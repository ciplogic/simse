Generated Simse
====
The source of the `@SmGen("kt", "greet")` declaration in this case's src/main.kt
(impl_specs/generators.md, "Generated Simse sources"). The driver reads `source` from the
program's own resources, compiles it with the program - package `rtl`, which is what this
line makes it - and the call site reaches the function by the declaration's own name.
A prose line here is ignored only when it holds no colon.

greet
====
source:
```kt
// The implementation of `greeting` (stress/smgen-kt/src/main.kt), written in Simse and
// compiled with the program: nothing about it is C++, and nothing was hand-written
// twice - the declaration says what the call looks like, this says what it does.
fun greeting(name: Str): Str {
    var text: Str = "Hello, "
    text.appendStr(name)
    text.append('!')
    return text
}
```
