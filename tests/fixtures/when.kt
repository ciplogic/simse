package fixtures

// `when` (specs/functions.md): the language's selection statement. It is Kotlin's
// spelling of `switch`, desugared in the parser to the `if`/`else` chain it means -
// so these goldens show a chain of `If`s, and a label is an `==` operand rather than
// a `case` that has to be constant.
enum class Color {
    Red,
    Green,
    Blue = 7
}

fun name(c: Color): Str {
    when (c) {
        Color.Red -> {
            return "red"
        }

        Color.Green, Color.Blue -> {
            return "green or blue"
        }

        else -> {
            return "other"
        }
    }
    return "?"
}

// No `else`: nothing matches, and the statements after the `when` run. The subject is
// `a + b`, which the desugaring binds before it tests anything.
fun step(a: Int, b: Int): Int {
    var result: Int = 0
    when (a + b) {
        1 -> {
            result = 10
        }

        2, 3 -> {
            result = 20
        }
    }
    return result
}

fun labelled(text: Str): Int {
    when (text) {
        "a" -> {
            return 1
        }

        "b" -> {
            return 2
        }

        else -> {
            return 0
        }
    }
    return -1
}

// Several statements in an arm, and an arm that leaves the enclosing loop: a `break`
// in a `when` arm is the loop's, because the arms do not fall through and there is no
// switch to leave.
fun firstEven(limit: Int): Int {
    var i: Int = 0
    while (i < limit) {
        when (i % 2) {
            0 -> {
                return i
            }
        }
        i = i + 1
    }
    return -1
}
