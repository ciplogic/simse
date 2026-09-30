// ParserOps.kt
//
// The parser's small free helpers: the attribute-argument spelling, the operator
// predicates and binding powers the Pratt loop reads, and the `when`-lowering switches.
// They need nothing from `Parser`'s state, so they live outside the class and outside
// Parser.kt.

package parser

import common

// The `@SmGen` arguments after the first (the generator name), each unquoted and joined
// by a comma as they are recorded (specs/attributes.md). An empty list (or one with only
// the generator name) is "".
fun generatorArgsText(args: *List<Str>): Str {
    var parts: List<Str> = List<Str>()
    var i: Int = 1
    while (i < args.size()) {
        parts.append(attrLiteralText(args[i]))
        i = i + 1
    }
    return joinStrs(parts, ",")
}

// A string literal without its quotes, or an integer literal as written (specs/attributes.md).
fun attrLiteralText(text: Str): Str {
    if (text.size() >= 2 && text[0] == '\"' && text[text.size() - 1] == '\"') {
        return text.substr(1, text.size() - 2)
    }
    return text
}

// `data`: a pure function - no side effects, the result a function of `value` - so the
// reuse pass may merge two `boolText(x)` calls with the same unchanged `x`
// (`linear/ReusePure.kt`).
data fun boolText(value: Bool): Str {
    if (value) {
        return "true"
    }
    return "false"
}

// Pratt binding powers; left-associative (the recursive call uses bp + 1).
fun binaryBindingPower(op: *Str): Int {
    when (op) {
        "||" -> {
            return 10
        }

        "&&" -> {
            return 20
        }

        "==", "!=" -> {
            return 30
        }

        "<", ">", "<=", ">=" -> {
            return 40
        }

        // Bitwise sit between comparison and shift, as in Python and Rust, so `a & b == c` is
        // `(a & b) == c`; C's opposite order silently means `a & (b == c)`.
        "|" -> {
            return 43
        }

        "^" -> {
            return 44
        }

        "&" -> {
            return 45
        }

        "<<", ">>" -> {
            return 47
        }

        "+", "-" -> {
            return 50
        }

        "*", "/", "%" -> {
            return 60
        }
    }
    return -1
}

fun isAssignOp(op: *Str): Bool {
    when (op) {
        "=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=" -> {
            return true
        }
    }
    return false
}

fun isStepOp(op: *Str): Bool {
    return op == "++" || op == "--"
}

fun stepAssignOp(op: *Str): Str {
    if (op == "++") {
        return "+="
    }
    return "-="
}

// `--no-when-dispatch`: the `when`-over-strings lowering above (a label's test guarded by the
// subject's length, and by its first byte when that one is printable). **On by default**: the
// rewrite only makes a test cheaper, so it cannot change which arm matches, and the switch is
// for the A/B and for an escape hatch. Off also turns off `--when-first-char`.
var whenDispatchFlag: Bool = true

// `--when-first-char`: guard a label of two or more bytes by the subject's first byte as well.
// The assumption this exists to validate: the extra `Char` load pays for itself by rejecting a
// same-length label before the `memcmp`. Off by default - the length guard alone is the one
// that cannot lose.
var whenFirstCharFlag: Bool = false

// `--when-copy-subject`: force the template even for a place subject, so the copy it costs can
// be measured against reading the place again. The A/B for the copy, not a mode to ship.
var whenCopySubjectFlag: Bool = false

fun whenDispatch(): Bool {
    return whenDispatchFlag
}

fun whenCopySubject(): Bool {
    return whenCopySubjectFlag
}

fun setWhenCopySubject(value: Bool): Unit {
    whenCopySubjectFlag = value
}

fun whenFirstChar(): Bool {
    return whenFirstCharFlag
}

fun setWhenDispatch(value: Bool): Unit {
    whenDispatchFlag = value
}

fun setWhenFirstChar(value: Bool): Unit {
    whenFirstCharFlag = value
}
