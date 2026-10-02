package diag

// The parenthesized spelling `@(name)` must be closed; one that never is is a diagnostic
// (cppsrc/parser/ParserInterp.kt).

fun main(): Int {
    val who: Str = "world"
    println(`value @(who`)
    return 0
}
