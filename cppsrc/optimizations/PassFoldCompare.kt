// PassFoldCompare.kt
//
// A comparison of two constants is a constant: `3 > 2` is `true`, and `answer > 100` is
// `false` once `answer` is a constant. Written as a pass of its own rather than as another
// arm of `PassFoldArith`, because the two answer *different kinds* of value - an operation
// keeps the type of its operands, a comparison always answers a `Bool` - so `if (true)`
// below is the branch pass's input, not this one's.
//
//   _sm_expr5 = 3 > 2;      ->   _sm_expr5 = true;
//   ifFalse _sm_expr5 L2          ifFalse true L2      (and then `PassFoldBranch`)
//
// `Int` and `Bool` only, for the reason `PassFoldArith` gives: a `Float64` comparison can
// be folded exactly (`1.5 < 2.5` *is* true, whatever the spelling), but a comparison is
// only worth folding when its operands are, and the operands are the arithmetic's problem.
// A `Str` comparison would have to compare the *decoded* bytes of two source spellings
// (the escapes in `"\n"`), which is the string table's job and not a peephole's.

package optimizations

import common
import linear

// The `Bool` a comparison of two constants folds to, or empty when this pass will not fold
// it.
fun foldCompareValue(op: *Str, a: Int, b: Int): Opt<Bool> {
    when (op) {
        "==" -> {
            return Opt<Bool>.some(a == b)
        }

        "!=" -> {
            return Opt<Bool>.some(a != b)
        }

        "<" -> {
            return Opt<Bool>.some(a < b)
        }

        "<=" -> {
            return Opt<Bool>.some(a <= b)
        }

        ">" -> {
            return Opt<Bool>.some(a > b)
        }

        ">=" -> {
            return Opt<Bool>.some(a >= b)
        }
    }
    return Opt<Bool>.none()
}

fun foldCompareBool(op: *Str, a: Bool, b: Bool): Opt<Bool> {
    when (op) {
        "==" -> {
            return Opt<Bool>.some(a == b)
        }

        "!=" -> {
            return Opt<Bool>.some(a != b)
        }
    }
    return Opt<Bool>.none()
}

fun foldCompareRule(e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprBinary) {
        return e
    }
    val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
    if (!foldCompareOp(op)) {
        return e
    }
    val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Lhs)
    val rhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Rhs)
    if (xmlIsEmpty(lhs) || xmlIsEmpty(rhs)) {
        return e
    }
    if (foldKindOf(lhs) == FoldKind.Int && foldKindOf(rhs) == FoldKind.Int) {
        val a: Opt<Int> = foldIntValue(lhs)
        val b: Opt<Int> = foldIntValue(rhs)
        if (!a.hasValue() || !b.hasValue()) {
            return e
        }
        val value: Opt<Bool> = foldCompareValue(op, a.value(), b.value())
        if (!value.hasValue()) {
            return e
        }
        return foldBoolLit(e, value.value())
    }
    if (foldKindOf(lhs) == FoldKind.Bool && foldKindOf(rhs) == FoldKind.Bool) {
        val a: Bool = xmlAttr(lhs, AstNodeAttributeKind.Value) == "true"
        val b: Bool = xmlAttr(rhs, AstNodeAttributeKind.Value) == "true"
        val value: Opt<Bool> = foldCompareBool(op, a, b)
        if (!value.hasValue()) {
            return e
        }
        return foldBoolLit(e, value.value())
    }
    return e
}

fun linFoldCompareBody(stmts: *List<AstXmlNode>): Bool {
    return foldExprsInList(stmts, foldCompareRule)
}

val linFoldComparePass: Bool = registerLinOptPass("foldCompare", linFoldCompareBody)
