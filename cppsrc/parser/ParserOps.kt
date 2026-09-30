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
    return unquoteLiteral(text)
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

// The Pratt binding power of a binary operator: the shared precedence table
// (`common.opPrecedenceRank`) scaled by ten, so a right-recursive call at `bp + 1` lands
// inside its own level. Left-associative; -1 for anything that is not a binary operator.
fun binaryBindingPower(op: *Str): Int {
    val rank: Int = opPrecedenceRank(op)
    if (rank < 0) {
        return -1
    }
    return rank * 10
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
// A C++ keyword cannot be a Simse name: the amalgamation is C++, so the emitted identifier
// would not compile. The parser rejects one when it reads a name (`expectName`), which is
// where the position is known, rather than leaving it to a C++ error (guide4ai.md, "Gotchas").
fun isCppKeyword(text: *Str): Bool {
    when (text) {
        "alignas", "alignof", "and", "and_eq", "asm", "auto", "bitand", "bitor", "bool",
        "break", "case", "catch", "char", "char8_t", "char16_t", "char32_t", "class", "compl",
        "concept", "const", "consteval", "constexpr", "constinit", "const_cast", "continue",
        "co_await", "co_return", "co_yield", "decltype", "default", "delete", "do", "double",
        "dynamic_cast", "else", "enum", "explicit", "export", "extern", "false", "float", "for",
        "friend", "goto", "if", "inline", "int", "long", "mutable", "namespace", "new",
        "noexcept", "not", "not_eq", "nullptr", "operator", "or", "or_eq", "private",
        "protected", "public", "register", "reinterpret_cast", "requires", "return", "short",
        "signed", "sizeof", "static", "static_assert", "static_cast", "struct", "switch",
        "template", "this", "thread_local", "throw", "true", "try", "typedef", "typeid",
        "typename", "union", "unsigned", "using", "virtual", "void", "volatile", "wchar_t",
        "while", "xor", "xor_eq" -> {
            return true
        }
    }
    return false
}
