// PassFoldCompare.kt
//
//   _sm_expr5 = 3 > 2;      ->   _sm_expr5 = true;
//
// A constant comparison folds to a `Bool` - its own pass, since an operation keeps its operands'
// type. `Int` and `Bool` only; a `Str` comparison would have to compare decoded bytes.

package optimizations

import common
import linear

// The `Bool` a comparison of two constants folds to, or empty.
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
