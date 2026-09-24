// Driver.kt
//
// The compiler's driver and CLI (`cppsrc/simse_bootstrap.cpp` is one amalgamation of these
// sources): loads the RTL prelude, scans the module roots, analyzes the whole compilation
// and amalgamates it into one C++ translation unit. Modules and packages: specs/modules.md.
//
// Usage: <program> <input.kt>... [-o <output.cpp>] [--prelude <file>] [--root <dir>]
//     [--module-root <dir>]...

package compiler

import lex
import parser
import sema
import codegen
import common
import profiling
import resources
import sourcegen

// Where the compiler's *own* resources are read from: the prelude's directory, or the
// directory a single-file `--prelude` is in, so the `_res.md` beside it is still found.
fun driverResourceRoots(prelude: *Str): List<Str> {
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

// The scanner error is prefixed with the file name; the raw token list goes to `parseModule`,
// which drops the trivia the grammar never sees.
fun driverParseFile(fileName: *Str): Res<AstXmlNode> {
    return driverParseSource(readFile(fileName), fileName)
}

// For source text that is not a file: `fileName` is what every diagnostic calls it.
fun driverParseSource(text: *Str, fileName: *Str): Res<AstXmlNode> {
    var scanner: Scanner = Scanner(getTokenRules(), 0, 1, 1, Str())
    scanner.setSource(text)
    var raw: List<Token> = List<Token>()
    while (true) {
        val result: Res<Token> = scanner.nextToken()
        if (!result.isOk()) {
            return Res<AstXmlNode>.err(fmtStr("|: |", fileName, result.Error))
        }
        if (result.Value.kind == TokenKind.Eof) {
            break
        }
        raw.append(result.Value)
    }
    return parseModule(*raw, fileName)
}

// The compilation set: every `*.kt` under each module root, then the explicit inputs; prelude
// files are excluded and each canonical path kept once. Sorted by canonical path, so the order
// depends only on the file set, not on how the files were specified (a scanned root vs an
// explicit input).
fun driverGatherFiles(moduleRoots: *List<Str>, inputs: *List<Str>, preludeCanon: *List<Str>): List<Str> {
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
    // Canonical keys are unique after dedup, so this sort is total and the order deterministic.
    chosen.sort((left: Str, right: Str) -> pathCanonical(left) < pathCanonical(right))
    return chosen
}

// `specs/simse-md.md`: an entry is `key: value` and a repeated key means "one more of these";
// prose lines carry no entry. Read separately from `resources`, before anything else is scanned.

fun manifestValues(text: *Str, key: *Str): List<Str> {
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

fun manifestFile(dir: *Str): Str {
    return dir + "/simse.md"
}

// Expands one root into the module roots to scan: the manifest's modules, or the root itself
// when it names none. `sourcegen: true` needs a compiler extended with those generators, which
// does not exist, so such a compilation is rejected (`specs/simse-md.md`).
fun driverExpandRoot(root: *Str, out: *List<Str>): Res<Str> {
    val file: Str = manifestFile(root)
    if (!pathExists(file)) {
        out.append(root)
        return Res<Str>.ok("")
    }
    val text: Str = readFile(file)
    if (manifestValues(text, "sourcegen").contains("true")) {
        return Res<Str>.err(fmtStr("module '|' declares source generators, which this compiler cannot use yet", root))
    }
    val modules: List<Str> = manifestValues(text, "module")
    if (modules.size() == 0) {
        out.append(root)
        return Res<Str>.ok("")
    }
    var i: Int = 0
    while (i < modules.size()) {
        val module: Str = fmtStr("|/|", root, modules[i])
        if (manifestValues(readFile(manifestFile(module)), "sourcegen").contains("true")) {
            return Res<Str>.err(
                fmtStr("module '|' declares source generators, which this compiler cannot use yet", module)
            )
        }
        out.append(module)
        i = i + 1
    }
    return Res<Str>.ok("")
}

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

    // The command line's roots, each expanded through its manifest (a root without one is
    // scanned whole - what this compiler's own build does).
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

    // The resources (`_res.md`, specs/resources.md): every file the module roots hold, parsed
    // once so the generated sources, the sections and the program's pool read the same list.
    // `resourceStored` holds only the keys the program *carries* - the entries not marked
    // compile-only (`!`) - each as the C++ literal holding its bytes.
    val resources: List<ResourceItem> = resLoad(moduleRoots)
    val resourceStored: List<Str> = resStoredLiterals(resources)

    // The compiler's *own* resources (`_res.md` beside the prelude, read here like its `.kt`
    // files): the second half of the generator lookup (`cppsrc/sourcegen/GenTypes.kt`), which
    // hands a program the RTL's C++ when it carries no section of its own.
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

    // The generators' state (`cppsrc/sourcegen/SourceGen.kt`), filled here because the reparse
    // pass below and the emitter's much later pass both read it.
    sourceGenBegin(preludeNames, preludeModules, fileNames, modules, resources, compilerResources)

    // The generated Simse sources (impl_specs/generators.md): a `ReparseRequired` declaration -
    // the `kt` generator - hands the compiler text that is not a file, parsed here as an
    // ordinary module: checked with the program and emitted after it.
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
        // Added to the generators' state too: the second pass runs after this one, and a
        // generator walking the program should see everything it was compiled with.
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
