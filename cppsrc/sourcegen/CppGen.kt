// CppGen.kt
//
// The `cpp` generator: `native("sym")` and `@SmGen("cpp"[, symbol])`.
// The C++ is hand-written and linked in already (a header's), so this generator produces
// nothing at all: `None` is its whole answer. It is registered (impl_specs/generators.md)
// so that `native` is one generator among others rather than a special case of the
// emitter, and the *manager* does the rest of what such a declaration needs - the symbol a
// call reaches, the prototype, the receiver pattern (`sourceGenDeclare`,
// `sourceGenDeclaresPrototype`).
//
// The relation to `res` is the point of the two: `cpp` means "the text exists, in a header",
// `res` means "the text is a resource the emitter places".

package sourcegen

// Self-registration (`SourceGen.kt`): the generator names itself in the compiler's table,
// from its own file, with the static's initializer doing the work. `true`/`true` - the C++
// is in a header, so the declaration keeps its prototype and its receiver pattern is
// registered.
val cppGenRegistered: Bool = registerSourceGen("cpp", cppGen, true, true)

fun cppGen(ctx: *SourceGenContext): SourceGenTransform {
    // Nothing is read and nothing is written: the declaration's own name (or the symbol its
    // attribute names, which the manager already resolved) is what a call reaches, and the
    // header has the text. The generator takes no parameters of its own, which is why it
    // needs no branch on them here.
    return SourceGenTransform(SourceTransformation.None, "")
}
