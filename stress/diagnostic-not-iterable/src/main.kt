package forloop

// A `for` iterates whatever has a `iter`: the prelude gives every container one,
// and a machine (`..T`) is its own identity, so `for (x in list)` and `for (x in m)`
// both work. Something with *neither* is rejected at the `for`, instead of emitting C++
// that does not compile (specs/functions.md, impl_specs/for.md).
//
// An `Int` is not iterable, and this is what saying so looks like.
fun main(): Int {
    val n: Int = 3
    for (x in n) {
        println(x.toString())
    }
    return 0
}
