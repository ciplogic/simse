sourcegen: true

// This module ships source generators: it is named by the root's manifest, and the compiler
// carries the built-in ones, so naming it no longer drops the compilation. What a compiler
// cannot do is emit a declaration whose generator it does not have (g.kt's `@SmGen("nope")`).
