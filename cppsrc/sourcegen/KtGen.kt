// KtGen.kt
//
// The `kt` generator: `@SmGen("kt", section)`. Its implementation is *Simse source* read
// from `<section>:source` (the tree's resources first, the compiler's second), handed back
// as `ReparseRequired` for the driver to parse with the program (`sourceGenReparseSource`).
// The declaration gets nothing, not even a prototype: the generated function's own
// declaration is what a call binds to (impl_specs/generators.md).

package sourcegen

// Self-registration (impl_specs/generators.md). `false`/`false`: nothing is emitted for the
// declaration, not even a prototype, and the generated function carries its own receiver.
val ktGenRegistered: Bool = registerSourceGen("kt", ktGen, false, false)

fun ktGen(ctx: *SourceGenContext): SourceGenTransform {
    val section: Str = ctx.parameter(0)
    if (ctx.phase != SourceGenPhase.Reparse) {
        // `Declare` and `Emit` place nothing: the source is compiled, not assembled.
        return SourceGenTransform(SourceTransformation.ReparseRequired, section)
    }

    // An empty text counts as missing (unlike a section's C++): a `kt` declaration with no
    // source has no implementation, so report it now rather than as an undefined symbol.
    val key: Str = section + ":source"
    val source: Str = sourceGenResText(ctx.state, key)
    if (source == "") {
        ctx.error = fmtStr("no source for @SmGen(\"kt\", \"|\") (needs the resource |)", section, key)
        return SourceGenTransform(SourceTransformation.None, section)
    }

    // The comment names the section: the generated module is a synthetic file, so that line
    // is what a diagnostic or a source comment can point at.
    ctx.source = fmtStr("\n// |\n", section)
    ctx.source.appendStr(source)
    if (ctx.source[ctx.source.size() - 1] != '\n') {
        ctx.source.append('\n')
    }
    return SourceGenTransform(SourceTransformation.ReparseRequired, section)
}
