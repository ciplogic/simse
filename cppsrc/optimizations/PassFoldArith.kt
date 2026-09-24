// PassFoldArith.kt
//
//   _sm_expr3 = 4 * 5;      ->   _sm_expr3 = 20;
//
// `Int` only - a `Float64` fold needs a spelling that parses back to the same bits. A division or
// remainder by 0 or -1, or a shift past 31, is left alone: those are undefined, not values.

package optimizations

import common
import linear

// The value a binary integer operation folds to, or empty when the fold is unsafe.
fun foldIntOp(op: *Str, a: Int, b: Int): Opt<Int> {
    when (op) {
        "+" -> {
            return Opt<Int>.some(a + b)
        }

        "-" -> {
            return Opt<Int>.some(a - b)
        }

        "*" -> {
            return Opt<Int>.some(a * b)
        }

        "/" -> {
            if (b == 0 || b == -1) {
                return Opt<Int>.none()
            }
            return Opt<Int>.some(a / b)
        }

        "%" -> {
            if (b == 0 || b == -1) {
                return Opt<Int>.none()
            }
            return Opt<Int>.some(a % b)
        }

        "&" -> {
            return Opt<Int>.some(a & b)
        }

        "|" -> {
            return Opt<Int>.some(a | b)
        }

        "^" -> {
            return Opt<Int>.some(a ^ b)
        }

        "<<" -> {
            if (b < 0 || b > 31) {
                return Opt<Int>.none()
            }
            return Opt<Int>.some(a << b)
        }

        ">>" -> {
            if (b < 0 || b > 31) {
                return Opt<Int>.none()
            }
            return Opt<Int>.some(a >> b)
        }
    }
    return Opt<Int>.none()
}

// An integer operation between two integer literals is the integer it computes.
fun foldArithRule(e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprBinary) {
        return e
    }
    val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
    if (!foldArithOp(op)) {
        return e
    }
    val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Lhs)
    val rhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Rhs)
    if (xmlIsEmpty(lhs) || xmlIsEmpty(rhs)) {
        return e
    }
    val a: Opt<Int> = foldIntValue(lhs)
    val b: Opt<Int> = foldIntValue(rhs)
    if (!a.hasValue() || !b.hasValue()) {
        return e
    }
    val value: Opt<Int> = foldIntOp(op, a.value(), b.value())
    if (!value.hasValue()) {
        return e
    }
    return foldIntLit(e, value.value())
}

fun linFoldArithBody(stmts: *List<AstXmlNode>): Bool {
    return foldExprsInList(stmts, foldArithRule)
}

// Self-registration (`Optimize.kt`).
val linFoldArithPass: Bool = registerLinOptPass("foldArith", linFoldArithBody)
