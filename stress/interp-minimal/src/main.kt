package interpminimal

// The smallest interpolation program there is: one backtick string, one item, and nothing
// that names `Span` any other way. The `fmtStrWith` body such a program reaches indexes its
// `StrView` template, and that synthesized `get` was once emitted without a declaration -
// every other interpolation case also indexes something of its own, which reached `get` for
// it and papered over the gap (`CgEmitFn.kt`'s alias resolution closes it).

fun main(): Int {
    val n: Int = 5
    println(`n=@n`)
    return 0
}
