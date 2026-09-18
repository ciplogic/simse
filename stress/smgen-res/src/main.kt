package fixtures

// The resource-backed generator (impl_specs/generators.md): a `@SmGen("res", "spanOf")`
// declaration takes its C++ from the `spanOf` section of the compiler's own
// cppsrc/rtl/_res.md - the `forward` declaration and the definition in `bodies`. The
// prelude's `spanOf` is the same declaration (cppsrc/rtl/Span.kt); this program has its
// own name for it, so the case does not depend on the prelude's reachability rule.

@SmGen("res", "spanOf")
fun spanOfItems<T>(items: *List<T>): Span<T>

fun main(): Int {
    var items: List<Int> = List<Int>()
    items.append(11)
    items.append(22)
    items.append(33)
    val view: Span<Int> = spanOfItems(*items)
    println(view.size())
    println(view.at(2))
    return 0
}
