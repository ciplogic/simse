// FoldExprs.kt
//
// The shared half of the `PassFold*` passes: what a literal is, how one is built, and the walk
// that offers a body's expressions to a rule. A rule answers its input unchanged when it folded
// nothing, a different *kind* when it did - that kind test is the change protocol, so it reports
// honestly without a deep `==`. Bottom-up, in place; a lambda and a type node are not entered.

package optimizations

import common
import linear

// ---- literals --------------------------------------------------------------

// The literal kinds a fold can produce or read; `None` is not a literal.
enum class FoldKind {
    None,
    Int,
    Float,
    Bool,
    Char,
    Str
}

fun foldKindOf(e: *AstXmlNode): FoldKind {
    val kind: AstNodeCategory = xmlKind(e)
    when (kind) {
        AstNodeCategory.ExprIntLit -> {
            return FoldKind.Int
        }

        AstNodeCategory.ExprFloatLit -> {
            return FoldKind.Float
        }

        AstNodeCategory.ExprBoolLit -> {
            return FoldKind.Bool
        }

        AstNodeCategory.ExprCharLit -> {
            return FoldKind.Char
        }

        AstNodeCategory.ExprStrLit -> {
            return FoldKind.Str
        }
    }
    return FoldKind.None
}

// The literal's own text: `Text` (a string keeps its quotes, a char its apostrophes), or
// `Value` for a `Bool`.
fun foldLiteralText(e: *AstXmlNode): Str {
    if (foldKindOf(e) == FoldKind.Bool) {
        return xmlAttr(e, AstNodeAttributeKind.Value)
    }
    if (foldKindOf(e) != FoldKind.None) {
        return xmlAttr(e, AstNodeAttributeKind.Text)
    }
    return ""
}

// The position attributes a built node keeps from the node it replaces, so diagnostics still
// point at the source.
fun foldPosAttrs(like: *AstXmlNode): List<AstNodeAttribute> {
    return listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(like).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(like).toString())
    )
}

// `like` as a literal of another kind: the role (`name`) is kept - it is the position the
// emitter reads from.
fun foldAsLiteral(like: *AstXmlNode, kind: AstNodeCategory, text: Str): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(like.name, kind, foldPosAttrs(like), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
    return node
}

fun foldIntLit(like: *AstXmlNode, value: Int): AstXmlNode {
    return foldAsLiteral(like, AstNodeCategory.ExprIntLit, value.toString())
}

fun foldBoolLit(like: *AstXmlNode, value: Bool): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(like.name, AstNodeCategory.ExprBoolLit, foldPosAttrs(like), Array<AstXmlNode>())
    var text: Str = "false"
    if (value) {
        text = "true"
    }
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Value, text))
    return node
}

// A string literal carries its own quotes, because the emitter reads `Text` back as source
// spelling (`CgStringTable.cgLiteralByteLength`), so a fold builds `"..."`, not bare text.
fun foldStrLit(like: *AstXmlNode, text: Str): AstXmlNode {
    return foldAsLiteral(like, AstNodeCategory.ExprStrLit, fmtStr("\"|\"", text))
}

// The integer a literal holds, or empty (a literal with anything but decimal digits folds
// nothing).
fun foldIntValue(e: *AstXmlNode): Opt<Int> {
    if (foldKindOf(e) != FoldKind.Int) {
        return Opt<Int>.none()
    }
    return xmlAttr(e, AstNodeAttributeKind.Text).toInt()
}

// Whether an operator is one of the simple arithmetic ones; `&&`/`||` are not, since the lowering
// turns a short-circuit into branches before these passes see it.
fun foldArithOp(op: *Str): Bool {
    when (op) {
        "+", "-", "*", "/", "%", "&", "|", "^", "<<", ">>" -> {
            return true
        }
    }
    return false
}

fun foldCompareOp(op: *Str): Bool {
    when (op) {
        "==", "!=", "<", "<=", ">", ">=" -> {
            return true
        }
    }
    return false
}

// ---- the walk --------------------------------------------------------------

// Whether the walk rewrote anything; a field so the recursion can report without a pair.
data class FoldState(
    var changed: Bool
)

// Every expression under `node` through `rule`; answers the node that stands in its place.
fun foldExprsUnder(node: *AstXmlNode, rule: (*AstXmlNode) -> AstXmlNode, state: *FoldState): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(node)
    // A fold produces a value, so it may not stand in a place: the walk does not enter a `Target`
    // or the operand of `&`/`*` (the storage - folding it would write through a temporary).
    if (node.name == AstNodeKind.Target || kind == AstNodeCategory.ExprRef
        || kind == AstNodeCategory.ExprDeref
    ) {
        return node
    }
    if (kind == AstNodeCategory.ExprLambda || kind == AstNodeCategory.TypeNamed
        || kind == AstNodeCategory.TypeGeneric || kind == AstNodeCategory.TypePointer
        || kind == AstNodeCategory.TypeReference
    ) {
        return node
    }
    // A folded child rebuilds its parent; the flag is read before the children, so a fold deeper
    // down is what says "rebuild me".
    val before: Bool = state.changed
    var kids: List<AstXmlNode> = node.Children.toList()
    var i: Int = 0
    while (i < kids.size()) {
        kids[i] = foldExprsUnder(*kids[i], rule, state)
        i = i + 1
    }
    var here: AstXmlNode = node
    if (state.changed != before) {
        here = exprLike(node, kids)
    }
    val after: AstXmlNode = rule(*here)
    if (xmlKind(after) != xmlKind(here)) {
        state.changed = true
        return after
    }
    return here
}

// One statement list, every expression through `rule`, written back in place.
fun foldExprsInList(stmts: *List<AstXmlNode>, rule: (*AstXmlNode) -> AstXmlNode): Bool {
    var state: FoldState = FoldState(false)
    var i: Int = 0
    while (i < stmts.size()) {
        val before: Bool = state.changed
        val stmt: AstXmlNode = foldExprsUnder(*stmts[i], rule, *state)
        // A statement whose own kind did not move is still a different node when a child folded,
        // so the write-back test is the flag and not the kind.
        if (state.changed != before) {
            stmts[i] = stmt
        }
        i = i + 1
    }
    return state.changed
}
