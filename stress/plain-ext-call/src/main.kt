package fixtures

// A plain call to a `this:`-parameter native extension (`append(xs, 3)`, `contains(xs, 3)` -
// the spelling the emitted C++ names, with the receiver as the first argument): the call
// resolves to the declaration the receiver picks (`simse_list_append`, not `simse_str_append`)
// and the type pass types the result from it, so an inferred `val` is a typed declaration and
// not an `auto` (impl_specs/linear-lowering.md; the checker's `semaPlainCallReturn` rule,
// ported to both the IL's resolution and the type pass).

fun main(): Int {
    var xs: List<Int> = List<Int>(0)
    append(xs, 3)
    append(xs, 4)
    val present = contains(xs, 3)
    println(xs.size().toString())
    println(present.toString())
    return 0
}
