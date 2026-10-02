// ParserInterp.kt
//
// String interpolation, for backtick strings only (specs/built-in-types.md): `@name` in a raw
// string's content is the name's value as text. It is a *desugar*, not a new node kind - the
// literal becomes one `fmtStr` call whose template carries one `|` where each `@name` stood
// and whose items are the names, in order, so `"a=@x b=@y"` is `fmtStr("a=| b=|", x, y)` -
// and the concatenation fusion and every stage downstream see the ordinary shapes
// (cppsrc/linear/MergeConcat.kt). A piece that itself holds a `|` would break the runtime's
// one-item-per-pipe count, so that call is an `fmtStrWith` with a separator no piece holds.
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

// The literal `@name` split into its pieces and items: `pieces` is one text per gap (one more
// than the items), `items` one name expression per `@name`, and `hasPipe` whether a piece
// holds a literal `|` (what picks the call's shape below).
fun Parser.parseInterpolatedRaw(raw: *Str, pos: SourcePos): ExprNode {
    val end: Int = raw.size() - 1
    var pieces: List<Str> = List<Str>()
    var items: List<AstXmlNode> = List<AstXmlNode>()
    var piece: Str = Str()
    var hasPipe: Bool = false
    var i: Int = 1
    while (i < end) {
        val ch: Char = raw.charAt(i)
        if (ch == '@' && i + 1 < end && lexIsAlpha(raw.charAt(i + 1))) {
            var j: Int = i + 1
            while (j < end && lexIsAlphaOrDigit(raw.charAt(j))) {
                j = j + 1
            }
            pieces.append(piece)
            piece = Str()
            val name: Str = raw.substr(i + 1, j - i - 1)
            items.append(this.nameExprAt(name, pos).node)
            i = j
            continue
        }
        if (ch == '|') {
            hasPipe = true
        }
        piece.append(ch)
        i = i + 1
    }
    pieces.append(piece)
    if (!hasPipe) {
        val templateText: Str = interpJoin(pieces, '|')
        var args: List<AstXmlNode> = List<AstXmlNode>()
        args.append(this.interpTemplateNode(templateText, pos).node)
        this.interpAppendItems(args, items)
        return this.interpCallAt("fmtStr", args, pos)
    }
    val separator: Char = interpSeparator(pieces)
    val templateText: Str = interpJoin(pieces, separator)
    var args: List<AstXmlNode> = List<AstXmlNode>()
    args.append(this.interpCharLitAt(separator, pos).node)
    args.append(this.interpTemplateNode(templateText, pos).node)
    this.interpAppendItems(args, items)
    return this.interpCallAt("fmtStrWith", args, pos)
}

fun Parser.interpAppendItems(args: *List<AstXmlNode>, items: *List<AstXmlNode>): Unit {
    var i: Int = 0
    while (i < items.size()) {
        args.append(items[i])
        i = i + 1
    }
}

// The pieces joined with one byte: the template text before it is quoted below.
fun interpJoin(pieces: *List<Str>, separator: Char): Str {
    var out: Str = Str()
    var first: Bool = true
    for (*piece in pieces) {
        if (!first) {
            out.append(separator)
        }
        out.appendStrPtr(piece)
        first = false
    }
    return out
}

// The lowest candidate byte no piece holds. The candidates are printable ASCII with the
// quote, the apostrophe and the backslash left out - those would be spelled with an escape,
// in the template or in the char literal below, and the fusion takes both only in their plain
// spellings (`ilConcatSplittable`) - and the backtick last: a raw string cannot contain one
// (the scanner ends it at the first), so the search always terminates. `@` comes first,
// echoing the marker the source used; a piece without a `@` is the common case, so that is
// what the emitted call usually carries.
fun interpSeparator(pieces: *List<Str>): Char {
    val candidates: Str = "@!#$%&()*+,-./0123456789:;<=>?ABCDEFGHIJKLMNOPQRSTUVWXYZ[]^_abcdefghijklmnopqrstuvwxyz{|}~`"
    var i: Int = 0
    while (i < candidates.size()) {
        val c: Char = candidates.charAt(i)
        if (!interpPiecesHave(pieces, c)) {
            return c
        }
        i = i + 1
    }
    return '`'
}

fun interpPiecesHave(pieces: *List<Str>, c: Char): Bool {
    for (*piece in pieces) {
        var i: Int = 0
        while (i < piece.size()) {
            if (piece.charAt(i) == c) {
                return true
            }
            i = i + 1
        }
    }
    return false
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
// `fmtStr(...)` written by hand.
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
