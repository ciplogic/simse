// ParserInterp.kt
//
// String interpolation, for backtick strings only (specs/built-in-types.md): `@name` in a raw
// string's content is the name's value as text. It is a *desugar*, not a new node kind - the
// literal becomes one `fmtStrWith('@', ...)` call whose template is the content with every
// `@name` reduced to its `@` and whose items are the names, in order, so `"a=@x b=@y"` is
// `fmtStrWith('@', "a=@ b=@", x, y)` - and the concatenation fusion
// (`cppsrc/linear/MergeConcat.kt`) turns it into one buffer like any hand-written call.
//
// A literal `@` (one not followed by an identifier start) is a placeholder too: the runtime
// counts every `@` of the template, so it is passed as the one-byte item `"@"`. That is the
// only bookkeeping - the separator is `@` always, whatever the text holds.
//
// `@` is the marker only before an identifier start; anywhere else it is the literal
// character, and there is no escape for it yet, so a literal `@` immediately before an
// identifier is not spellable. `"@x"` is the two characters: a double-quoted string never
// interpolates.

package parser
import compiler

import lex
import common

// Whether a raw string token's content interpolates: one `@` followed by an identifier start.
// Called first, and the desugar below scans the same way, so the pair stays in step.
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
        i = i + 1
    }
    return false
}

// The literal as the `fmtStrWith` call it stands for: one `@` in the template per `@` of the
// content, one item per placeholder - a name expression for `@name`, the one-byte item `"@"`
// for a bare `@`.
fun Parser.parseInterpolatedRaw(raw: *Str, pos: SourcePos): ExprNode {
    val end: Int = raw.size() - 1
    val atItem: ExprNode = this.interpTemplateNode("@", pos)
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
        templateText.append('@')
        if (i + 1 < end && lexIsAlpha(raw.charAt(i + 1))) {
            var j: Int = i + 1
            while (j < end && lexIsAlphaOrDigit(raw.charAt(j))) {
                j = j + 1
            }
            val name: Str = raw.substr(i + 1, j - i - 1)
            items.append(this.nameExprAt(name, pos).node)
            i = j
            continue
        }
        items.append(atItem.node)
        i = i + 1
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
