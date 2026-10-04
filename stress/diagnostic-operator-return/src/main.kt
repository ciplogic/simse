package fixtures

// `compareTo`'s contract is the Kotlin one: the four comparisons derive from the Int it
// answers (`a < b` is `compareTo(a, b) < 0`), so a Bool is refused (specs/functions.md).

data class Version(var n: Int)

operator fun Version.compareTo(other: Version): Bool {
    return this.n == other.n
}

fun main(): Int {
    val a: Version = Version(1)
    val b: Version = Version(2)
    println((a < b).toString())
    return 0
}
