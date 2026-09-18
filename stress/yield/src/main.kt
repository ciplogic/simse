package demo

// `yield` (impl_specs/yield.md): a body that yields is a state machine, and the
// function that builds it returns one on the stack. `..Int` in the signature says
// what the machine hands out; there is no other semantic to it - the compiler
// replaces every `yield` with a branch, a return and the label that resumes there.
//
//   val evens = everyOther(10)     // a machine, built by value
//   while (evens.advance()) {
//       println(evens.value().toString())
//   }
//
// `advance()` steps the machine, leaves what it yielded in its `current` field and
// answers whether there was a value; `value()` reads that field out, so nothing is
// built per element - no `Opt` to construct and unwrap.
//
// A machine is also what `for` iterates (specs/functions.md): `for (v in m)` and
// `for ((v, i) in m)` are the `while` above with the advance as their first
// statement, so `continue` still moves the machine on and still counts.

// A function that yields, with a `while` loop around the yield: the loop becomes
// labels and gotos first, and the yield becomes the resumption point inside them.
fun everyOther(n: Int): ..Int {
    var i: Int = 0
    while (i < n) {
        if (i % 2 == 0) {
            yield i
        }
        i = i + 1
    }
}

// The same machine, advanced by hand: `advance()` takes no argument and the yielded
// value stays in the machine until `value()` is asked for it. The local is called
// `value`, which is also the protocol's method name: the field is emitted under a
// mangled one (`linear::yieldFieldName`), so a program's own names never alias it.
fun countdown(from: Int): ..Int {
    var value: Int = from
    while (value > 0) {
        yield value
        value = value - 1
    }
    // A `return` (or the end of the body) finishes the machine: `yield break`.
}

// Every name the machine itself uses, taken by the body: `branch` and `current` (the
// lowering's fields), `advance` and `value` (the methods). Each becomes `_sm_f_<name>`.
fun shadowing(base: Int): ..Int {
    var current: Int = base
    var advance: Int = 1
    var branch: Int = 2
    var value: Int = 3
    while (value > 0) {
        yield current + advance + branch + value
        value = value - 1
    }
}

fun main(): Int {
    println("every other, up to 10:")
    val evens = everyOther(10)
    while (evens.advance()) {
        println(evens.value().toString())
    }

    println("countdown from 3:")
    val down = countdown(3)
    while (down.advance()) {
        println(down.value().toString())
    }

    // A machine can be advanced after it is finished; it stays finished.
    println("after the end:")
    println(down.advance().toString())

    // A body's own names are its fields even when they are the protocol's own.
    println("shadowing the protocol:")
    for (total in shadowing(10)) {
        println(total.toString())
    }

    // `for` over a machine: the plain form. The loop variable is a fresh `val`
    // per iteration, and the machine is created once, before the loop.
    println("with for:")
    for (value in everyOther(10)) {
        println(value.toString())
    }

    // The second form, with the index the compiler counts for you (from 0).
    println("with for and an index:")
    for ((value, index) in everyOther(10)) {
        println(index.toString() + ":" + value.toString())
    }

    // `continue` is the loop's own: because the advance and the index are the first
    // thing in the body, a skipped iteration still moves the machine on and still
    // counts (note the jump from 1 to 3 in the index). `break` leaves the loop.
    println("skip 4, stop after 8:")
    for ((value, index) in everyOther(10)) {
        if (value == 4) {
            continue
        }
        if (value > 8) {
            break
        }
        println(index.toString() + "=" + value.toString())
    }
    return 0
}
