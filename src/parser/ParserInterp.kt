// ParserInterp.kt
//
// String interpolation (specs/built-in-types.md), in both spellings: `@name` in a raw
// (backtick) string, and `$name` / `${expr}` in an ordinary quoted one - Kotlin's form, so
// a copied Kotlin string works as it stands. Each is a *desugar*, not a new node kind - the
// literal becomes one `fmtStrWith(sep, template, items...)` call whose template is the
// content with every item reduced to its separator byte and whose items are the names or
// expressions, in order, so `"a=$x b=$y"` is `fmtStrWith('@', "a=@ b=@", x, y)` - and the
// concatenation fusion (src/linear/MergeConcat.kt) turns it into one buffer like any
// hand-written call.
//
// A string that interpolates may not hold a literal separator *the desugar picked*: the
// runtime counts every separator byte of the template against the items, so one that starts
// no item cannot line up. In a raw string the separator is `@` (a literal `@` there is the
// parse error with the two ways out); in a quoted string the separator is chosen per string
// from candidates the text does not contain, so a literal `@` (an email address, say) only
// moves the choice down the list. A raw string with no `@name` at all never reaches here -
// it is a plain literal, `@`s and all.
//
// A name ends at the first byte an identifier cannot hold, so a name followed by another
// identifier byte takes the parenthesized spelling `@(name)`: its `)` ends the name, which
// is what the separator of a generated name needs - `_sm_@(name)_@(n)` is the two names
// with the `_` between them. The two spellings produce the same separator byte in the
// template and the same item.
//
// `@` is the raw-string marker only before an identifier start (`@(name)` included), and
// there is no escape for it yet, so a literal `@` immediately before an identifier is not
// spellable there. `$` is the quoted-string marker the same way, with one escape: `\$` is a
// literal dollar (the pool's own escape), and a `$` that starts no item is one as written.

package parser

import compiler

import lex
import common

// Whether a raw string token's content interpolates: one `@` followed by an identifier
// start, plain or parenthesized. Called first, and the desugar below scans the same way, so
// the pair stays in step.
fun interpHasItem(raw: *Str): Bool {
    if (raw.size() < 2 || raw[0] != '`') {
        return false
    }
    val end: Int = raw.size() - 1
    var i: Int = 1
    while (i + 1 < end) {
        if (raw[i] == '@' && lexIsAlpha(raw[i + 1])) {
            return true
        }
        if (raw[i] == '@' && raw[i + 1] == '(' && i + 2 < end && lexIsAlpha(raw[i + 2])) {
            return true
        }
        i++
    }
    return false
}

// Whether an ordinary quoted string interpolates a `$` item (Kotlin's spelling, so a copied
// Kotlin string works as it stands): `$name`, or `${` - one expression - not preceded by a
// backslash (`\$` is the escaped literal dollar, and an escape like `\x24` is one byte of
// output, never a marker). A `$` that starts neither is a literal dollar.
fun interpHasDollarItem(raw: *Str): Bool {
    if (raw.size() < 2 || raw[0] != '"') {
        return false
    }
    val end: Int = raw.size() - 1
    var i: Int = 1
    while (i < end) {
        if (raw[i] == '\\') {
            i = i + 2
            continue
        }
        if (raw[i] == '$') {
            if (i + 1 < end && (lexIsAlpha(raw[i + 1]) || raw[i + 1] == '{')) {
                return true
            }
        }
        i++
    }
    return false
}

// The literal as the `fmtStrWith` call it stands for: one `@` in the template per name -
// `@name` or `@(name)` - one name expression per item. A `@` that starts no name is an
// error, and the message names the spellings that work.
fun Parser.parseInterpolatedRaw(raw: *Str, pos: SourcePos): ExprNode {
    val end: Int = raw.size() - 1
    var templateText: Str = Str()
    var items: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 1
    while (i < end) {
        val ch: Char = raw[i]
        if (ch != '@') {
            templateText.append(ch)
            i++
            continue
        }
        if (i + 1 < end && raw[i + 1] == '(') {
            var close: Int = i + 2
            while (close < end && lexIsAlphaOrDigit(raw[close])) {
                close++
            }
            val closed: Bool = close < end && raw[close] == ')'
            val named: Bool = close > i + 2 && lexIsAlpha(raw[i + 2])
            if (!closed || !named) {
                this.setError(
                    interpPosAt(raw, i, pos),
                    "invalid interpolation string: `@(` must hold one name and be closed by `)` (`@(name)`); for a literal `@`, write it in a \"...\" string or in a raw string that does not interpolate"
                )
                return this.emptyExpr()
            }
            templateText.append('@')
            val nameInParens: Str = raw.substr(i + 2, close - i - 2)
            items.append(this.nameExprAt(nameInParens, pos).node)
            i = close + 1
            continue
        }
        if (i + 1 >= end || !lexIsAlpha(raw[i + 1])) {
            this.setError(
                interpPosAt(raw, i, pos),
                "invalid interpolation string: `@` must be followed by a name (`@name`) or `@(name)`; for a literal `@`, write it in a \"...\" string or in a raw string that does not interpolate"
            )
            return this.emptyExpr()
        }
        var j: Int = i + 1
        while (j < end && lexIsAlphaOrDigit(raw[j])) {
            j++
        }
        templateText.append('@')
        val name: Str = raw.substr(i + 1, j - i - 1)
        items.append(this.nameExprAt(name, pos).node)
        i = j
    }
    var args: List<AstXmlNode> = List<AstXmlNode>()
    args.append(this.interpCharLitAt('@', pos).node)
    args.append(this.interpTemplateNode(templateText, pos).node)
    var k: Int = 0
    while (k < items.size()) {
        args.append(items[k])
        k++
    }
    return this.interpCallAt("fmtStrWith", args, pos)
}

// The position of the byte at `index` of a raw string token that starts at `pos`: what the
// diagnostic about one `@` points at. Columns advance one per byte and a line ending is one
// `\n` - CRLF and a lone CR both - the scanner's own rule.
fun interpPosAt(raw: *Str, index: Int, pos: SourcePos): SourcePos {
    var line: Int = pos.line
    var column: Int = pos.column
    var i: Int = 0
    while (i < index) {
        val ch: Char = raw[i]
        if (ch == '\r') {
            if (i + 1 < raw.size() && raw[i + 1] == '\n') {
                i++
            }
            line++
            column = 1
        } else if (ch == '\n') {
            line++
            column = 1
        } else {
            column++
        }
        i++
    }
    return SourcePos(pos.offset + index, line, column)
}

// The template as an ordinary string literal: the content is spelled the way a raw string is,
// through `litRawString` (backslashes, quotes and line endings escaped), so the AST, the
// string pool and the fusion see one plain `"..."` literal.
fun Parser.interpTemplateNode(text: *Str, pos: SourcePos): ExprNode {
    var wrapped: Str = "`"
    wrapped.appendStrPtr(text)
    wrapped.append('`')
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, litRawString(wrapped)))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprStrLit, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}

// The separator as the char literal `fmtStrWith` takes first: the spelling a source char
// literal would have (`common/literals.kt`), so the fusion can read the byte back.
fun Parser.interpCharLitAt(ch: Char, pos: SourcePos): ExprNode {
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, litSpellChar(ch)))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCharLit, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}

// A free call named `callee` with `args` in order: the same node `parsePostfix` builds for
// `fmtStrWith(...)` written by hand.
fun Parser.interpCallAt(callee: *Str, args: *List<AstXmlNode>, pos: SourcePos): ExprNode {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    kids.append(this.roleOf(this.nameExprAt(callee, pos).node, AstNodeKind.Callee))
    var i: Int = 0
    while (i < args.size()) {
        kids.append(this.roleOf(*args[i], AstNodeKind.Arg))
        i++
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, attrs, kids.toArray())
    return ExprNode(call, pos.line, pos.column)
}

// The `$` interpolation of an ordinary quoted string, as the `fmtStrWith` call it stands
// for - the same desugar `@name` takes in a raw string, with two differences the quoted
// form's own rules force:
//
// - The template keeps the string's *source* bytes, escapes and all, and is emitted as an
//   ordinary `"..."` literal, so the string pool decodes it exactly as it would have
//   decoded the original (`\x41` stays one byte of output, never a marker). `\$` is kept
//   as written: it is the pool's own escape of a literal dollar, and it is also how a `$`
//   that would otherwise start an item is written.
// - The separator is *chosen*: the runtime counts every separator byte of the template
//   against the items, and a quoted string may hold a literal `@` (an email address) where
//   a raw string may not - so the desugar picks the first candidate the template's literal
//   text does not contain, `@` first (the raw-string spelling, and the one the fusion
//   reads), then `|`, `~`, `^`, `%`, and - strings being bytes - `\x01`, which cannot be
//   in the text. A non-fusing separator still runs; the fusion is a speedup, not a rule.
//
// `${` holds one expression, not just a name: the fragment is scanned and parsed with the
// parser's own machinery, from positions that point into the original string, so a
// diagnostic inside an item names the line and column the user wrote.
fun Parser.parseInterpolatedQuoted(raw: *Str, pos: SourcePos): ExprNode {
    val end: Int = raw.size() - 1
    var segments: List<Str> = List<Str>()
    var items: List<AstXmlNode> = List<AstXmlNode>()
    var text: Str = Str()
    var i: Int = 1
    while (i < end) {
        val ch: Char = raw[i]
        if (ch == '\\' && i + 1 < end && raw[i + 1] == '$') {
            text.append(ch)
            text.append(raw[i + 1])
            i = i + 2
            continue
        }
        if (ch != '$') {
            text.append(ch)
            i++
            continue
        }
        if (i + 1 < end && lexIsAlpha(raw[i + 1])) {
            var j: Int = i + 1
            while (j < end && lexIsAlphaOrDigit(raw[j])) {
                j++
            }
            segments.append(text)
            items.append(this.nameExprAt(raw.substr(i + 1, j - i - 1), pos).node)
            text = Str()
            i = j
            continue
        }
        if (i + 1 < end && raw[i + 1] == '{') {
            var close: Int = i + 2
            var depth: Int = 1
            while (close < end && depth > 0) {
                if (raw[close] == '{') {
                    depth++
                } else if (raw[close] == '}') {
                    depth--
                }
                if (depth > 0) {
                    close++
                }
            }
            if (depth != 0) {
                this.setError(
                    interpPosAt(raw, i, pos),
                    "invalid interpolation string: a dollar-brace item must be closed by a brace (`one expression` inside)"
                )
                return this.emptyExpr()
            }
            val at: SourcePos = interpPosAt(raw, i, pos)
            val fragment: Str = raw.substr(i + 2, close - i - 2)
            val value: AstXmlNode = this.interpFragmentExpr(fragment, at)
            if (this.failed) {
                return this.emptyExpr()
            }
            segments.append(text)
            items.append(value)
            text = Str()
            i = close + 1
            continue
        }
        // A `$` that starts no item is a literal dollar.
        text.append(ch)
        i++
    }
    segments.append(text)

    var separator: Char = '@'
    var found: Bool = false
    var c: Int = 0
    val candidates: Str = "@|~^%\x01"
    while (c < candidates.size() && !found) {
        val candidate: Char = candidates[c]
        var present: Bool = false
        var s: Int = 0
        while (s < segments.size() && !present) {
            var seg: Str = segments[s]
            var b: Int = 0
            while (b < seg.size()) {
                if (seg[b] == candidate) {
                    present = true
                }
                b++
            }
            s++
        }
        if (!present) {
            separator = candidate
            found = true
        }
        c++
    }
    if (!found) {
        this.setError(pos, "invalid interpolation string: no separator byte is free in the template")
        return this.emptyExpr()
    }

    var templateText: Str = Str()
    var k: Int = 0
    while (k < segments.size()) {
        if (k > 0) {
            templateText.append(separator)
        }
        templateText.appendStrPtr(segments[k])
        k++
    }

    var args: List<AstXmlNode> = List<AstXmlNode>()
    args.append(this.interpCharLitAt(separator, pos).node)
    args.append(this.interpQuotedTemplateNode(templateText, pos).node)
    var a: Int = 0
    while (a < items.size()) {
        args.append(items[a])
        a++
    }
    return this.interpCallAt("fmtStrWith", args, pos)
}

// One expression from the inside of a `${...}`: scanned from `at` (a position inside the
// original string) and parsed by a parser of its own, so the expression is any the language
// takes and a diagnostic in it points where the user wrote it. The fragment must be exactly
// one expression - anything after it is the error, not a silent drop.
fun Parser.interpFragmentExpr(fragment: *Str, at: SourcePos): AstXmlNode {
    // The cursor starts at the fragment's first byte (`pos` indexes `source`), while the
    // line and column are the item's own, so a diagnostic inside it points where the user
    // wrote the interpolation, not into a string they cannot see.
    var scanner: Scanner = Scanner(0, at.line, at.column, fragment)
    var toks: List<Token> = List<Token>()
    while (true) {
        val result: Res<Token> = scanner.nextToken()
        if (!result.isOk()) {
            this.failed = true
            this.error = result.Error
            return this.emptyNode()
        }
        if (result.Value.kind == TokenKind.Eof) {
            break
        }
        if (result.Value.kind != TokenKind.Space && result.Value.kind != TokenKind.Comment) {
            toks.append(result.Value)
        }
    }
    toks.append(Token("", TokenKind.Eof, at))
    var parser: Parser = Parser(spanOf(*toks), false, "", this.file, 1, 0, this.emptyNode(), 0)
    val value: ExprNode = parser.parseExpr(0)
    if (parser.failed) {
        this.failed = true
        this.error = parser.error
        return this.emptyNode()
    }
    if (!parser.atEnd()) {
        this.setError(at, "invalid interpolation string: a dollar-brace item holds one expression")
        return this.emptyNode()
    }
    return value.node
}

// The template of a quoted-string interpolation, as the `"..."` literal it is: the source
// bytes as written (the escapes the pool already decodes), one separator byte per item.
fun Parser.interpQuotedTemplateNode(text: *Str, pos: SourcePos): ExprNode {
    var wrapped: Str = "\""
    wrapped.appendStrPtr(text)
    wrapped.append('"')
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    attrs.append(AstNodeAttribute(AstNodeAttributeKind.Text, wrapped))
    return ExprNode(
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprStrLit, attrs, Array<AstXmlNode>()),
        pos.line,
        pos.column
    )
}
