package fixtures

// `operator` marks the indexer functions, and only the names with a lowering are operators
// (specs/functions.md). `plus` is Kotlin's spelling for `+`, but `+` does not resolve a
// declaration here yet, so the name is refused rather than silently ignored.

data class Counter(var n: Int)

operator fun Counter.plus(other: Counter): Counter {
    return Counter(this.n + other.n)
}

fun main(): Int {
    val a: Counter = Counter(1)
    val b: Counter = Counter(2)
    println((a + b).n.toString())
    return 0
}
