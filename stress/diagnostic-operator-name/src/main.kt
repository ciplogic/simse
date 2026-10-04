package fixtures

// `operator` marks the functions the language's syntax resolves: the indexers and the
// Kotlin operators for the comparisons, `==`/`!=` and `+` (specs/functions.md). `times` is
// Kotlin's spelling for `*`, and `*` does not resolve a declaration, so the name is
// refused rather than silently ignored.

data class Counter(var n: Int)

operator fun Counter.times(other: Counter): Counter {
    return Counter(this.n * other.n)
}

fun main(): Int {
    val a: Counter = Counter(1)
    val b: Counter = Counter(2)
    println((a * b).n.toString())
    return 0
}
