// ParserInterp.kt
//
// String interpolation, for backtick strings only (specs/built-in-types.md): `@name` in a raw
// string's content is the name's value as text. It is a *desugar*, not a new node kind - the
// literal becomes one `fmtStrWith('@', ...)` call whose template is the content with every
// `@name` reduced to its `@` and whose items are the names, in order, so `"a=@x b=@y"` is
// `fmtStrWith('@', "a=@ b=@", x, y)` - and the concatenation fusion
// (cppsrc/linear/MergeConcat.kt) turns it into one buffer like any hand-written call.
//
// A string that interpolates may not hold a literal `@`: the runtime counts every `@` of the
// template against the items, so one that starts no name cannot line up and is a parse error
// with the two ways out. A raw string with no `@name` at all never reaches here - it is a
// plain literal, `@`s and all.
//
// A name ends at the first byte an identifier cannot hold, so a name followed by another
// identifier byte takes the parenthesized spelling `@(name)`: its `)` ends the name, which
// is what the separator of a generated name needs - `_sm_@(name)_@(n)` is the two names
// with the `_` between them. The two spellings produce the same `@` in the template and the
// same item.
//
// `@` is the marker only before an identifier start (`@(name)` included), and there is no
// escape for it yet, so a literal `@` immediately before an identifier is not spellable.
// `"@x"` is the two characters: a double-quoted string never interpolates.

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
        i = i + 1
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
        val ch: Char = raw.charAt(i)
        if (ch != '@') {
            templateText.append(ch)
            i = i + 1
            continue
        }
        if (i + 1 < end && raw.charAt(i + 1) == '(') {
            var close: Int = i + 2
            while (close < end && lexIsAlphaOrDigit(raw.charAt(close))) {
                close = close + 1
            }
            val closed: Bool = close < end && raw.charAt(close) == ')'
            val named: Bool = close > i + 2 && lexIsAlpha(raw.charAt(i + 2))
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
        if (i + 1 >= end || !lexIsAlpha(raw.charAt(i + 1))) {
            this.setError(
                interpPosAt(raw, i, pos),
                "invalid interpolation string: `@` must be followed by a name (`@name`) or `@(name)`; for a literal `@`, write it in a \"...\" string or in a raw string that does not interpolate"
            )
            return this.emptyExpr()
        }
        var j: Int = i + 1
        while (j < end && lexIsAlphaOrDigit(raw.charAt(j))) {
            j = j + 1
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
        k = k + 1
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
        val ch: Char = raw.charAt(i)
        if (ch == '\r') {
            if (i + 1 < raw.size() && raw.charAt(i + 1) == '\n') {
                i = i + 1
            }
            line = line + 1
            column = 1
        } else if (ch == '\n') {
            line = line + 1
            column = 1
        } else {
            column = column + 1
        }
        i = i + 1
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
        i = i + 1
    }
    var attrs: List<AstNodeAttribute> = this.posAttrs(pos.line, pos.column)
    var call: AstXmlNode = AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, attrs, kids.toArray())
    return ExprNode(call, pos.line, pos.column)
}
