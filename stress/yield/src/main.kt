package demo

// `yield` (impl_specs/yield.md): a body that yields is a state machine, and the
// function that builds it returns one on the stack. `..Int` in the signature says
// what the machine hands out; there is no other semantic to it - the compiler
// replaces every `yield` with a branch, a return and the label that resumes there.
//
//   val evens = everyOther(10)     // a machine, built by value
//   var step = evens.next()
//   while (step.hasValue()) {
//       println(step.value().toString())
//       step = evens.next()
//   }
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

// The same machine, advanced without copying an optional: `advance` writes through
// the caller's pointer and answers whether there was a value.
fun countdown(from: Int): ..Int {
    var value: Int = from
    while (value > 0) {
        yield value
        value = value - 1
    }
    // A `return` (or the end of the body) finishes the machine: `yield break`.
}

fun main(): Int {
    println("every other, up to 10:")
    val evens = everyOther(10)
    var step: Opt<Int> = evens.next()
    while (step.hasValue()) {
        println(step.value().toString())
        step = evens.next()
    }

    println("countdown from 3, through a pointer:")
    val down = countdown(3)
    var slot: Int = 0
    var more: Bool = down.advance(*slot)
    while (more) {
        println(slot.toString())
        more = down.advance(*slot)
    }

    // A machine can be advanced after it is finished; it stays finished.
    println("after the end:")
    println(down.next().hasValue().toString())

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
