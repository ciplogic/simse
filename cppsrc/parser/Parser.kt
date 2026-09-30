// Parser.kt
//
// The grammar parser: it consumes tokens from the scanner and builds an AstXmlNode tree
// (impl_specs/ast-xmlnode.md). Errors are recorded in `failed`/`error`; the entry points
// return Res.err with a positioned message.
//
// `for` and `when` are desugared here, so no stage downstream sees either.

package parser

import lex
import common

// The position travels with the expression: a parent node takes its own from the operand
// it extends (callee, receiver or left).
data class ExprNode(
    var node: AstXmlNode,

    var line: Int,
    var column: Int
)

data class Parser(
    var cursor: Span<Token>,

    var failed: Bool,
    var error: Str,
    var file: Str,
    var nextTemplateId: Int,
    // The `>`s a `>>` closer left over: see `matchGenericCloser`.
    var pendingClosers: Int,
    // The enclosing function's declared return type (empty outside one): what a
    // parenthesized `return (x)` constructs, via the `initByValue` convention.
    var returnTypeCtx: AstXmlNode,
    // Unique suffix for the temporary a `return (...)` construction declares.
    var ctorCounter: Int
) {

    fun peek(offset: Int): Token {
        if (offset <= 0) {
            return this.cursor[0]
        }
        val rest: Span<Token> = this.cursor.slice(offset)
        if (!rest.isEmpty()) {
            return rest[0]
        }
        return this.cursor[this.cursor.size() - 1]
    }

    fun advance(): Token {
        val token: Token = this.cursor[0]
        if (this.cursor.size() > 1) {
            this.cursor = this.cursor.slice(1)
        }
        return token
    }

    fun atEnd(): Bool {
        return this.peek(0).kind == TokenKind.Eof
    }

    fun checkText(text: *Str): Bool {
        return this.peek(0).text == text
    }

    fun checkKind(kind: TokenKind): Bool {
        return this.peek(0).kind == kind
    }

    fun matchText(text: *Str): Bool {
        if (this.checkText(text)) {
            this.advance()
            return true
        }
        return false
    }

    fun setError(pos: SourcePos, message: *Str): Unit {
        if (this.failed) {
            return
        }
        this.failed = true
        this.error = fmtStr(
            "|:|:|: |",
            this.file, pos.line.toString(), pos.column.toString(), message
        )
    }

    fun fail(message: *Str): Bool {
        this.setError(this.peek(0).pos, message)
        return false
    }

    fun expectText(text: *Str): Bool {
        if (this.matchText(text)) {
            return true
        }
        return this.fail(fmtStr("expected '|'", text))
    }

    fun expectName(): Str {
        if (this.checkKind(TokenKind.Identifier)) {
            return this.advance().text
        }
        this.fail("expected name")
        return ""
    }

    // `>>` scans as one shift token, so a closer takes one `>` out of it and leaves the rest
    // pending for the closer of the list it is nested in (`List<List<Int>>`). `>>=` is an error.
    fun checkGenericCloser(): Bool {
        if (this.pendingClosers > 0) {
            return true
        }
        val text: Str = this.peek(0).text
        return text == ">" || text == ">>"
    }

    fun matchGenericCloser(): Bool {
        if (this.pendingClosers > 0) {
            this.pendingClosers = this.pendingClosers - 1
            return true
        }
        if (this.matchText(">")) {
            return true
        }
        if (this.matchText(">>")) {
            this.pendingClosers = 1
            return true
        }
        if (this.checkText(">>=")) {
            return this.fail("a `>` closer ran into `=`: write a space before it")
        }
        return this.fail("expected '>'")
    }

    fun skipSeparators(): Unit {
        while (this.checkKind(TokenKind.EndOfLine) || this.checkText(";")) {
            this.advance()
        }
    }

    // A data class's fields separate on `,` (or `;`); only the field list takes a comma,
    // since between statements and methods one would read as an expression part.
    fun skipFieldSeparators(): Unit {
        while (this.checkKind(TokenKind.EndOfLine) || this.checkText(";") || this.checkText(",")) {
            this.advance()
        }
    }

    fun skipNewlines(): Unit {
        while (this.checkKind(TokenKind.EndOfLine)) {
            this.advance()
        }
    }

    fun atStmtEnd(): Bool {
        return this.checkKind(TokenKind.EndOfLine) || this.atEnd()
                || this.checkText(";") || this.checkText("}")
    }

    fun emptyNode(): AstXmlNode {
        return AstXmlNode(AstNodeKind.None, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
    }

    fun emptyExpr(): ExprNode {
        return ExprNode(this.emptyNode(), 0, 0)
    }

    // A comparison a string literal is tested against: `Str == "lit"` compares the *view* of
    // the string against the literal, never a copy of it (`spanOfStr`, cppsrc/rtl/StrView.kt) -
    // the view operators read the bytes in place, and a `*Str` operand is not read through at
    // all. The `when`-over-strings desugar does the same for its tests (`parseWhen`).
    fun isStringCompareOp(op: *Str): Bool {
        return op == "==" || op == "!=" || op == "<" || op == "<=" || op == ">" || op == ">="
    }

    fun posAttrs(line: Int, column: Int): List<AstNodeAttribute> {
        var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
            AstNodeAttribute(AstNodeAttributeKind.Line, line.toString()),
            AstNodeAttribute(AstNodeAttributeKind.Column, column.toString())
        )
        return attrs
    }

    // `parent` is a non-owning pointer: the append replaces the caller's `Children` handle.
    fun attach(parent: *AstXmlNode, role: AstNodeKind, child: *AstXmlNode): Unit {
        xmlAddChild(parent, this.roleOf(child, role))
    }

    // Sets `child`'s role without attaching it, for a caller collecting children itself:
    // appending one at a time through `xmlAddChild` is O(k*k) for k children.
    fun roleOf(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
        var renamed: AstXmlNode = child
        renamed.name = role
        return renamed
    }

    // Children are built once from the list rather than appended to per child.
    fun container(name: AstNodeKind, children: *List<AstXmlNode>): AstXmlNode {
        return AstXmlNode(name, AstNodeCategory.None, List<AstNodeAttribute>(), children.toArray())
    }

    // The names are read only; a `*List<Str>` avoids copying the caller's list.
    fun appendTypeParams(node: *AstXmlNode, names: *List<Str>): Unit {
        var params: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < names.size()) {
            var attrs: List<AstNodeAttribute> = List<AstNodeAttribute>()
            attrs.append(AstNodeAttribute(AstNodeAttributeKind.Name, names[i]))
            params.append(AstXmlNode(AstNodeKind.TypeParam, AstNodeCategory.None, attrs, Array<AstXmlNode>()))
            i = i + 1
        }
        xmlAddChildren(node, params)
    }
}


// Parses a pre-filtered token cursor (with a synthetic Eof already appended).
fun parseModule(cursor: Span<Token>, fileName: *Str): Res<AstXmlNode> {
    var parser: Parser = Parser(
        cursor, false, "", fileName, 1, 0,
        AstXmlNode(AstNodeKind.None, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>()), 0
    )
    val root: AstXmlNode = parser.parseRoot()
    if (parser.failed) {
        return Res<AstXmlNode>.err(parser.error)
    }
    return Res<AstXmlNode>.ok(root)
}

// Drops Space/Comment tokens and appends a synthetic Eof. A newline inside `(...)` or
// `[...]` separates nothing: the list may be wrapped (specs/declarations.md).
fun parseModule(tokens: *List<Token>, fileName: *Str): Res<AstXmlNode> {
    var toks: List<Token> = List<Token>()
    var bracketed: Int = 0
    var i: Int = 0
    while (i < tokens.size()) {
        val token: Token = tokens[i]
        if (token.kind == TokenKind.Operator) {
            if (token.text == "(" || token.text == "[") {
                bracketed = bracketed + 1
            } else if (token.text == ")" || token.text == "]") {
                if (bracketed > 0) {
                    bracketed = bracketed - 1
                }
            }
        }
        if (token.kind != TokenKind.Space && token.kind != TokenKind.Comment) {
            if (!(token.kind == TokenKind.EndOfLine && bracketed > 0)) {
                toks.append(token)
            }
        }
        i = i + 1
    }
    var eofPos: SourcePos = SourcePos(0, 1, 1)
    if (toks.size() > 0) {
        eofPos = toks[toks.size() - 1].pos
    }
    toks.append(Token("", TokenKind.Eof, eofPos))
    return parseModule(spanOf(*toks), fileName)
}
