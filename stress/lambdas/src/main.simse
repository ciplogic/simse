package fixtures
// T18: lambdas assigned to callable values and passed as arguments.

typealias Mapper = (Int) -> Int
typealias Predicate = (Int) -> Bool

fun apply(f: Mapper, value: Int): Int {
    return f(value)
}

fun countIf(items: *List<Int>, predicate: Predicate): Int {
    var count: Int = 0
    var c: Span<Int> = spanOf(items)
    while (!c.isEmpty()) {
        if (predicate(c[0])) {
            count = count + 1
        }
        c = c.slice(1)
    }
    return count
}

fun makeAdder(factor: Int): Mapper {
    return (v: Int) -> v + factor
}

fun main(): Int {
    val doubler: Mapper = (v: Int) -> v * 2
    println(doubler(21))
    println(apply(doubler, 5))

    // A lambda passed directly as an argument.
    println(apply((v: Int) -> v + 1, 41))

    // A closure capturing a local by value.
    val add10: Mapper = makeAdder(10)
    println(add10(5))

    var items: List<Int> = List<Int>()
    items.append(1)
    items.append(2)
    items.append(3)
    items.append(4)

    val isEven: Predicate = (v: Int) -> v % 2 == 0
    println(countIf(*items, isEven))
    println(countIf(*items, (v: Int) -> v > 2))

    // A block-bodied lambda.
    val big: Mapper = (v: Int) -> {
        if (v > 0) {
            return v * 100
        }
        return 0
    }
    println(big(3))

    return 0
}
