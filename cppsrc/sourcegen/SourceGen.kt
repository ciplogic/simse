// SourceGen.kt
//
// The source generators' manager (impl_specs/generators.md): the registered-generator table,
// the dispatch, and the three entry points the compiler calls - `sourceGenDeclare` (codegen,
// resolves the symbol a call reaches), `sourceGenReparseSource` (the driver, joins
// generator-produced Simse source), and `sourceGenEmit` (codegen, places text that needs the
// reach set). Each generator registers itself from its own file with a file-level static.

package sourcegen

import common
import resources

// The table starts empty and each generator appends to it (a file-level static), so the order
// the initialization pass runs in cannot matter; an assignment would have been order-dependent.
// The dispatcher looks a generator up by name, so a name must simply not repeat.

var sourceGenTable: List<SourceGenerator>

fun addSourceGen(
    gens: *List<SourceGenerator>,
    name: Str,
    transform: OnSourceGen,
    declaresPrototype: Bool,
    registersReceiver: Bool
): Unit {
    gens.append(SourceGenerator(name, transform, declaresPrototype, registersReceiver))
}

// A generator's self-registration - one line at the end of its own file (`specs/simse-md.md`).
// Answers `Bool` because a static's initializer is an expression and every static has a type;
// the value is never read.
fun registerSourceGen(
    name: Str,
    transform: OnSourceGen,
    declaresPrototype: Bool,
    registersReceiver: Bool
): Bool {
    addSourceGen(getSourceGens(), name, transform, declaresPrototype, registersReceiver)
    return true
}

fun getSourceGens(): *List<SourceGenerator> {
    return * sourceGenTable
}

fun sourceGenFind(name: *Str): Int {
    val gens: *List<SourceGenerator> = getSourceGens()
    var i: Int = 0
    while (i < gens.size()) {
        if (gens[i].name == name) {
            return i
        }
        i = i + 1
    }
    return -1
}

// An `@SmGen` name nobody registered is a diagnostic the emitter reports, not a silent default.
fun sourceGenHas(name: *Str): Bool {
    return sourceGenFind(name) >= 0
}

// Its C++ is elsewhere and already linked (a header's), so call sites need the declaration;
// a generator whose text is emitted or compiled declares the symbol itself.
fun sourceGenDeclaresPrototype(name: *Str): Bool {
    val at: Int = sourceGenFind(name)
    if (at < 0) {
        return false
    }
    return getSourceGens()[at].declaresPrototype
}

// An explicit `this` registers as a receiver extension (the pattern that selects an overload
// by receiver).
fun sourceGenRegistersReceiver(name: *Str): Bool {
    val at: Int = sourceGenFind(name)
    if (at < 0) {
        return false
    }
    return getSourceGens()[at].registersReceiver
}

var sourceGenState: FullCompiledState = makeSourceGenState()

fun makeSourceGenState(): FullCompiledState {
    return FullCompiledState(
        List<Str>(), List<AstXmlNode>(), 0, List<ResourceItem>(), List<ResourceItem>(),
        Dictionary<Str, Str>(), List<SourceGenRequest>()
    )
}

// A generator is handed this pointer: the files read and the resources they carry, nothing
// else of the compiler.
fun sourceGenTree(): *FullCompiledState {
    return * sourceGenState
}

// The driver's start, before the program is checked: the files read (the prelude first, then
// the program), the resources they carry, the compiler's *own* resources, and a fresh
// assembly.
fun sourceGenBegin(
    preludeNames: *List<Str>,
    preludeModules: *List<AstXmlNode>,
    fileNames: *List<Str>,
    modules: *List<AstXmlNode>,
    resources: *List<ResourceItem>,
    compilerResources: *List<ResourceItem>
): Unit {
    sourceGenResetSink()
    sourceGenState.fileNames = preludeNames
    sourceGenState.modules = preludeModules
    sourceGenState.preludeCount = preludeModules.size()
    sourceGenState.resources = resources
    sourceGenState.compilerResources = compilerResources
    sourceGenState.definitions = Dictionary<Str, Str>()
    sourceGenState.requests = List<SourceGenRequest>()
    var i: Int = 0
    while (i < modules.size()) {
        sourceGenState.fileNames.append(fileNames[i])
        sourceGenState.modules.append(modules[i])
        i = i + 1
    }
}

// A module that only exists after a reparse, so a generator sees the whole compilation.
fun sourceGenAddModule(fileName: *Str, module: *AstXmlNode): Unit {
    sourceGenState.fileNames.append(fileName)
    sourceGenState.modules.append(module)
}

// The generator registered for `ctx.name` runs; a non-empty key is recorded in the state's
// dictionary, which is what `AlreadyExisting` is read back from.
fun runSourceGen(ctx: *SourceGenContext): SourceGenTransform {
    val at: Int = sourceGenFind(ctx.name)
    if (at < 0) {
        ctx.error = fmtStr("unknown source generator '|'", ctx.name)
        return SourceGenTransform(SourceTransformation.None, "")
    }
    val gen: *SourceGenerator = *getSourceGens()[at]
    val answer: SourceGenTransform = gen.transform(ctx)
    if (answer.key.size() > 0) {
        ctx.state.definitions.insert(answer.key, ctx.name)
    }
    return answer
}

// The emitter's `collect` pass, one attributed declaration: the value is the symbol a call
// reaches, the error a generator's message for the caller to report.
fun sourceGenDeclare(decl: *AstXmlNode, fileName: *Str, prelude: Bool): Res<Str> {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    val declName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    var symbol: Str = declName
    if (xmlAttr(decl, AstNodeAttributeKind.HasNativeSymbol) == "true") {
        symbol = sourceGenUnquote(xmlAttr(decl, AstNodeAttributeKind.NativeSymbol))
    }
    var ctx: SourceGenContext = SourceGenContext(
        name,
        SourceGenPhase.Declare,
        decl,
        declName,
        sourceGenArgs(xmlAttr(decl, AstNodeAttributeKind.GeneratorArgs)),
        symbol,
        "",
        "",
        fileName,
        prelude,
        sourceGenTree(),
        sourceGenSink(),
        null
    )
    val answer: SourceGenTransform = runSourceGen(*ctx)
    if (ctx.error.size() > 0) {
        return Res<Str>.err(ctx.error)
    }
    sourceGenState.requests.append(
        SourceGenRequest(name, decl, ctx.parameters, ctx.symbol, declName, fileName, prelude)
    )
    return Res<Str>.ok(ctx.symbol)
}

// The emitter's other pass, after every body: a *prelude* declaration nothing reaches is
// skipped, and text only a generator produces here lands. Every generator is then asked once
// more with an empty declaration, for text no declaration named (`emit: always`).
fun sourceGenEmit(sections: *Sections, reachedNames: *Dictionary<Str, Bool>): Res<Str> {
    var i: Int = 0
    while (i < sourceGenState.requests.size()) {
        val request: *SourceGenRequest = *sourceGenState.requests[i]
        var ctx: SourceGenContext = SourceGenContext(
            request.generator,
            SourceGenPhase.Emit,
            request.declaration,
            request.declName,
            request.parameters,
            request.symbol,
            "",
            "",
            request.fileName,
            request.prelude,
            sourceGenTree(),
            sections,
            reachedNames
        )
        val answer: SourceGenTransform = runSourceGen(*ctx)
        if (ctx.error.size() > 0) {
            return Res<Str>.err(ctx.error)
        }
        i = i + 1
    }
    val gens: *List<SourceGenerator> = getSourceGens()
    var g: Int = 0
    while (g < gens.size()) {
        var program: SourceGenContext = SourceGenContext(
            gens[g].name,
            SourceGenPhase.Emit,
            xmlEmptyNode(),
            "",
            List<Str>(),
            "",
            "",
            "",
            "",
            false,
            sourceGenTree(),
            sections,
            reachedNames
        )
        val answer: SourceGenTransform = runSourceGen(*program)
        if (program.error.size() > 0) {
            return Res<Str>.err(program.error)
        }
        g = g + 1
    }
    return Res<Str>.ok("")
}

// Collects the Simse source every program declaration answers `ReparseRequired`, joined into
// the one module parsed with the program. Prelude declarations are not walked: their source
// would have to be part of the RTL, loaded before this runs.
fun sourceGenReparseSource(): Res<Str> {
    var blocks: List<Str> = List<Str>()
    var m: Int = sourceGenState.preludeCount
    while (m < sourceGenState.modules.size()) {
        val collected: Res<Str> = sourceGenReparseNode(sourceGenState.modules[m], *blocks)
        if (!collected.isOk()) {
            return collected
        }
        m = m + 1
    }
    if (blocks.size() == 0) {
        return Res<Str>.ok("")
    }
    // One module in package `rtl`: a bare name there is the symbol a call reaches, which lets a
    // generated function carry the declaration's own name.
    var text: Str = "package rtl\n"
    var i: Int = 0
    while (i < blocks.size()) {
        text.appendStr(blocks[i])
        i = i + 1
    }
    return Res<Str>.ok(text)
}

// Depth first, in order: the order blocks are joined, so the module depends only on the sources.
fun sourceGenReparseNode(node: *AstXmlNode, blocks: *List<Str>): Res<Str> {
    if (node.name == AstNodeKind.Function
        && xmlAttr(node, AstNodeAttributeKind.Generator).size() > 0
    ) {
        val block: Res<Str> = sourceGenReparseDecl(node)
        if (!block.isOk()) {
            return block
        }
        if (block.Value.size() > 0) {
            blocks.append(block.Value)
        }
    }
    var i: Int = 0
    while (i < node.Children.count()) {
        val child: Res<Str> = sourceGenReparseNode(node.Children[i], blocks)
        if (!child.isOk()) {
            return child
        }
        i = i + 1
    }
    return Res<Str>.ok("")
}

// Its source, or "" when its generator has nothing for the module. An unknown name is left to
// the emitter, which reports it against the declaration.
fun sourceGenReparseDecl(decl: *AstXmlNode): Res<Str> {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    if (!sourceGenHas(name)) {
        return Res<Str>.ok("")
    }
    var ctx: SourceGenContext = SourceGenContext(
        name,
        SourceGenPhase.Reparse,
        decl,
        xmlAttr(decl, AstNodeAttributeKind.Name),
        sourceGenArgs(xmlAttr(decl, AstNodeAttributeKind.GeneratorArgs)),
        "",
        "",
        "",
        "",
        false,
        sourceGenTree(),
        null,
        null
    )
    val answer: SourceGenTransform = runSourceGen(*ctx)
    if (ctx.error.size() > 0) {
        return Res<Str>.err("simse: " + ctx.error)
    }
    if (answer.change == SourceTransformation.ReparseRequired) {
        return Res<Str>.ok(ctx.source)
    }
    return Res<Str>.ok("")
}
