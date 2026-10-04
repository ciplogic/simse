// Scanner.kt
//
// The Simse-language scanner: token kinds, the matcher table a token's first byte looks up, the
// matchers themselves, and the `Scanner` type. `StrView` is the RTL's borrowed view
// (`src/rtl/StrView.kt`).

package lex

import common

enum class TokenKind {
    None,
    Space,
    Comment,
    EndOfLine,
    Identifier,
    ReservedWord,
    Number,
    String,
    Character,
    Operator,
    Attribute,
    Eof
}

// What a matcher found: how many leading bytes it accepts, and the token kind those bytes make.
// Zero bytes is no match. A byte whose scan can end in two kinds has one matcher that decides
// between them (`/` is a comment or an operator; a letter is a keyword or an identifier).
data class ScanMatch(
    var length: Int,
    var kind: TokenKind
)

typealias ScanFunc = (StrView) -> ScanMatch

typealias CharPredicate = (Char) -> Bool

data class Token(
    var text: Str,

    var kind: TokenKind,
    var pos: SourcePos
)

// The token at the cursor of `view`, through the matcher its first byte selects
// (`tokenStartTable`). One matcher runs where the rule walk tried every registered one in turn.
// A byte above 127 is negative (a `Char` is a signed byte), so it indexes nothing and no token
// starts with it.
fun scanAt(view: StrView): ScanMatch {
    if (view.size() == 0) {
        return ScanMatch(0, TokenKind.None)
    }
    var code: Int = view.at(0)
    if (code < 0) {
        return ScanMatch(0, TokenKind.None)
    }
    val scan: ScanFunc = tokenStartTable[code]
    return scan(view)
}

// Horizontal whitespace only. Line endings are their own token kind. The `lex` prefix keeps
// these out of the way of the RTL's own `Char.isSpace()`/`isDigit()`/... extensions (the
// emitter's name table is flat, so a shared name would resolve to the wrong one).
fun lexIsSpace(ch: Char): Bool {
    return ch == ' ' || ch == '\t'
}

fun lexIsDigit(ch: Char): Bool {
    return ch >= '0' && ch <= '9'
}

// The identifier rule: letters, digits and `_`.
fun lexIsAlpha(ch: Char): Bool {
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || ch == '_'
}

fun lexIsAlphaOrDigit(ch: Char): Bool {
    return lexIsAlpha(ch) || lexIsDigit(ch)
}

fun isOperatorChar(ch: Char): Bool {
    return ch == '+' || ch == '-' || ch == '*' || ch == '/'
            || ch == '%' || ch == '=' || ch == '<' || ch == '>'
            || ch == '!' || ch == '&' || ch == '|' || ch == '^'
            || ch == '~' || ch == '?' || ch == ':' || ch == ';'
            || ch == ',' || ch == '.' || ch == '(' || ch == ')'
            || ch == '[' || ch == ']' || ch == '{' || ch == '}'
}

fun matchAllOfRule(view: StrView, predicate: CharPredicate): Int {
    var i = 0
    while (i < view.size()) {
        if (!predicate(view.at(i))) {
            return i
        }
        i = i + 1
    }
    return view.size()
}

fun matchAllOfRules(view: StrView, first: CharPredicate, rest: CharPredicate): Int {
    if (view.size() == 0) {
        return 0
    }
    if (!first(view.at(0))) {
        return 0
    }
    var i = 1
    while (i < view.size()) {
        if (!rest(view.at(i))) {
            return i
        }
        i = i + 1
    }
    return view.size()
}

// Static storage (specs/statics.md), read through a raw pointer (`*List<T>`) so matching copies
// nothing; a `List<Str>`-returning accessor would rebuild the table per call.

var reservedWordTable: List<Str> = makeReservedWords()
var multiCharOperatorTable: List<Str> = makeMultiCharOperators()
var tokenStartTable: List<ScanFunc> = makeTokenStartTable()

// One byte -> the matcher that scans the token starting with it. A byte left `matchNoToken`
// starts no token, so `nextToken` reports it without trying anything; a byte whose scan can end
// in two kinds maps to the one matcher that decides (`/` -> `matchCommentOrOperator`, a letter ->
// `matchName`). The table is 256 long, so a non-negative byte indexes it directly.
fun makeTokenStartTable(): List<ScanFunc> {
    var table: List<ScanFunc> = List<ScanFunc>(256, matchNoToken)
    markScan(*table, " \t", matchSpaces)
    markScan(*table, "\n\r", matchEndOfLine)
    markScan(*table, "\"", matchStringLiteral)
    markScan(*table, "`", matchRawStringLiteral)
    markScan(*table, "'", matchCharLiteral)
    markScan(*table, "@", matchAttribute)
    markScan(*table, "_", matchName)
    // `/` alone starts a comment (`//`, `/*`) as well as an operator (`/=`, `/`).
    markScan(*table, "/", matchCommentOrOperator)
    // The multi-char operators' first bytes (`->`, `==`, `<<=`, `..`, ...).
    markScan(*table, "-=!<>&|+*%^.", matchOperator)
    // Operator bytes no multi-char operator starts (`(`, `,`, `;`, ...): always one byte.
    markScan(*table, "~?:;,()[]{}", matchSingleOperator)
    var d: Int = '0'
    while (d <= '9') {
        table[d] = matchNumber
        d = d + 1
    }
    var c: Int = 'a'
    while (c <= 'z') {
        table[c] = matchName
        c = c + 1
    }
    c = 'A'
    while (c <= 'Z') {
        table[c] = matchName
        c = c + 1
    }
    return table
}

// Every byte of `chars` assigned `scan`.
fun markScan(table: *List<ScanFunc>, chars: *Str, scan: ScanFunc): Unit {
    var i: Int = 0
    while (i < chars.size()) {
        var code: Int = chars[i]
        table[code] = scan
        i = i + 1
    }
}

fun makeReservedWords(): List<Str> {
    // `suspend` (impl_specs/async.md) is the modifier that marks a declaration whose body may
    // wait; it is a keyword, so a program cannot also use the name.
    var words: List<Str> = listOf<Str>(
        "class", "data", "val", "var", "fun", "return", "while", "for",
        "if", "else", "true", "false", "null", "enum", "typealias",
        "import", "this", "break", "continue", "when", "yield", "package", "suspend",
        "union"
    )
    return words
}

fun makeMultiCharOperators(): List<Str> {
    // Longest first where one entry starts another (`>>=` before `>>`): the lookup returns the
    // first entry the view starts with.
    var operators: List<Str> = listOf<Str>(
        "->", "==", "!=", "<=", ">=", "&&", "||", "+=", "-=", "*=", "/=", "%=",
        "&=", "|=", "^=", "<<=", ">>=", "<<", ">>",
        "++", "--", ".."
    )
    return operators
}

// How much of `view` the table matches, or 0. `exact` requires the whole view to be an entry
// (a reserved word); without it the first entry `view` starts with wins (a multi-char operator).
fun tableMatch(view: StrView, table: *List<Str>, exact: Bool): Int {
    if (view.size() == 0) {
        return 0
    }
    val first: Char = view.at(0)
    var i: Int = 0
    while (i < table.size()) {
        val entry: *Str = *table[i]
        val length: Int = entry.size()
        i = i + 1
        if (length == 0 || entry[0] != first) {
            continue
        }
        if (view.size() < length || (exact && view.size() != length)) {
            continue
        }
        if (view.startsWith(entry)) {
            return length
        }
    }
    return 0
}

// Pointers into the tables, so a caller can look without copying.
fun reservedWords(): *List<Str> {
    return * reservedWordTable
}

fun multiCharOperators(): *List<Str> {
    return * multiCharOperatorTable
}

fun isReservedWord(view: StrView): Bool {
    return tableMatch(view, reservedWordTable, true) > 0
}

// The matcher a byte with no token maps to: no bytes, no kind - `nextToken` turns it into the
// scanning error.
fun matchNoToken(view: StrView): ScanMatch {
    return ScanMatch(0, TokenKind.None)
}

fun matchSpaces(view: StrView): ScanMatch {
    return ScanMatch(matchAllOfRule(view, lexIsSpace), TokenKind.Space)
}

// A line ending is CRLF, LF, or CR, matched as a whole.
fun matchEndOfLine(view: StrView): ScanMatch {
    if (view.size() == 0) {
        return ScanMatch(0, TokenKind.None)
    }
    val ch: Char = view.at(0)
    when (ch) {
        '\n' -> {
            return ScanMatch(1, TokenKind.EndOfLine)
        }

        '\r' -> {
            if (view.size() >= 2 && view.at(1) == '\n') {
                return ScanMatch(2, TokenKind.EndOfLine)
            }
            return ScanMatch(1, TokenKind.EndOfLine)
        }
    }
    return ScanMatch(0, TokenKind.None)
}

// A name: one scan answers both the keyword and the identifier, where the rule walk scanned it
// twice (`matchReservedWord`, then `matchIdentifier`). The reserved table is the only thing that
// separates them.
fun matchName(view: StrView): ScanMatch {
    val length: Int = matchAllOfRules(view, lexIsAlpha, lexIsAlphaOrDigit)
    if (length == 0) {
        return ScanMatch(0, TokenKind.None)
    }
    if (isReservedWord(view.slice(0, length))) {
        return ScanMatch(length, TokenKind.ReservedWord)
    }
    return ScanMatch(length, TokenKind.Identifier)
}

// `@Identifier` (specs/attributes.md). The token's text keeps the `@` (it is the matched
// slice); the parser strips it.
fun matchAttribute(view: StrView): ScanMatch {
    if (view.size() < 2 || view.at(0) != '@') {
        return ScanMatch(0, TokenKind.None)
    }
    if (!lexIsAlpha(view.at(1))) {
        return ScanMatch(0, TokenKind.None)
    }
    var i: Int = 2
    while (i < view.size() && lexIsAlphaOrDigit(view.at(i))) {
        i = i + 1
    }
    return ScanMatch(i, TokenKind.Attribute)
}

fun matchNumber(view: StrView): ScanMatch {
    var i = 0
    while (i < view.size() && lexIsDigit(view.at(i))) {
        i = i + 1
    }
    if (i == 0) {
        return ScanMatch(0, TokenKind.None)
    }
    if (i + 1 < view.size() && view.at(i) == '.' && lexIsDigit(view.at(i + 1))) {
        i = i + 1
        while (i < view.size() && lexIsDigit(view.at(i))) {
            i = i + 1
        }
    }
    return ScanMatch(i, TokenKind.Number)
}

fun matchComment(view: StrView): ScanMatch {
    if (view.size() < 2 || view.at(0) != '/') {
        return ScanMatch(0, TokenKind.None)
    }
    if (view.at(1) == '/') {
        var i = 2
        while (i < view.size() && view.at(i) != '\n' && view.at(i) != '\r') {
            i = i + 1
        }
        return ScanMatch(i, TokenKind.Comment)
    }
    if (view.at(1) == '*') {
        var i = 2
        while (i + 1 < view.size()) {
            if (view.at(i) == '*' && view.at(i + 1) == '/') {
                return ScanMatch(i + 2, TokenKind.Comment)
            }
            i = i + 1
        }
    }
    return ScanMatch(0, TokenKind.None)
}

// `/` is the one byte whose scan ends in two kinds: the comment is tried first, exactly as the
// rule walk's order did, and a `/` that starts neither `//` nor `/*` is the operator it also is.
fun matchCommentOrOperator(view: StrView): ScanMatch {
    val comment: ScanMatch = matchComment(view)
    if (comment.length > 0) {
        return comment
    }
    return matchOperator(view)
}

fun matchStringLiteral(view: StrView): ScanMatch {
    if (view.size() == 0 || view.at(0) != '\"') {
        return ScanMatch(0, TokenKind.None)
    }
    var i = 1
    while (i < view.size()) {
        val ch: Char = view.at(i)
        when (ch) {
            '\\' -> {
                i = i + 2
                continue
            }

            '\"' -> {
                return ScanMatch(i + 1, TokenKind.String)
            }
        }
        i = i + 1
    }
    return ScanMatch(0, TokenKind.None)
}

// A backtick string: a raw, multi-line text with no escape - the next backtick ends it, so a
// backtick cannot appear inside and there is nothing to escape. Its `@name` interpolation is
// the parser's desugar (src/parser/ParserInterp.kt), not the scanner's: the token still
// runs from backtick to backtick. A string with no `@name` is stored as the ordinary quoted
// literal litRawString spells from its content (common/literals.kt), so everything downstream
// sees a plain string literal.
fun matchRawStringLiteral(view: StrView): ScanMatch {
    if (view.size() == 0 || view.at(0) != '`') {
        return ScanMatch(0, TokenKind.None)
    }
    var i = 1
    while (i < view.size()) {
        if (view.at(i) == '`') {
            return ScanMatch(i + 1, TokenKind.String)
        }
        i = i + 1
    }
    return ScanMatch(0, TokenKind.None)
}

fun matchCharLiteral(view: StrView): ScanMatch {
    if (view.size() == 0 || view.at(0) != '\'') {
        return ScanMatch(0, TokenKind.None)
    }
    var i = 1
    while (i < view.size()) {
        val ch: Char = view.at(i)
        when (ch) {
            '\\' -> {
                i = i + 2
                continue
            }

            '\'' -> {
                return ScanMatch(i + 1, TokenKind.Character)
            }

            '\n' -> {
                return ScanMatch(0, TokenKind.None)
            }
        }
        i = i + 1
    }
    return ScanMatch(0, TokenKind.None)
}

// A byte a multi-char operator starts (`<=`, `<<=`, `->`, `..`, ...): the table decides, and a
// byte with no multi-char entry is the one-byte operator it also is.
fun matchOperator(view: StrView): ScanMatch {
    val matched: Int = tableMatch(view, multiCharOperatorTable, false)
    if (matched > 0) {
        return ScanMatch(matched, TokenKind.Operator)
    }
    if (view.size() > 0 && isOperatorChar(view.at(0))) {
        return ScanMatch(1, TokenKind.Operator)
    }
    return ScanMatch(0, TokenKind.None)
}

// An operator byte no multi-char operator starts (`(`, `,`, `;`, `~`, ...): the table says the
// token is one byte, so the multi-char table is never consulted for it.
fun matchSingleOperator(view: StrView): ScanMatch {
    return ScanMatch(1, TokenKind.Operator)
}

// Escapes the first `maxLen` bytes for a single-line diagnostic: printable ASCII (32..126)
// kept, every other byte as \xNN with two uppercase hex digits, bytes unsigned.
fun escapedSnippet(view: StrView, maxLen: Int): Str {
    val hexDigits: Str = "0123456789ABCDEF"
    var snippet = Str()
    var count: Int = view.size()
    if (count > maxLen) {
        count = maxLen
    }
    var i = 0
    while (i < count) {
        var byte: Int = view.at(i)
        if (byte < 0) {
            byte = byte + 256
        }
        if (byte == 92) {
            snippet.append('\\')
            snippet.append('\\')
        } else if (byte == 10) {
            snippet.append('\\')
            snippet.append('n')
        } else if (byte == 13) {
            snippet.append('\\')
            snippet.append('r')
        } else if (byte == 9) {
            snippet.append('\\')
            snippet.append('t')
        } else if (byte >= 32 && byte <= 126) {
            snippet.append(view.at(i))
        } else {
            snippet.append('\\')
            snippet.append('x')
            snippet.append(hexDigits[byte / 16])
            snippet.append(hexDigits[byte % 16])
        }
        i = i + 1
    }
    return snippet
}

fun unexpectedCharacterMessage(line: Int, column: Int, snippet: *Str): Str {
    return `@line:@column: Unexpected character: '@snippet'`
}

data class Scanner(
    var pos: Int,
    var line: Int,
    var column: Int,
    var source: Str
) {
    fun setSource(text: *Str): Unit {
        this.source = text
        this.pos = 0
        this.line = 1
        this.column = 1
    }

    // A newline is '\n', or '\r' not immediately followed by '\n' (so CRLF counts once); tabs
    // count as a single column.
    fun advance(count: Int): Unit {
        var i = 0
        while (i < count) {
            val ch: Char = this.source[this.pos]
            val followedByLf: Bool = this.pos + 1 < this.source.size() && this.source[this.pos + 1] == '\n'
            if (ch == '\n' || (ch == '\r' && !followedByLf)) {
                this.line = this.line + 1
                this.column = 1
            } else {
                this.column = this.column + 1
            }
            this.pos = this.pos + 1
            i = i + 1
        }
    }

    // One token at the cursor, the scanning error, or `Eof` at the end. The first byte selects a
    // matcher (`scanAt`), so exactly one runs - where the rule walk tried every registered
    // matcher in turn, and a name was scanned twice (`matchReservedWord`, then `matchIdentifier`).
    // A byte no token starts answers no bytes and is the error.
    fun nextToken(): Res<Token> {
        if (this.pos >= this.source.size()) {
            return Res<Token>.ok(Token("", TokenKind.Eof, SourcePos(this.pos, this.line, this.column)))
        }
        // A view of the scanner's own `source`, not a copy (`src/rtl/StrView.kt`).
        val view: StrView = spanOfStr(this.source).slice(this.pos)
        val matched: ScanMatch = scanAt(view)
        if (matched.length <= 0) {
            return Res<Token>.err(unexpectedCharacterMessage(this.line, this.column, escapedSnippet(view, 10)))
        }
        val startPos: SourcePos = SourcePos(this.pos, this.line, this.column)
        val token: Token = Token(view.slice(0, matched.length).toString(), matched.kind, startPos)
        this.advance(matched.length)
        return Res<Token>.ok(token)
    }
}

// Every token up to (not including) Eof, or the scanning error.
fun readFileAsTokens(scanner: *Scanner, fileName: *Str): Res<List<Token>> {
    val content: Str = readFile(fileName)
    scanner.setSource(content)

    var tokens: List<Token> = List<Token>()
    while (true) {
        val result: Res<Token> = scanner.nextToken()
        if (!result.isOk()) {
            val errorText: Str = result.error
            return Res<List<Token>>.err(`@fileName: @errorText`)
        }
        if (result.value.kind == TokenKind.Eof) {
            return Res<List<Token>>.ok(tokens)
        }
        tokens.append(result.value)
    }
}

fun isSpaceBasedToken(kind: TokenKind): Bool {
    return kind == TokenKind.Space || kind == TokenKind.Comment
}

// Like readFileAsTokens, but drops Space and Comment tokens.
fun readFileAndSkipSpacesTokens(scanner: *Scanner, fileName: *Str): Res<List<Token>> {
    val allResult: Res<List<Token>> = readFileAsTokens(scanner, fileName)
    if (!allResult.isOk()) {
        return Res<List<Token>>.err(allResult.error)
    }

    var tokens: List<Token> = List<Token>()
    val all: List<Token> = allResult.value
    for (*token in all) {
        if (!isSpaceBasedToken(token.kind)) {
            tokens.append(token)
        }
    }
    return Res<List<Token>>.ok(tokens)
}
