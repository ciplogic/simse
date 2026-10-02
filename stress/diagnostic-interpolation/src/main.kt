package diag

// A string that interpolates may not hold a literal `@`: the runtime counts every `@` of
// the template against the items, so one that starts no name is an error
// (cppsrc/parser/ParserInterp.kt). The message names the two spellings that work.

fun main(): Int {
    val who: Str = "world"
    println(`a@ b and @who`)
    return 0
}
