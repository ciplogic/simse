// PassFoldArith.kt
//
// Constants, computed: `4 * 5` is `20`, so `_sm_expr3 = 4 * 5` never reaches the C++.
//
//   _sm_expr3 = 4 * 5;      ->   _sm_expr3 = 20;
//   _sm_expr9 = _sm_expr3;       _sm_expr9 = 20;
//
// The operands are read as literals *in the statement form*, which is where a literal
// rides as an operand: the expression lowering binds a nested expression to a temporary
// but leaves a literal alone (`ExpressionLowering.exprIsSimple`), so `4 * 5` is one
// `ExprBinary` with two literal children and not two slots by the time this runs. A run
// folds what a previous run exposed (`(2 + 3) * 4`), because the walk is bottom-up and the
// passes run to a fixpoint.
//
// **Only `Int`.** The float case is a separate question: `Float64` arithmetic folded here
// would have to produce a *literal spelling* that parses back to exactly the bits the C++
// would have computed, so a fold that is one ulp out is a program that prints a different
// number. Integers have no such question - `20` is `20`.
//
// The guards are about *not* folding what the language leaves undefined: a division or
// remainder by zero is unchecked in the language (the C++ traps), and folding it would
// hand the program a value where it used to crash; a shift past the width of an `Int` is
// undefined in C++ and means nothing here. A `-1` divisor is skipped with the zero one,
// because `Int.MinValue / -1` overflows.

package optimizations

import common
import linear

// The value a binary integer operation folds to, or empty when this pass will not fold it.
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

// One node: an integer operation between two integer literals is the integer it computes.
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
