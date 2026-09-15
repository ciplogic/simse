package whencase

// `when` (specs/functions.md): the selection statement, desugared to an `if`/`else`
// chain in the parser. This program pins the behavior the desugaring has to keep:
// the subject is evaluated once, a group of labels shares one body, an arm's
// `break`/`continue` is the enclosing loop's, and a `when` with no `else` just falls
// through to what follows it.

enum Size {
    Small,
    Big
}

var calls: Int = 0

fun tick(): Int {
    calls = calls + 1
    return calls
}

fun classify(n: Int): Str {
    when (n) {
        0 -> {
            return "zero"
        }

        1, 2 -> {
            return "one or two"
        }

        else -> {
            return "many"
        }
    }
}

fun describe(s: Size): Str {
    when (s) {
        Size.Small -> {
            return "small"
        }

        else -> {
            return "big"
        }
    }
}

fun main(): Int {
    // The subject is evaluated once, however many labels are tested.
    when (tick()) {
        1 -> {
            println("first")
        }

        else -> {
            println("nope")
        }
    }
    println(calls)

    println(classify(0))
    println(classify(1))
    println(classify(2))
    println(classify(3))
    println(describe(Size.Big))

    // No `else`: nothing matches, and the code after the `when` runs.
    var seen: Int = 0
    when (7) {
        1 -> {
            seen = 1
        }

        2, 3 -> {
            seen = 2
        }
    }
    println(seen)

    // An arm's `break`/`continue` is the enclosing loop's: there is no switch to
    // leave, and the arms do not fall through.
    var i: Int = 0
    var evens: Int = 0
    while (i < 10) {
        i = i + 1
        when (i) {
            4 -> {
                evens = evens + 1
                continue
            }

            7 -> {
                break
            }
        }
        evens = evens + 1
    }
    println(evens)
    println(i)

    // A `when` in an arm's body, and a string subject.
    var word: Str = "a"
    when (word) {
        "a" -> {
            when (i) {
                7 -> {
                    println("a7")
                }

                else -> {
                    println("an")
                }
            }
        }

        "b" -> {
            println("bee")
        }

        else -> {
            println("other")
        }
    }
    return 0
}
