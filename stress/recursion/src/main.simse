package recur

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

fun main(): Int {
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
