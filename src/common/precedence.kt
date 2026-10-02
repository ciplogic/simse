// precedence.kt
//
// The one table of binary-operator precedence. The parser's Pratt binding power
// (`binaryBindingPower`) and the emitter's parenthesisation (`cgPrecedence`) both read it,
// so the two cannot drift - they used to be two copies of the same order.

package common

// The relative precedence of a binary operator, 1 (loosest) to 10 (tightest), or -1 when
// `op` is not a binary operator. The order is the language's (specs/built-in-types.md):
// bitwise binds tighter than a comparison but looser than a shift, as in Python and Rust,
// so `a & b == c` is `(a & b) == c`; C's opposite order silently means `a & (b == c)`.
fun opPrecedenceRank(op: *Str): Int {
    when (op) {
        "||" -> {
            return 1
        }

        "&&" -> {
            return 2
        }

        "==", "!=" -> {
            return 3
        }

        "<", ">", "<=", ">=" -> {
            return 4
        }

        "|" -> {
            return 5
        }

        "^" -> {
            return 6
        }

        "&" -> {
            return 7
        }

        "<<", ">>" -> {
            return 8
        }

        "+", "-" -> {
            return 9
        }

        "*", "/", "%" -> {
            return 10
        }
    }
    return -1
}
