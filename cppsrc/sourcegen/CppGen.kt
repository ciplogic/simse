// CppGen.kt
//
// The `cpp` generator: `@SmGen("cpp"[, symbol])`. The C++ is hand-written in a header and
// already linked, so the generator produces nothing (`None`); the *manager* does what such a
// declaration needs - the symbol a call reaches, the prototype, the receiver pattern
// (`sourceGenDeclare`, `sourceGenDeclaresPrototype`).

package sourcegen

// Self-registration (impl_specs/generators.md). `true`/`true`: the C++ is in a header, so the
// declaration keeps its prototype and its receiver pattern is registered.
val cppGenRegistered: Bool = registerSourceGen("cpp", cppGen, true, true)

fun cppGen(ctx: *SourceGenContext): SourceGenTransform {
    return SourceGenTransform(SourceTransformation.None, "")
}
