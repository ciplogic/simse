package fixtures

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

fun main(): Int {
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
