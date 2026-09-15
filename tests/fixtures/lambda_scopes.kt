package fixtures

// Two things this fixture pins, both of which the two rings disagreed about silently
// (the corpus compiled and ran the same program; only the emitted *names* differed).
//
//   - A lambda body is *typed* like any other body, so a `for` inside one has a typed
//     loop variable, and the `smToYield` wrap a `for` puts around what it iterates is
//     the identity when that is already a machine (impl_specs/for.md).
//   - A lambda is a body of its own for the *rename* pass too: two lambdas in one body
//     may each declare a local of the same name, and both keep it. The enclosing body's
//     `used` set is not the lambda's - the same rule `rewriteUses(..., false)` states in
//     the C++ ring.
//
// The second shape is why the fixture exists: with the enclosing pass naming a lambda's
// declarations, the *second* lambda's `value` came out renamed (`_sm_value_2`) in one
// ring and untouched in the other. A case with the name reused across two lambdas is
// the only thing that shows it, and it is cheap enough to keep.

typealias Taker = (Int) -> Unit

fun List<Int>.everyNth(step: Int): ..Int {
    var i: Int = 0
    while (i < this.size()) {
        yield this[i]
        i = i + step
    }
}

fun main(): Int {
    val items: List<Int> = List<Int>()
    items.append(10)
    items.append(20)
    items.append(30)

    // The same local name in two lambdas, over a container.
    val first: Taker = (n: Int) -> {
        for (value in items) {
            println((value + n).toString())
        }
    }
    first(1)

    // ... and over a machine, which is the wrap that has to be the identity.
    val second: Taker = (n: Int) -> {
        for (value in items.everyNth(2)) {
            println((value - n).toString())
        }
    }
    second(1)

    // The indexed form, still inside a lambda.
    val third: Taker = (n: Int) -> {
        for ((value, index) in items.everyNth(2)) {
            println((index + n).toString() + ":" + value.toString())
        }
    }
    third(7)
    return 0
}
