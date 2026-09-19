package fixtures

// A section whose title opens with `!` is *compile-only* (specs/resources.md): the compiler
// reads it - the `kt` generator below compiles that source into this program - and the
// program does not carry it, so the text is not in the executable a second time. `Kept` is
// not marked, so `main` still finds it at run time.

@SmGen("kt", "Greet")
fun greeting(name: Str): Str

fun main(): Int {
    println(greeting("world"))
    println(Resources.has("Greet:source"))
    println(Resources.has("Kept:note"))
    println(Resources.count())
    return 0
}
