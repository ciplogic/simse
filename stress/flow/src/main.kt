package flow

// ---- bitwise ----
// The bitwise operators (`& | ^ << >>`) and their compound forms (`&= |= ^= <<= >>=`),
// specs/built-in-types.md, "Operators".
//
// Their precedence is Python's and Rust's - the bitwise pair binds *tighter* than a
// comparison, the shifts tighter still - so a bit test needs no parentheses and
// `flags & mask == 0` cannot silently mean `flags & (mask == 0)`. The shifts sit between
// `+` and `&`, so `1 << 2 + 1` is `1 << 3`.
//
// `>>` is one token (the shift), so the *type* parser splits it when it closes a nested
// type-argument list: this case declares a `List<List<Int>>` and a
// `Dictionary<Str, List<Int>>`, whose closers scan as `>>` and have to be taken one `>` at
// a time.

data class Bits(var flags: Int, var count: Int)

fun pack(low: Int, high: Int): Int {
    return (high << 4) | low
}

// An *operand* is a position the value/handle conversion applies to as well: `by` is a
// `*Int`, so `from + by` is `from + *by` (specs/memory-model.md) - the same rule that lets
// a parameter be a `*T` without every call spelling the `*`, read where the operation
// needs a value.
fun addThrough(from: Int, by: *Int): Int {
    return from + by
}

fun inner(rows: List<List<Int>>): Int {
    return rows[1][0]
}

fun named(tables: Dictionary<Str, List<Int>>): Int {
    return tables.get("a").value()[1]
}

fun partBitwise(): Int {
    // The operators.
    println((6 & 3).toString())
    println((6 | 1).toString())
    println((6 ^ 3).toString())
    println((1 << 5).toString())
    println((32 >> 2).toString())

    // Precedence: the shift binds tighter than `+` (so `1 << 2 + 1` is `1 << 3`), and the
    // bitwise pair binds tighter than `==` (so this test is on the mask, not on the
    // comparison).
    println((1 << 2 + 1).toString())
    println(((0 << 4) | 5).toString())
    println((6 & 3 == 3).toString())
    var flags: Int = 0
    flags = (1 << 2) | 1
    println((flags & 4 != 0).toString())

    // The compound forms: the place is located once, read, folded and written back
    // (specs/memory-model.md), exactly as the arithmetic ones are.
    var bits: Int = 1
    bits <<= 3
    println(bits.toString())
    bits >>= 1
    println(bits.toString())
    bits |= 8
    println(bits.toString())
    bits &= 12
    println(bits.toString())
    bits ^= 5
    println(bits.toString())

    // ... on a field, on an element, and through the pointer a `*T` parameter is.
    val b: Bits = Bits(1, 0)
    b.flags <<= 4
    println(b.flags.toString())
    b.flags |= 3
    println(b.flags.toString())
    val xs: List<Int> = listOf<Int>(1, 2, 3)
    xs[1] <<= 2
    println(xs[1].toString())
    xs[1] &= 6
    println(xs[1].toString())

    // A nested type-argument list: its closer is the `>>` the scanner makes of the shift
    // operator, and the parser takes one `>` out of it per list.
    val rows: List<List<Int>> = listOf<List<Int>>(listOf<Int>(1, 2), listOf<Int>(9, 8))
    println(inner(rows).toString())
    val tables: Dictionary<Str, List<Int>> = Dictionary<Str, List<Int>>()
    tables.insert("a", listOf<Int>(7, 11))
    println(named(tables).toString())

    // A handle as an operand: read through, exactly as a call argument is.
    var step: Int = 4
    println(addThrough(10, *step).toString())
    return 0
}

// ---- compound-assign ----
// Compound assignment (`+= -= *= /= %=`) and the step forms (`i++`, `i--`).
//
// The lowering locates the target's *place* once, reads its current value out of that
// same place, folds the operation into it, and writes the result back through it
// (specs/memory-model.md). One shape covers every target - a local slot, a
// closure's field, a member, an element, a file-level static and a `*T`'s pointee - so
// nothing is copied on the way and a write can never land in a copy of what the target
// names.
//
// A step is the compound assignment with a `1`: `i++` is `i += 1` and `i--` is `i -= 1`,
// written by the parser. Its value is the assignment's, so it stands on its own as a
// statement and the prefix form is a diagnostic (stress/diagnostic-prefix-step).

data class Counter(var hits: Int, var name: Str) {
    fun bump(by: Int): Unit {
        this.hits += by
    }
}

typealias Taker = (Int) -> Unit

var total: Int = 100
var calls: Int = 0

// The index of a compound assignment is evaluated once, however much it looks like a
// call: `xs[next()] += 100` reads and writes the same element.
fun next(): Int {
    calls++
    return 1
}

fun stepByThree(value: *Int): Unit {
    *value += 3
}

fun stepOnce(value: *Int): Unit {
    // A step applies to the *place* the `*` names - the pointee - and not to the pointer,
    // so this is `*value = *value + 1`, not C's `*(value++)`. (One `*`-statement per body
    // because a statement that *begins* with `*` after an expression statement reads as a
    // multiplication continuing that expression - a parse rule, not a lowering one.)
    *value++
}

fun partCompoundAssign(): Int {
    // A local slot: `i = i + 5`, one instruction.
    var i: Int = 1
    i += 5
    println(i.toString())
    i -= 2
    println(i.toString())
    i *= 3
    println(i.toString())
    i /= 2
    println(i.toString())
    i %= 4
    println(i.toString())

    // The step forms, whose `1` the parser writes.
    i++
    println(i.toString())
    i--
    i--
    println(i.toString())

    // A step is a statement, so it can be a loop body's whole statement.
    var n: Int = 0
    while (n < 3) {
        n++
    }
    println(n.toString())

    // A field: the receiver is located once, and the read and the write name the field on
    // it. `c` is a `val`, but its field is the place.
    val c: Counter = Counter(10, "c")
    c.hits += 5
    println(c.hits.toString())
    c.bump(2)
    println(c.hits.toString())
    c.hits++
    println(c.hits.toString())

    // An element: the list and the index are each evaluated once, into the operands the
    // read and the write share.
    val xs: List<Int> = listOf<Int>(1, 2, 3)
    xs[1] += 10
    println(xs[1].toString())
    xs[2]--
    println(xs[2].toString())
    var at: Int = 0
    xs[at] -= 1
    println(xs[0].toString())

    // The place is located once: `next()` runs once, not once per half of the update.
    xs[next()] += 100
    println(calls.toString())
    println(xs[1].toString())

    // A field of an element: the element's own address is the reference the read and the
    // write go through.
    val counters: List<Counter> = listOf(Counter(1, "a"), Counter(2, "b"))
    counters[0].hits += 5
    println(counters[0].hits.toString())
    counters[1].hits++
    println(counters[1].hits.toString())

    // A file-level static.
    total += 1
    println(total.toString())
    total--
    println(total.toString())

    // A pointee, written through the `*T` the callees were handed.
    var value: Int = 7
    stepByThree(*value)
    stepOnce(*value)
    println(value.toString())

    // A captured variable: the closure's field is the place, so a step inside the lambda
    // reaches the variable it captured. A capture is a *copy* (no reference captures yet,
    // impl_specs/user-language-roadmap.md), so the field the lambda steps is its own - read
    // inside the lambda the steps show, outside it the variable is untouched.
    var captured: Int = 1
    val addSome: Taker = (by: Int) -> {
        captured += by
        captured++
        println(captured.toString())
    }
    addSome(4)
    println(captured.toString())
    return 0
}

// ---- control-flow ----
// Nested loops with break/continue, an if/else chain, a `when` and a loop whose
// condition is a literal: the shapes the linear lowering has to break into
// labels, gotos and conditional jumps.

fun classify(n: Int): Str {
    when (n) {
        0 -> {
            return "zero"
        }

        1 -> {
            return "one"
        }

        2 -> {
            return "two"
        }

        else -> {
            return "many"
        }
    }
}

fun partControlFlow(): Int {
    var total: Int = 0
    var row: Int = 0
    while (row < 4) {
        var col: Int = 0
        while (col < 4) {
            col = col + 1
            if (col == 2) {
                continue
            }
            if (row == 3) {
                break
            }
            total = total + 1
        }
        row = row + 1
    }
    println(total)

    println(classify(0))
    println(classify(1))
    println(classify(2))
    println(classify(9))

    var i: Int = 0
    while (i < 10) {
        if (i > 2 && i < 5) {
            println("mid " + i.toString())
        }
        i = i + 1
    }

    var spins: Int = 0
    while (true) {
        spins = spins + 1
        if (spins >= 3) {
            break
        }
    }
    println(spins)
    return 0
}

// ---- recursion ----
// Recursion (including a call in tail position and one in both operands), a
// pointer parameter that borrows the caller's list, list sorting, and `Span<T>`
// iteration (there is no range-for).

fun fib(n: Int): Int {
    if (n < 2) {
        return n
    }
    return fib(n - 1) + fib(n - 2)
}

fun gcd(a: Int, b: Int): Int {
    if (b == 0) {
        return a
    }
    return gcd(b, a % b)
}

fun sum(values: *List<Int>): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < values.size()) {
        total = total + values[i]
        i = i + 1
    }
    return total
}

fun partRecursion(): Int {
    println(fib(20))
    println(gcd(1071, 462))

    var values: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 8) {
        values.append((i * 7) % 5)
        i = i + 1
    }
    println(sum(*values))

    values.sort((left: Int, right: Int) -> left < right)
    println(sum(*values))

    var span: Span<Int> = spanOf(*values)
    var count: Int = 0
    while (!span.isEmpty()) {
        count = count + 1
        span = span.slice(1)
    }
    println(count)
    return 0
}

// ---- when ----
// `when` (specs/functions.md): the selection statement, desugared to an `if`/`else`
// chain in the parser. This program pins the behavior the desugaring has to keep:
// the subject is evaluated once, a group of labels shares one body, an arm's
// `break`/`continue` is the enclosing loop's, and a `when` with no `else` just falls
// through to what follows it.

enum class Size {
    Small,
    Big
}

var callsWhen: Int = 0

fun tick(): Int {
    callsWhen = callsWhen + 1
    return callsWhen
}

fun classifyWhen(n: Int): Str {
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

fun partWhen(): Int {
    // The subject is evaluated once, however many labels are tested.
    when (tick()) {
        1 -> {
            println("first")
        }

        else -> {
            println("nope")
        }
    }
    println(callsWhen)

    println(classifyWhen(0))
    println(classifyWhen(1))
    println(classifyWhen(2))
    println(classifyWhen(3))
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

// ---- the category's entry ----
fun main(): Int {
    partBitwise()
    partCompoundAssign()
    partControlFlow()
    partRecursion()
    partWhen()
    return 0
}
