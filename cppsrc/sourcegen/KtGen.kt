// KtGen.kt
//
// The `kt` generator: `@SmGen("kt", section)`. Its implementation is *Simse source* - read
// from `<section>:source`, the tree's own resources first and the compiler's second - which
// this generator hands to the manager, and the manager to the driver, which parses it with
// the program (`sourceGenReparseSource`). That is `ReparseRequired`: the compiler's own
// front end runs over the text, so the generated function is checked and emitted like any
// other, and nothing is emitted for the declaration itself - not even a prototype, because
// the generated function's own declaration is what a call binds to.
//
// A generator never parses anything itself (impl_specs/generators.md, "What a generator may
// touch"): it says *what* it produced, and the compiler decides what to do with it.

package sourcegen

// Self-registration (`SourceGen.kt`): the generator names itself in the compiler's table,
// from its own file. `false`/`false` - nothing is emitted for the declaration, not even a
// prototype, and the generated function carries its own receiver.
val ktGenRegistered: Bool = registerSourceGen("kt", ktGen, false, false)

fun ktGen(ctx: *SourceGenContext): SourceGenTransform {
    val section: Str = ctx.parameter(0)
    if (ctx.phase != SourceGenPhase.Reparse) {
        // `Declare` and `Emit` have nothing to add: the source is compiled, not placed in
        // the assembly.
        return SourceGenTransform(SourceTransformation.ReparseRequired, section)
    }

    // The lookup is "the tree's own resources first, the compiler's second" - the rule the
    // `res` generator reads its C++ by (`ResGen.kt`). An empty text counts as missing here,
    // unlike a section's C++: a section with no source is a declaration with no
    // implementation, and saying so now beats an undefined symbol in the C++ later.
    val key: Str = section + ":source"
    val source: Str = sourceGenResText(ctx.state, key)
    if (source == "") {
        ctx.error = "no source for @SmGen(\"kt\", \"" + section + "\") (needs the resource " + key + ")"
        return SourceGenTransform(SourceTransformation.None, section)
    }

    // One block per declaration, and the driver joins them into one module: a comment
    // naming the section is what a diagnostic or an emitted source comment can point at
    // (the module is a synthetic file, so the line is all a reader has).
    ctx.source = "\n// " + section + "\n"
    ctx.source.appendStr(source)
    if (ctx.source[ctx.source.size() - 1] != '\n') {
        ctx.source.append('\n')
    }
    return SourceGenTransform(SourceTransformation.ReparseRequired, section)
}
