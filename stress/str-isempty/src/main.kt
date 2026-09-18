package fixtures

// `Str.isEmpty` is a *prelude body*, not a native (`cppsrc/rtl/rtl.kt`,
// `impl_specs/rtl-abi.md` T71), so the compiler emits it - but only when a program reaches
// it (`Codegen.kt`/`Codegen.cpp`, `reachesPreludeBody`).
//
// This program is the shape that rule has to get right: it never names `Str` as a type
// anywhere. Every receiver is a literal, and the only mention of the function is the call.
// Under the old rule the type test decided whether the body was emitted, and a name the
// program never used as a *type* emitted nothing at all - the generated C++ then called a
// function it never defined (`error C3861: 'isEmpty': identifier not found`). The rule now
// falls back to "the call reaches it" when no overload of the name is attributable, so
// what is called is what is emitted.
//
// A receiver whose type the program *does* name is covered by `stress/rtl-simse`.
fun main(): Int {
    println("".isEmpty())      // true
    println("x".isEmpty())     // false
    if ("".isEmpty()) {
        println("empty")       // empty
    }
    return 0
}
