// ResGen.kt
//
// The `res` generator: `@SmGen("res", section[, symbol])`, whose C++ is a resource section.
// `Declare` resolves the symbol a call reaches; `Emit` places `<section>:<name>` text under
// the key the section decides (the symbol, or its own name for a *shared* section), and with
// an empty declaration places `<section>:emit`=`always` text (specs/resources.md). The lookup
// is the tree's resources first, the compiler's own second (`sourceGenResText`, GenTypes.kt).

package sourcegen

// Self-registration (impl_specs/generators.md). `false`/`true`: the section's `forward` text
// is the declaration, so no prototype of its own; its receiver pattern is registered.
val resGenRegistered: Bool = registerSourceGen("res", resGen, false, true)

fun resGen(ctx: *SourceGenContext): SourceGenTransform {
    if (ctx.phase == SourceGenPhase.Declare) {
        return resGenDeclare(ctx)
    }
    if (ctx.phase == SourceGenPhase.Emit) {
        if (xmlIsEmpty(ctx.declaration)) {
            return resGenAlways(ctx)
        }
        return resGenEmit(ctx)
    }
    // `Reparse` is the driver's pass; this generator's text is C++ the emitter places.
    return SourceGenTransform(SourceTransformation.None, "")
}

// The symbol is resolved at `Declare`: every call site is emitted before the sections are
// filled, so this cannot wait for the `Emit` phase.
fun resGenDeclare(ctx: *SourceGenContext): SourceGenTransform {
    val named: Str = ctx.parameter(1)
    if (named != "") {
        ctx.symbol = named
        return SourceGenTransform(SourceTransformation.ChangedOutput, "")
    }
    val key: Str = ctx.parameter(0) + ":symbol"
    if (sourceGenResHas(ctx.state, key)) {
        ctx.symbol = sourceGenResText(ctx.state, key)
    }
    return SourceGenTransform(SourceTransformation.ChangedOutput, "")
}

// Once every body is emitted, so a *prelude* declaration nothing reaches is skipped.
fun resGenEmit(ctx: *SourceGenContext): SourceGenTransform {
    if (ctx.prelude && !ctx.isReached()) {
        return SourceGenTransform(SourceTransformation.None, "")
    }
    val section: Str = ctx.parameter(0)
    if (!resGenAddSection(ctx, section)) {
        // Nothing to place: a call site then names a symbol nothing declares, which the C++
        // compile reports (the emitter cannot fail here).
        return SourceGenTransform(SourceTransformation.None, "")
    }
    ctx.state.definitions.insert(section, ctx.name)
    return SourceGenTransform(SourceTransformation.ChangedOutput, section)
}

// `emit: always`: the sections both lists mark, added under their own names. The definitions
// dictionary makes this idempotent - an already-defined section answers `AlreadyExisting`.
fun resGenAlways(ctx: *SourceGenContext): SourceGenTransform {
    var sections: List<Str> = List<Str>()
    for (*entry in ctx.state.resources) {
        if (entry.key.endsWith(":emit") && entry.value == "always") {
            sections.append(entry.key.substr(0, entry.key.size() - 5))
        }
    }
    for (*entry in ctx.state.compilerResources) {
        if (entry.key.endsWith(":emit") && entry.value == "always") {
            sections.append(entry.key.substr(0, entry.key.size() - 5))
        }
    }
    var added: Bool = false
    var i: Int = 0
    while (i < sections.size()) {
        val section: Str = sections[i]
        if (!ctx.state.definitions.has(section)) {
            resGenAddSection(ctx, section)
            ctx.state.definitions.insert(section, ctx.name)
            added = true
        }
        i = i + 1
    }
    if (!added) {
        return SourceGenTransform(SourceTransformation.AlreadyExisting, "")
    }
    return SourceGenTransform(SourceTransformation.ChangedOutput, "")
}

// `<section>:<name>` for every current section name, under the item key the section decides
// (`symbol:` when it declares one, its own name otherwise). Answers whether anything was added.
fun resGenAddSection(ctx: *SourceGenContext, section: *Str): Bool {
    var item: Str = section
    val declared: Str = section + ":symbol"
    if (sourceGenResHas(ctx.state, declared)) {
        item = sourceGenResText(ctx.state, declared)
    }
    val names: List<Str> = ctx.sections.names()
    var added: Bool = false
    var i: Int = 0
    while (i < names.size()) {
        val key: Str = fmtStr("|:|", section, names[i])
        if (sourceGenResHas(ctx.state, key)) {
            ctx.sections.add(names[i], item, sourceGenResText(ctx.state, key))
            added = true
        }
        i = i + 1
    }
    return added
}
