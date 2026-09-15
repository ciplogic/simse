// Driver.kt
//
// The self-hosted compiler driver: a Simse port of cppsrc/codegen/TranspileMain.cpp
// plus the module scanning in compiler::transpile (cppsrc/Compiler.cpp). It loads
// the RTL prelude set, scans the module roots, parses and analyzes the whole
// compilation, and amalgamates the result into one C++ translation unit. It is
// the single root of the compiler source set: its imports pull in the scanner,
// parser, sema, code generator, and the skeleton-parser mirror, so the stage-1
// fixed point covers every mirror.
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
// write anywhere else.
//
// The filesystem/IO helpers are the T23 prelude natives (cppsrc/rtl/fs.kt).

package compiler

import lex
import parser
import sema
import codegen
import skelparser
import common

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
    xmlAddChildren(target, *picked)
}

fun driverAppendDecls(target: *AstXmlNode, source: *AstXmlNode): Unit {
    val decls: List<AstXmlNode> = xmlDecls(source)
    xmlAddChildren(target, *decls)
}

// ---- scanning / parsing ---------------------------------------------------

// Reads, scans, and parses one file. Matches parser::parseFile: the scanner error
// is prefixed with the file name, and the token stream carries a synthetic Eof.
fun driverParseFile(fileName: Str): Res<AstXmlNode> {
    var scanner: Scanner = Scanner(getTokenRules(), 0, 1, 1, Str())
    scanner.setSource(readFile(fileName))
    var tokens: List<Token> = List<Token>()
    while (true) {
        val result: Res<Token> = scanner.nextToken()
        if (!result.isOk()) {
            return Res<AstXmlNode>.err(fileName + ": " + result.Error)
        }
        if (result.Value.kind == TokenKind.Eof) {
            break
        }
        if (result.Value.kind != TokenKind.Space && result.Value.kind != TokenKind.Comment) {
            tokens.append(result.Value)
        }
    }
    var eofPos: SourcePos = SourcePos(0, 1, 1)
    if (tokens.size() > 0) {
        eofPos = tokens[tokens.size() - 1].pos
    }
    tokens.append(Token("", TokenKind.Eof, eofPos))
    return parseModule(spanOf(*tokens), fileName)
}

// The compilation set: every `*.kt` under each module root (in the given
// order, each root scanned recursively and sorted), then the explicit inputs.
// Files already loaded as prelude are excluded and each canonical path is kept
// once; the kept files are sorted by canonical path so the order depends only on
// the file set, matching compiler::transpile byte for byte no matter how the
// files were specified (a scanned root vs an explicit input).
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
    // After dedup the canonical keys are unique, so this sort is total and both
    // compiler rings produce the same sequence.
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
        if (arg == "-o") {
            if (i + 1 >= args.size()) {
                eprintln("simse_transpile: -o requires a path")
                return 2
            }
            i = i + 1
            output = args[i]
        } else if (arg == "--prelude") {
            if (i + 1 >= args.size()) {
                eprintln("simse_transpile: --prelude requires a path")
                return 2
            }
            i = i + 1
            preludePath = args[i]
            preludeExplicit = true
        } else if (arg == "--root") {
            if (i + 1 >= args.size()) {
                eprintln("simse_transpile: --root requires a path")
                return 2
            }
            i = i + 1
            rootDir = args[i]
            haveRoot = true
        } else if (arg == "--module-root") {
            if (i + 1 >= args.size()) {
                eprintln("simse_transpile: --module-root requires a path")
                return 2
            }
            i = i + 1
            extraRoots.append(args[i])
        } else if (arg == "--showLinearRepresentation") {
            ilSetShow(true)
        } else if (arg == "--linearCodegen") {
            // The report: both paths, and the differences on stderr.
            ilSetLinearCodegen(true)
            ilSetLinearCodegenEmit(true)
        } else if (arg == "--linearCodegenEmit") {
            ilSetLinearCodegen(true)
            ilSetLinearCodegenEmit(true)
        } else if (arg == "--statementsCodegen") {
            // The escape hatch: the statement tree emits every body, exactly as before
            // the IL became the source of the output.
            ilSetLinearCodegenEmit(false)
        } else if (arg == "-h" || arg == "--help") {
            println("usage: simse_transpile <input.kt>... [-o <output.cpp>] [--prelude <file>] [--root <dir>] [--module-root <dir>]... [--showLinearRepresentation] [--linearCodegen] [--statementsCodegen]")
            return 0
        } else {
            inputs.append(arg)
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
            eprintln("simse_transpile: prelude not found: " + resolvedPrelude)
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
        driverAppendNamed(*mergedPrelude, *parsedPrelude.Value, AstNodeKind.Import)
        driverAppendDecls(*mergedPrelude, *parsedPrelude.Value)
        p = p + 1
    }
    val hasPrelude: Bool = preludeFiles.size() > 0

    val files: List<Str> = driverGatherFiles(moduleRoots, inputs, preludeCanon)

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

    val emitted: Res<Str> = emitProgram(cgInputs)
    if (!emitted.isOk()) {
        eprintln(emitted.Error)
        return 1
    }
    if (!writeFile(output, emitted.Value)) {
        eprintln("simse_transpile: cannot write " + output)
        return 1
    }
    return 0
}
