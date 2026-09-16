package app

// The one handle conversion that is *not* inferred (specs/functions.md, "Handles at a
// call"): a raw pointer cannot become a counted reference in place. `&T` counts a box
// - `&x` is the counted reference to a *copy* of `x` - and a `*T` argument is a
// pointer to somebody's storage, so the compiler would have to guess whether the call
// wants a copy of that storage or a share the language has no box for. The writer
// says it, one line before the call:
//
//     var boxed: &Int = &v
//     printRef(boxed)

fun printRef(v: &Int): Int {
    return copy(v)
}

fun main(): Int {
    var v: Int = 1
    var vptr: *Int = *v
    println(printRef(vptr).toString())
    return 0
}
