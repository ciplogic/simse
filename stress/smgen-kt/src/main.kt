package fixtures

// A declaration whose implementation is *generated Simse* (impl_specs/generators.md):
// `@SmGen("kt", "greet")` names the resource section in this case's src/_res.md, whose
// `source` holds the function below. The compiler parses, checks and emits it with the
// program; the call site reaches it by this declaration's own name, because the
// generated module is package `rtl` - where a bare name is the symbol a call emits.

@SmGen("kt", "greet")
fun greeting(name: Str): Str

fun main(): Int {
    println(greeting("world"))
    println(greeting(""))
    return 0
}
