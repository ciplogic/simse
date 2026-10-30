package fixtures

// The refusal side of the same inference: `s.size()` fits `Str`, `List<T>`, `Span<T>` and
// `Dictionary` alike, so no site can prove `s` - and the closure is monomorphic, so ambiguity
// is the emitter's diagnostic, never a guess.

fun main(): Int {
    val f = (s) -> s.size()
    println(f("hey").toString())
    return 0
}
