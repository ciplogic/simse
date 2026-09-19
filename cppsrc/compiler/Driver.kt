// Driver.kt
//
// The compiler's driver: it loads the RTL prelude set, scans the module roots, parses
// and analyzes the whole compilation, and amalgamates the result into one C++
// translation unit. It is the single root of the compiler source set, and the CLI every
// build runs: the compiler *is* these sources, and the published
// `cppsrc/simse_bootstrap.cpp` is one amalgamation of them.
//
// Modules and packages (specs/modules.md): a module is a directory scanned from
// the module roots; every `.kt` file found participates. `import a.b.c` only
// makes package `a.b.c` visible unqualified, so resolution is by package name and
// never depends on the working directory.
//
// Usage: <program> <input.kt>... [-o <output.cpp>] [--prelude <file>]
//                  [--root <dir>] [--module-root <dir>]...
//
// The output file defaults to `simse_out.cpp` in the current folder - the amalgamated
// compiler, the same file the published `cppsrc/simse_bootstrap.cpp` is a copy of
// (docs/getting-started.md, "Building the compiler without a compiler"). Pass `-o` to
// write anywhere else. The default prelude is the *relative* path `cppsrc/rtl`, so the
// compiler is meant to run from the repository root.
//
// The filesystem/IO helpers are the prelude natives (cppsrc/rtl/fs.kt).

package compiler

import lex
import parser
import sema
import codegen
import common
import profiling
import resources
import sourcegen

// ---- module helpers -------------------------------------------------------

// The directories the compiler's *own* resources are read from: the prelude's directory, or -
// when `--prelude` names a single file - the directory that file is in, so the `_res.md`
// beside it is still found (`resLoad` scans directories).
fun driverResourceRoots(prelude: Str): List<Str> {
    var roots: List<Str> = List<Str>()
    if (pathIsDirectory(prelude)) {
        roots.append(prelude)
        return roots
    }
    val slash: Int = prelude.lastIndexOf("/")
    val backslash: Int = prelude.lastIndexOf("\\")
    var cut: Int = slash
    if (backslash > cut) {
        cut = backslash
    }
    if (cut > 0) {
        roots.append(prelude.substr(0, cut))
    }
    return roots
}

fun driverNewModule(): AstXmlNode {
    return AstXmlNode(AstNodeKind.Module, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
}

fun driverAppendNamed(target: *AstXmlNode, source: *AstXmlNode, role: AstNodeKind): Unit {
    var picked: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < source.Children.count()) {
        if (source.Children[i].name == role) {
            picked.append(source.Children[i])
        }
        i = i + 1
    }
    xmlAddChildren(target, picked)
}

fun driverAppendDecls(target: *AstXmlNode, source: *AstXmlNode): Unit {
    val decls: List<AstXmlNode> = xmlDecls(source)
    xmlAddChildren(target, decls)
}

// ---- scanning / parsing ---------------------------------------------------

// Reads, scans, and parses one file. The scanner error is prefixed with the file name,
// and the whole raw token list goes to `parseModule`, which is where the trivia the
// grammar never sees is dropped (spaces, comments, and a newline inside `(...)`).
fun driverParseFile(fileName: Str): Res<AstXmlNode> {
    return driverParseSource(readFile(fileName), fileName)
}

// The same for source text that is not a file: a generator's output. Its name is what
// every diagnostic and source comment will call it.
fun driverParseSource(text: Str, fileName: Str): Res<AstXmlNode> {
    var scanner: Scanner = Scanner(getTokenRules(), 0, 1, 1, Str())
    scanner.setSource(text)
    var raw: List<Token> = List<Token>()
    while (true) {
        val result: Res<Token> = scanner.nextToken()
        if (!result.isOk()) {
            return Res<AstXmlNode>.err(fileName + ": " + result.Error)
        }
        if (result.Value.kind == TokenKind.Eof) {
            break
        }
        raw.append(result.Value)
    }
    return parseModule(*raw, fileName)
}

// ---- generated Simse sources ----------------------------------------------
//
// `impl_specs/generators.md`: a declaration whose generator answers `ReparseRequired` - the
// `kt` generator, whose source is a resource (`cppsrc/sourcegen/KtGen.kt`) - contributes
// Simse source, which the compiler compiles like any other module: it is parsed, checked and
// emitted with the program. What the declarations are is the generators' own business, so
// the driver only asks for the text and parses it; `sourcegen` joins it into one module in
// package `rtl`, where a bare name is the symbol a call reaches, so the generated function
// carries the declaration's own name and the call site reaches it unchanged.


// The compilation set: every `*.kt` under each module root (in the given
// order, each root scanned recursively and sorted), then the explicit inputs.
// Files already loaded as prelude are excluded and each canonical path is kept
// once; the kept files are sorted by canonical path so the order depends only on
// the file set, not on how the files were specified (a scanned root vs an explicit
// input).
fun driverGatherFiles(moduleRoots: List<Str>, inputs: List<Str>, preludeCanon: List<Str>): List<Str> {
    var candidates: List<Str> = List<Str>()
    var r: Int = 0
    while (r < moduleRoots.size()) {
        val rootFiles: List<Str> = listFiles(moduleRoots[r], ".kt")
        var f: Int = 0
        while (f < rootFiles.size()) {
            candidates.append(rootFiles[f])
            f = f + 1
        }
        r = r + 1
    }
    var i: Int = 0
    while (i < inputs.size()) {
        candidates.append(inputs[i])
        i = i + 1
    }

    var chosen: List<Str> = List<Str>()
    var seen: List<Str> = List<Str>()
    var c: Int = 0
    while (c < candidates.size()) {
        val display: Str = candidates[c]
        val canon: Str = pathCanonical(display)
        if (!preludeCanon.contains(canon) && !seen.contains(canon)) {
            seen.append(canon)
            chosen.append(display)
        }
        c = c + 1
    }
    // After dedup the canonical keys are unique, so this sort is total and the order is
    // deterministic.
    chosen.sort((left: Str, right: Str) -> pathCanonical(left) < pathCanonical(right))
    return chosen
}

// ---- the project file (`simse.md`) ----------------------------------------
//
// `specs/simse-md.md`: a `simse.md` at a module root is the project's manifest - the modules
// the project is built from - and a module's own `simse.md` carries that module's keys. The
// shape is the resource idiom without its sections and fenced values (`specs/resources.md`):
// prose lines carry no entry and are ignored, an entry is `key: value`, and a repeated key
// means "one more of these". The reader is deliberately small and separate from `resources`:
// a manifest is read *before* anything else is (it decides what is scanned), and a key that
// appears twice has to be seen twice.

// Every value of `key` in a manifest's text, in order, trimmed.
fun manifestValues(text: Str, key: Str): List<Str> {
    var out: List<Str> = List<Str>()
    val lines: List<Str> = text.split("\n")
    var i: Int = 0
    while (i < lines.size()) {
        val line: Str = lines[i].trim()
        val colon: Int = line.indexOf(":")
        if (colon > 0 && line.substr(0, colon).trim() == key) {
            out.append(line.substr(colon + 1, line.size() - colon - 1).trim())
        }
        i = i + 1
    }
    return out
}

// A root's or a module's manifest path.
fun manifestFile(dir: Str): Str {
    return dir + "/simse.md"
}

// One root, expanded into the module roots to scan: the modules its manifest names, or the
// root itself when it has no manifest (or names no module - what a file carrying only a
// module's own keys does). A module that declares `sourcegen: true` ships source generators,
// which need a compiler *extended* with them; that is not implemented, so the compilation is
// dropped rather than compiled without them (`specs/simse-md.md`).
fun driverExpandRoot(root: Str, out: *List<Str>): Res<Str> {
    val file: Str = manifestFile(root)
    if (!pathExists(file)) {
        out.append(root)
        return Res<Str>.ok("")
    }
    val text: Str = readFile(file)
    // A root that declares generators is itself a module that ships them.
    if (manifestValues(text, "sourcegen").contains("true")) {
        return Res<Str>.err("module '" + root + "' declares source generators, which this compiler cannot use yet")
    }
    val modules: List<Str> = manifestValues(text, "module")
    if (modules.size() == 0) {
        out.append(root)
        return Res<Str>.ok("")
    }
    var i: Int = 0
    while (i < modules.size()) {
        val module: Str = root + "/" + modules[i]
        if (manifestValues(readFile(manifestFile(module)), "sourcegen").contains("true")) {
            return Res<Str>.err(
                "module '" + module + "' declares source generators, which this compiler cannot use yet"
            )
        }
        out.append(module)
        i = i + 1
    }
    return Res<Str>.ok("")
}

// ---- entry point ----------------------------------------------------------

fun main(args: List<Str>): Int {
    var inputs: List<Str> = List<Str>()
    var output: Str = ""
    var preludePath: Str = ""
    var rootDir: Str = ""
    var haveRoot: Bool = false
    var extraRoots: List<Str> = List<Str>()
    var preludeExplicit: Bool = false

    var i: Int = 0
    while (i < args.size()) {
        val arg: Str = args[i]
        when (arg) {
            "-o" -> {
                if (i + 1 >= args.size()) {
                    eprintln("simse: -o requires a path")
                    return 2
                }
                i = i + 1
                output = args[i]
            }

            "--prelude" -> {
                if (i + 1 >= args.size()) {
                    eprintln("simse: --prelude requires a path")
                    return 2
                }
                i = i + 1
                preludePath = args[i]
                preludeExplicit = true
            }

            "--root" -> {
                if (i + 1 >= args.size()) {
                    eprintln("simse: --root requires a path")
                    return 2
                }
                i = i + 1
                rootDir = args[i]
                haveRoot = true
            }

            "--module-root" -> {
                if (i + 1 >= args.size()) {
                    eprintln("simse: --module-root requires a path")
                    return 2
                }
                i = i + 1
                extraRoots.append(args[i])
            }

            "--showLinearRepresentation" -> {
                ilSetShow(true)
            }

            "--profile" -> {
                profSetEnabled(true)
            }

            "-h", "--help" -> {
                println("usage: simse <input.kt>... [-o <output.cpp>] [--prelude <file>] [--root <dir>] [--module-root <dir>]... [--showLinearRepresentation] [--profile]")
                return 0
            }

            else -> {
                inputs.append(arg)
            }
        }
        i = i + 1
    }

    if (inputs.size() == 0 && !haveRoot && extraRoots.size() == 0) {
        rootDir = "."
        haveRoot = true
    }
    if (output == "") {
        output = "simse_out.cpp"
    }

    // The roots the command line named, then each expanded through its own manifest
    // (`specs/simse-md.md`): a root whose `simse.md` names modules is scanned as those
    // modules, a root without one is scanned whole - which is what this compiler's own
    // build does.
    var roots: List<Str> = List<Str>()
    if (haveRoot) {
        roots.append(rootDir)
    }
    var e: Int = 0
    while (e < extraRoots.size()) {
        roots.append(extraRoots[e])
        e = e + 1
    }
    var moduleRoots: List<Str> = List<Str>()
    var r: Int = 0
    while (r < roots.size()) {
        val expanded: Res<Str> = driverExpandRoot(roots[r], *moduleRoots)
        if (!expanded.isOk()) {
            eprintln("simse: " + expanded.Error)
            return 2
        }
        r = r + 1
    }

    // Prelude set: a directory contributes every `*.kt` in it, a file itself.
    var resolvedPrelude: Str = "cppsrc/rtl"
    if (preludeExplicit) {
        resolvedPrelude = preludePath
    }
    var preludeFiles: List<Str> = List<Str>()
    if (resolvedPrelude != "") {
        if (pathIsDirectory(resolvedPrelude)) {
            preludeFiles = listFiles(resolvedPrelude, ".kt")
        } else if (pathExists(resolvedPrelude)) {
            preludeFiles.append(resolvedPrelude)
        } else if (preludeExplicit) {
            eprintln("simse: prelude not found: " + resolvedPrelude)
            return 2
        }
    }

    var preludeModules: List<AstXmlNode> = List<AstXmlNode>()
    var preludeNames: List<Str> = List<Str>()
    var preludeCanon: List<Str> = List<Str>()
    var mergedPrelude: AstXmlNode = driverNewModule()
    var p: Int = 0
    while (p < preludeFiles.size()) {
        val parsedPrelude: Res<AstXmlNode> = driverParseFile(preludeFiles[p])
        if (!parsedPrelude.isOk()) {
            eprintln(parsedPrelude.Error)
            return 1
        }
        preludeCanon.append(pathCanonical(preludeFiles[p]))
        preludeNames.append(preludeFiles[p])
        preludeModules.append(parsedPrelude.Value)
        driverAppendNamed(mergedPrelude, parsedPrelude.Value, AstNodeKind.Import)
        driverAppendDecls(mergedPrelude, parsedPrelude.Value)
        p = p + 1
    }
    val hasPrelude: Bool = preludeFiles.size() > 0

    val files: List<Str> = driverGatherFiles(moduleRoots, inputs, preludeCanon)

    // The resources (`_res.md`, specs/resources.md): every file the module roots hold,
    // parsed and joined once, so the generated sources below, the sections a generator
    // emits and the program's pool all read the very same list. A compilation with no
    // resource file carries an empty list and emits the same C++ as one built before the
    // feature existed.
    //
    // What the **program carries** is `resStoredLiterals`: every key and value of the entries
    // that are not compile-only (`!`), each spelled as the C++ literal that holds its bytes -
    // text by `resQuoteLiteral`, a `*`-marked value by `resQuoteBinary`, whose bytes are not
    // text. The emitter pools exactly that list, so a marked section is read by the compiler
    // and left out of the program it builds.
    val resources: List<ResourceItem> = resLoad(moduleRoots)
    val resourceStored: List<Str> = resStoredLiterals(resources)

    // The compiler's *own* resources: the `_res.md` files beside the prelude, read from disk
    // here like the prelude's `.kt` files are. These are the second half of the generator
    // lookup (`cppsrc/sourcegen/GenTypes.kt`), and what hands a program the RTL's C++ when
    // the program carries no section of its own.
    //
    // They used to be the compiler's *pool* - the RTL's text embedded in the compiler's own
    // string table and read back through the `Resources` API - which meant the compiler
    // carried 23 KB of text it already had as code. The file is the source of truth and the
    // compiler already reads the directory for its prelude, so the pool is gone:
    // `cppsrc/rtl/_res.md` is marked `!` (`specs/resources.md`).
    var compilerResources: List<ResourceItem> = List<ResourceItem>()
    if (resolvedPrelude != "") {
        compilerResources = resLoad(driverResourceRoots(resolvedPrelude))
    }

    var fileNames: List<Str> = List<Str>()
    var modules: List<AstXmlNode> = List<AstXmlNode>()
    var f: Int = 0
    while (f < files.size()) {
        val parsed: Res<AstXmlNode> = driverParseFile(files[f])
        if (!parsed.isOk()) {
            eprintln(parsed.Error)
            return 1
        }
        fileNames.append(files[f])
        modules.append(parsed.Value)
        f = f + 1
    }

    // The generators' state (`cppsrc/sourcegen/SourceGen.kt`): the files that were read
    // (the prelude first, then the program), the resources they carry, the compiler's own
    // resources, and a fresh output assembly. It is filled here because the two passes below -
    // the reparse one right after this, and the emitter's much later - read the same state,
    // and a generator is handed it whole.
    sourceGenBegin(preludeNames, preludeModules, fileNames, modules, resources, compilerResources)

    // The generated Simse sources (impl_specs/generators.md): a declaration whose generator
    // answers `ReparseRequired` - the `kt` generator reads its source from a resource - hands
    // the compiler text that is not a file, and the front end runs again over it, so what
    // comes out is an ordinary module: checked with the program and emitted after it.
    val generated: Res<Str> = sourceGenReparseSource()
    if (!generated.isOk()) {
        eprintln(generated.Error)
        return 1
    }
    if (generated.Value != "") {
        val parsedGenerated: Res<AstXmlNode> = driverParseSource(generated.Value, "<generated>/kt.kt")
        if (!parsedGenerated.isOk()) {
            eprintln(parsedGenerated.Error)
            return 1
        }
        fileNames.append("<generated>/kt.kt")
        modules.append(parsedGenerated.Value)
        // The generated module is part of the tree a generator sees, so it is added to the
        // state too: the second pass runs after this one, and a generator that walks the
        // program should see everything it was compiled with.
        sourceGenAddModule("<generated>/kt.kt", parsedGenerated.Value)
    }

    // Compilation-wide name/type resolution over the prelude and every module.
    var semaInputs: List<SemaInput> = List<SemaInput>()
    var s: Int = 0
    while (s < preludeModules.size()) {
        semaInputs.append(SemaInput(preludeNames[s], preludeModules[s]))
        s = s + 1
    }
    var m: Int = 0
    while (m < modules.size()) {
        semaInputs.append(SemaInput(fileNames[m], modules[m]))
        m = m + 1
    }
    val diagnostics: List<Str> = analyze(semaInputs)
    if (diagnostics.size() > 0) {
        var d: Int = 0
        while (d < diagnostics.size()) {
            eprintln(diagnostics[d])
            d = d + 1
        }
        return 1
    }

    var cgInputs: List<CgInput> = List<CgInput>()
    if (hasPrelude) {
        cgInputs.append(CgInput(resolvedPrelude, mergedPrelude, true))
    }
    var g: Int = 0
    while (g < modules.size()) {
        cgInputs.append(CgInput(fileNames[g], modules[g], false))
        g = g + 1
    }

    val emitted: Res<Str> = emitProgram(cgInputs, resourceStored)
    if (!emitted.isOk()) {
        eprintln(emitted.Error)
        return 1
    }
    if (!writeFile(output, emitted.Value)) {
        eprintln("simse: cannot write " + output)
        return 1
    }
    return 0
}
