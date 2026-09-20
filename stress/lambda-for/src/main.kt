package fixtures

// T51: a `for` inside a lambda. A lambda body is a frame of its own - its parameters
// plus the values it captures - and it is *typed* like any other body, so the loop
// variable has a type (`value.toString()` is the integer spelling) and the `iter`
// wrap a `for` puts around what it iterates is the identity on a machine.
//
// Both forms are here on purpose: over a container, and over a machine the program
// built itself.

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

    // Over a container: the frame has to know `value` is an `Int`.
    val sumWith: Taker = (n: Int) -> {
        var total: Int = 0
        for (value in items) {
            total = total + value
        }
        println((total + n).toString())
    }
    sumWith(1)

    // Over a machine: `items.everyNth(2)` already *is* iterable, so the wrap is the
    // identity - and only the receiver's type can say so.
    val eachNth: Taker = (n: Int) -> {
        for (value in items.everyNth(2)) {
            println((value + n).toString())
        }
    }
    eachNth(1)

    // The indexed form, still inside the lambda.
    val indexed: Taker = (n: Int) -> {
        for ((value, index) in items.everyNth(2)) {
            println((index + n).toString() + ":" + value.toString())
        }
    }
    indexed(7)
    return 0
}
