// SourceGen.kt
//
// The source generators' manager (impl_specs/generators.md): the table of registered
// generators, the dispatch, and the three entry points the rest of the compiler calls.
//
//   sourceGenDeclare        codegen, for every declaration that carries an attribute:
//                           resolves the symbol a call reaches and records the declaration
//                           for the `Emit` phase. The value is that symbol; an error is the
//                           generator's own message.
//   sourceGenReparseSource  the driver, before the program is checked: the Simse source a
//                           generator produced, joined into one module (or "" when none
//                           did).
//   sourceGenEmit           codegen, after every body: the text that needs the program's
//                           calls to be known - the reach set - and the one pass a
//                           generator gets for the program itself.
//
// The table is filled by the generators themselves: each one registers from its own file
// with a file-level static - `registerSourceGen`, one line at the end of the generator's
// source - so the built-in three and a module's generator register the same way. It is read
// through a pointer - the shape `cppsrc/lex/Scanner.kt` gives its token matchers, and a
// static for the same reason: the compiler is one compilation per process.
//
// This package calls nothing from the compiler's stages, and this file is the boundary a
// generator is held to: a generator reads and writes the *data* it is handed (the AST nodes,
// the resources, the sections) and answers a `SourceGenTransform`; everything else - the
// prototype, the receiver pattern, the parsing of what it produced - is done by the caller.
// A generator therefore cannot break when a compiler API changes, and it cannot do
// arbitrary things to the compilation either.

package sourcegen

import common
import resources

// ---- the table ------------------------------------------------------------
//
// The table starts **empty** and every generator appends itself to it, from its own file,
// with a file-level static whose initializer is the registration (`specs/statics.md`). That
// is what makes the order the initialization pass runs in irrelevant: storage starts empty
// as a guarantee, and a registration is an *append*, so whether the built-in three run
// before or after a module's generator cannot matter. An assignment here - the
// `var table = makeSourceGens()` this used to be - would have been order-dependent: a
// registration that ran first would be overwritten by the assignment that ran second.
//
// The dispatcher looks a generator up by *name*, so the order the table ends up in is not
// observable either; a name must simply not repeat. The manager's own statics are program
// statics (this tree is a program to the compiler that builds it), so they run in
// `simse_initStatics`, before the driver is reached.

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

// One generator's self-registration: what a generator's file-level static is initialized
// with, so that registering reads as one line at the end of the generator's own file
// (`specs/simse-md.md`, a module's generators register the same way). It answers `Bool`
// because a static's initializer is an expression and every static has a type - the value
// itself is never read.
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

// The index of the generator registered under `name`, or -1.
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

// Whether a generator is registered for `name`: an `@SmGen` name nobody registered is a
// diagnostic the emitter reports against the declaration, not a silent default.
fun sourceGenHas(name: *Str): Bool {
    return sourceGenFind(name) >= 0
}

// Whether a declaration of this generator gets a prototype of its own: its C++ is elsewhere
// and already linked (a header's), so the call sites need the declaration. A generator whose
// text is emitted or compiled declares the symbol itself.
fun sourceGenDeclaresPrototype(name: *Str): Bool {
    val at: Int = sourceGenFind(name)
    if (at < 0) {
        return false
    }
    return getSourceGens()[at].declaresPrototype
}

// Whether a declaration of this generator that has an explicit `this` is registered as a
// receiver extension (the pattern that selects an overload by receiver).
fun sourceGenRegistersReceiver(name: *Str): Bool {
    val at: Int = sourceGenFind(name)
    if (at < 0) {
        return false
    }
    return getSourceGens()[at].registersReceiver
}

// ---- the compilation ------------------------------------------------------

var sourceGenState: FullCompiledState = makeSourceGenState()

fun makeSourceGenState(): FullCompiledState {
    return FullCompiledState(
        List<Str>(), List<AstXmlNode>(), 0, List<ResourceItem>(), List<ResourceItem>(),
        Dictionary<Str, Str>(), List<SourceGenRequest>()
    )
}

// The whole state, for the dispatch below: a generator is handed this pointer, so it sees
// the files that were read and the resources they carry, and nothing else of the compiler.
fun sourceGenTree(): *FullCompiledState {
    return * sourceGenState
}

// The driver's start, before the program is checked: the files that were read (the prelude
// first, then the program), the resources they carry, the compiler's *own* resources (the
// `_res.md` files beside the prelude, read from disk - the second half of the generator
// lookup), and a fresh assembly. Everything a generator may look at is here by the time the
// first dispatch runs.
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

// A module that only exists after a reparse (the driver's generated one), so the tree a
// generator sees is the whole compilation and not only the files it started with.
fun sourceGenAddModule(fileName: *Str, module: *AstXmlNode): Unit {
    sourceGenState.fileNames.append(fileName)
    sourceGenState.modules.append(module)
}

// ---- dispatch -------------------------------------------------------------

// One dispatch: the generator registered for `ctx.name` runs, and every answer that names a
// key is recorded in the state's dictionary - which is what a generator that would produce
// the same thing twice reads back to answer `AlreadyExisting` (a generator that produces
// several keys records the rest itself, GenTypes.kt's `FullCompiledState`).
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

// ---- the compiler's entry points ------------------------------------------

// The emitter's `collect` pass: one declaration that carries an attribute. The value is the
// symbol a call reaches - the declaration's own name unless the declaration or its generator
// says otherwise - and the error a generator's message, for the caller to report against the
// declaration.
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

// The emitter's other pass, after every body: by now the program's calls are known, so a
// *prelude* declaration nothing reaches is skipped, and the text a generator produces only
// here - a resource section - lands in the assembly. Every generator is then asked once more
// for the program itself (`declaration` empty): that is where text no declaration named goes
// (`emit: always`, a generator's own sections).
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

// The driver's pass, before the program is checked: every declaration in the *program's* own
// modules that answers `ReparseRequired` contributes Simse source, and the blocks are joined
// into the one module the front end parses with the program. A prelude declaration is not
// walked: its source would have to be part of the RTL, which is loaded before this runs.
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
    // One module, one package: a bare name in `rtl` is the symbol a call reaches, which is
    // what lets a generated function carry the declaration's own name.
    var text: Str = "package rtl\n"
    var i: Int = 0
    while (i < blocks.size()) {
        text.appendStr(blocks[i])
        i = i + 1
    }
    return Res<Str>.ok(text)
}

// One module's declarations, in order, depth first - the order the blocks are joined in, so
// the generated module depends only on the sources.
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

// One declaration's answer to the reparse pass: its source, or "" when its generator has
// nothing for the module. An unknown name is left to the emitter, which reports it against
// the declaration it came from.
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
