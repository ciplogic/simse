// ResGen.kt
//
// The `res` generator: `@SmGen("res", section[, symbol])`, whose C++ is a *resource*. The
// sections of `cppsrc/rtl/_res.md` are the RTL's own generated functions; a program's own
// `_res.md` supplies one for itself (specs/resources.md).
//
// Three answers, one job each:
//
//   Declare       resolve the symbol a call reaches: the attribute's second argument wins,
//                 then the section's own `symbol:`, then the declaration's name. Nothing is
//                 placed yet - the reach set is not known until every body is emitted.
//   Emit          place the text: `<section>:<name>` for every *section name* goes into that
//                 section, under the item key the section itself decides - the symbol when
//                 it declares one (two declarations of one symbol then replace each other,
//                 last write wins) and its own name when it does not (a *shared* section:
//                 one header's worth of functions, emitted once however many declarations
//                 reach it).
//   Emit, program the same for `<section>:emit` = `always`: text the compiler emits for
//                 every program with no declaration to hang it on (the string table's
//                 decoder, the clock the profiler reads). It is asked for once per
//                 compilation rather than once per declaration - a generator is asked
//                 twice: for each of its declarations, and once for the program itself.
//
// The text comes from the tree being compiled first - the `_res.md` files under its module
// roots, which is what lets the RTL's own C++ be a resource file, since while the compiler
// is built the tree's file is the newer one - and from the compiler's own table second,
// which is what hands every other program the RTL's C++ without it carrying the RTL's
// resource file (`sourceGenResText`, GenTypes.kt).

package sourcegen

// Self-registration (`SourceGen.kt`): the generator names itself in the compiler's table,
// from its own file. `false`/`true` - the section's `forward` text is the declaration, so
// the declaration keeps no prototype of its own, while its receiver pattern is registered.
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
    // `Reparse` is the driver's pass, and this generator's text is C++ the emitter places.
    return SourceGenTransform(SourceTransformation.None, "")
}

// The symbol a call reaches, resolved while the program is being collected - every call site
// is emitted before the sections are filled, so this cannot wait for the `Emit` phase.
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

// The text, into the sections its own keys name - once every body is emitted, so a *prelude*
// declaration nothing reaches is skipped and a program pays only for what it calls.
fun resGenEmit(ctx: *SourceGenContext): SourceGenTransform {
    if (ctx.prelude && !ctx.isReached()) {
        return SourceGenTransform(SourceTransformation.None, "")
    }
    val section: Str = ctx.parameter(0)
    if (!resGenAddSection(ctx, section)) {
        // No text: nothing to place. (A call site then names a symbol nothing declares, which
        // the C++ compile reports - the emitter cannot fail here, because the declaration
        // this belongs to is a value it does not hold.)
        return SourceGenTransform(SourceTransformation.None, "")
    }
    ctx.state.definitions.insert(section, ctx.name)
    return SourceGenTransform(SourceTransformation.ChangedOutput, section)
}

// `emit: always`: the sections both lists mark, added under their own names. The dictionary
// is what makes this idempotent - a compilation asks once, and a section already defined is
// `AlreadyExisting` rather than text rebuilt and re-inserted.
fun resGenAlways(ctx: *SourceGenContext): SourceGenTransform {
    var sections: List<Str> = List<Str>()
    for (*entry in ctx.state.resources) {
        if (entry.key.endsWith(":emit") && entry.value == "always") {
            sections.append(entry.key.substr(0, entry.key.size() - 5))
        }
    }
    // The compiler's own resources, which is where a program's `strtable`/`timeops` come
    // from: the `_res.md` files beside the prelude, read at run time (GenTypes.kt).
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

// One section's whole text: `<section>:<name>` for every section name, added under the item
// key the section itself decides (its `symbol:` when it declares one, its own name when it
// does not). Answers whether anything was added, which is what tells a declaration with no
// text from one with.
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
