package fixtures

// Two declarations that name one symbol. A section's entry is keyed by the symbol
// (impl_specs/generators.md), so the generator the emitter reaches last wins:
// `spanOf` carries the real definition and `spanOfEmpty` the decoy - both sections of
// cppsrc/rtl/_res.md, the decoy second. Both calls below reach `simse_spanOf`, so both
// spans come from the decoy - that is the documented hazard, and this case pins it: a
// generator that does not check `has` first overwrites another's implementation, and
// an `expected.cpp` shows the single definition the amalgamation ends up with.

@SmGen("res", "spanOf")
fun realSpan<T>(items: *List<T>): Span<T>

@SmGen("res", "spanOfEmpty")
fun emptySpan<T>(items: *List<T>): Span<T>

fun main(): Int {
    var items: List<Int> = List<Int>()
    items.append(3)
    val real: Span<Int> = realSpan(*items)
    val empty: Span<Int> = emptySpan(*items)
    println(real.size())
    println(empty.size())
    return 0
}
