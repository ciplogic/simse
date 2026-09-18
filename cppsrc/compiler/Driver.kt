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
import skelparser
import common
import profiling
import resources

// ---- module helpers -------------------------------------------------------

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
// `impl_specs/generators.md`, the `kt` generator: a `@SmGen("kt", section)` declaration's
// implementation is *Simse source*, which the compiler compiles like any other module -
// it is parsed, checked and emitted with the program. The source is read from
// `<section>:source`, from the program's own resources first and the compiler's when the
// program does not carry it, and the generated module is in package `rtl`, where a bare
// name is the symbol a call reaches: the generated function carries the declaration's own
// name and the call site reaches it unchanged.

// Every `@SmGen("kt", section)` declaration in a module tree, in source order - a walk
// that does not care where a declaration sits (a top-level function, a method, a nested
// module).
fun driverCollectKtGen(node: AstXmlNode, sections: *List<Str>): Unit {
    if (node.name == AstNodeKind.Function
        && xmlAttr(node, AstNodeAttributeKind.Generator) == "kt"
    ) {
        sections.append(cgGeneratorArg(xmlAttr(node, AstNodeAttributeKind.GeneratorArgs), 0))
    }
    var i: Int = 0
    while (i < node.Children.count()) {
        driverCollectKtGen(node.Children[i], sections)
        i = i + 1
    }
}

// The generated module's text, or "" when the program makes no `kt` declaration. Every
// section has to exist: a declaration whose source is missing could only fail later, in
// the C++, with an error that names no declaration.
fun driverGeneratedSource(modules: List<AstXmlNode>, resources: List<ResourceItem>): Res<Str> {
    var sections: List<Str> = List<Str>()
    var m: Int = 0
    while (m < modules.size()) {
        driverCollectKtGen(modules[m], sections)
        m = m + 1
    }
    if (sections.size() == 0) {
        return Res<Str>.ok("")
    }
    var text: Str = "package rtl\n"
    var i: Int = 0
    while (i < sections.size()) {
        val section: Str = sections[i]
        var source: Str = resValueOf(resources, section + ":source")
        if (source == "") {
            val key: Str = section + ":source"
            if (Resources.has(key)) {
                source = Resources.get(key).toString()
            }
        }
        if (source == "") {
            return Res<Str>.err(
                "simse: no source for @SmGen(\"kt\", \"" + section + "\")"
                        + " (needs the resource " + section + ":source)"
            )
        }
        // Where a section starts, for a diagnostic or an emitted source comment: the
        // whole module is one synthetic file, so the line is all a reader has.
        text.appendStr("\n// " + section + "\n")
        text.appendStr(source)
        if (source.size() == 0 || source[source.size() - 1] != '\n') {
            text.append('\n')
        }
        i = i + 1
    }
    return Res<Str>.ok(text)
}

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

    var moduleRoots: List<Str> = List<Str>()
    if (haveRoot) {
        moduleRoots.append(rootDir)
    }
    var e: Int = 0
    while (e < extraRoots.size()) {
        moduleRoots.append(extraRoots[e])
        e = e + 1
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
    val resources: List<ResourceItem> = resLoad(moduleRoots)
    val resourceEntries: List<Str> = resEntriesFlat(resources)

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

    // The generated Simse sources (`@SmGen("kt", ...)`, impl_specs/generators.md): the
    // compiler's front end runs again over text that is not a file, and what comes out is
    // an ordinary module - checked with the program and emitted after it.
    val generated: Res<Str> = driverGeneratedSource(modules, resources)
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

    val emitted: Res<Str> = emitProgram(cgInputs, resourceEntries)
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
