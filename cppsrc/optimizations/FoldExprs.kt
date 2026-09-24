// FoldExprs.kt
//
// The shared half of the constant-folding passes (`PassFold*.kt`): what a literal is, how
// one is built, and the one walk that offers every expression of a body to a rule.
//
// A **rule** is a named function `(*AstXmlNode) -> AstXmlNode` that answers the node it
// was given when it has nothing to fold, and a different *kind* of node when it has. That
// "the kind changed" test is the whole of the change protocol: every fold below turns an
// operation into a literal (or a branch into a jump), so a rule that keeps the kind has
// folded nothing, and a rule that changes it has - which is how a pass reports honestly
// without comparing two nodes (a structural `==` over an AST is a deep walk).
//
// The walk is **bottom-up**: a node's children are rewritten first, so a rule sees a node
// whose operands are already folded (`(2 + 3) * 4` folds the `2 + 3` on the way down and
// the rule then sees a literal it can multiply). It is also *in place*: a rewritten child
// replaces the child it stood as, in the statement list the pass was handed - which is
// what `Optimize.kt` requires, since the pipeline keeps the list it passed.
//
// Two things the walk deliberately does not enter: a **lambda** (its body is lowered as a
// body of its own, `LinearForm.ilLambdaLower`, so folding it here would fold a body the
// lowering has not seen yet) and a **type** node (nothing in a type is an expression).

package optimizations

import common
import linear

// ---- literals --------------------------------------------------------------

// The five literal kinds a fold can produce or read, as one tag. `Fold.None` (0) is what
// a node that is not a literal answers.
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

// The literal's own text: `Text` for the numeric, char and string literals (a string
// literal carries its quotes, a char literal its apostrophes), `Value` for a `Bool`
// (`true`/`false`). Empty for anything that is not a literal.
fun foldLiteralText(e: *AstXmlNode): Str {
    if (foldKindOf(e) == FoldKind.Bool) {
        return xmlAttr(e, AstNodeAttributeKind.Value)
    }
    if (foldKindOf(e) != FoldKind.None) {
        return xmlAttr(e, AstNodeAttributeKind.Text)
    }
    return ""
}

// The position attributes, which every built node keeps from the node it replaces: a
// source comment and a diagnostic still point at the line the writer wrote.
fun foldPosAttrs(like: *AstXmlNode): List<AstNodeAttribute> {
    return listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(like).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(like).toString())
    )
}

// `like`, as a literal of another kind: the role (`name`) is kept - it is the position the
// emitter reads the node from, `Init`/`Value`/`Cond`/`Lhs` - and the kind, attributes and
// children are the literal's.
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

// A string literal carries its own quotes: the emitter reads `Text` back as the *source
// spelling* (`CgStringTable.cgLiteralByteLength` counts the escapes), so a fold has to
// build `"..."` and not the bare text.
fun foldStrLit(like: *AstXmlNode, text: Str): AstXmlNode {
    return foldAsLiteral(like, AstNodeCategory.ExprStrLit, fmtStr("\"|\"", text))
}

// The integer a literal holds, or empty: a literal the language wrote as a number is
// decimal digits, and one with anything else in it (a run the parser left alone) folds
// nothing.
fun foldIntValue(e: *AstXmlNode): Opt<Int> {
    if (foldKindOf(e) != FoldKind.Int) {
        return Opt<Int>.none()
    }
    return xmlAttr(e, AstNodeAttributeKind.Text).toInt()
}

// Whether a comparison or arithmetic operator is one of the simple ones. `&&`/`||` are
// *not*: the lowering turns a short-circuit into branches before any of these passes see
// it, so an `ExprBinary` carrying one is not the shape this walk is for.
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

// Whether the walk rewrote anything. A field of a node the pass hands around, so the
// recursion can report without returning a pair.
data class FoldState(
    var changed: Bool
)

// Every expression under `node`, through `rule`. Answers the node that stands in `node`'s
// place: the node itself when nothing under it folded, a rebuilt node when a child did,
// and the rule's own answer when the rule folds the node itself.
fun foldExprsUnder(node: *AstXmlNode, rule: (*AstXmlNode) -> AstXmlNode, state: *FoldState): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(node)
    // **A fold produces a value, so it may not stand in a place.** Three positions in a
    // linear body are places rather than values, and the walk does not enter any of them:
    // a `Target` (where a statement writes), and the operand of a `&`/`*` (which is the
    // storage itself - `stepByThree(*value)` over a folded `value` would write to a
    // temporary, which is the bug `stress/compound-assign` caught).
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
    // A child that folds is a rebuild of its parent: `exprLike` copies the attributes and
    // the role, so the parent keeps the position the emitter reads it from. The flag is
    // read *before* the children so a fold deeper down is what says "rebuild me".
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

// One statement list, every expression through `rule`; a statement whose rewritten form is
// a different node replaces the one it stood as, in place.
fun foldExprsInList(stmts: *List<AstXmlNode>, rule: (*AstXmlNode) -> AstXmlNode): Bool {
    var state: FoldState = FoldState(false)
    var i: Int = 0
    while (i < stmts.size()) {
        val before: Bool = state.changed
        val stmt: AstXmlNode = foldExprsUnder(*stmts[i], rule, *state)
        // A statement whose *own* kind did not move is still a different node when
        // something under it folded (the rebuilt children), so the write-back test is the
        // flag and not the kind.
        if (state.changed != before) {
            stmts[i] = stmt
        }
        i = i + 1
    }
    return state.changed
}
