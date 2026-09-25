package whenstrings

// The `when`-over-strings lowering (cppsrc/parser/Parser.kt): a `when` whose subject is a string
// and whose arms are all string literals has each label test guarded by the subject's length
// (and, with `--when-first-char`, by its first byte too). The arms, their order, their bodies and
// the `else` are untouched, so what matches cannot change. The shapes pinned here: the empty
// text, one-byte labels, an arm whose labels span two lengths (`<` with `<=`), a label that can
// never match, a `StrView` subject, and an arm that is a constant rather than a literal - that
// last one leaves the whole `when` with the plain comparison it always had.

fun classify(op: Str): Str {
    when (op) {
        "" -> {
            return "empty"
        }

        "|", "^", "&" -> {
            return "bitwise"
        }

        "==", "!=" -> {
            return "equality"
        }

        "<", ">", "<=", ">=" -> {
            return "relational"
        }

        "<<" -> {
            return "shift"
        }

        else -> {
            return "other"
        }
    }
}

// The same guards, read through a view.
fun viewKind(v: StrView): Str {
    when (v) {
        "a", "b" -> {
            return "letter"
        }

        "ab" -> {
            return "pair"
        }

        else -> {
            return "?"
        }
    }
}

// An arm that is a constant, not a literal.
fun fixed(op: Str): Str {
    val head: Str = "head"
    when (op) {
        head -> {
            return "HEAD"
        }

        else -> {
            return "other"
        }
    }
}

fun main(): Int {
    val ops: List<Str> = listOf<Str>("", "|", "^", "&", "==", "!=", "<", ">", "<=", ">=", "<<", "zz", "||")
    for (op in ops) {
        println(op + " -> " + classify(op))
    }
    println(viewKind("a") + " " + viewKind("ab") + " " + viewKind("abc"))
    println(fixed("head") + " " + fixed("other"))
    return 0
}
