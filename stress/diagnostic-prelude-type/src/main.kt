package app

// A program type may not reuse a prelude type's name (specs/modules.md, "Shadowing"): the
// prelude's types are emitted under their bare names and the prelude's own generated code
// names them (`Opt<Int> simse_str_toInt(...)`), so a second declaration of the name would
// make the emitter's name -> package table misname one of the two. See
// `stress/unions` for the working shape; a *function* may still shadow a prelude function.

data class Res(var id: Int)

fun main(): Int {
    val r = Res(1)
    println(r.id)
    return 0
}
