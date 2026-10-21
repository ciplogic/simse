// main.kt
//
// The Simse toolset (smt): a project CLI in the shape of npm, written in Simse. `dx.bat`
// is the developer-experience alias that runs this same tool (`dx build` == `smt build`).
//
//   smt <command> [options] [-- <args>...]
//
// Commands:
//   init [<dir>]           scaffold a project: simse.md + src/main.kt + test/smoke_test.kt
//   build                  transpile the project, then compile it
//   run [-- <args>...]     build the project, then run its program
//   test                   build the @Test suite from test/, then run it
//   check                  transpile the project only: the fast front-end check
//   clean                  remove the build artifacts (out.*, test.*)
//   install [<module>...]  resolve the project's modules                   (stub)
//   help [<command>]       the command list, or one command's
//   version                the toolset's version
//
// Options:
//   --root <dir>     the project root (default: .)
//   --module <dir>   a module the project uses; passed to the compiler (repeatable)
//   --release        optimize the C++ compile (/O2 /DNDEBUG)
//   --quiet          one line per step
//   -h, --help       this text; with a command, that command's
//   --version        the toolset's version
//
// `build`, `run` and `check` drive the native toolchain through the `process` module
// (modules/process) - no script runtime: the compiler (`simse.exe`) transpiles the project
// to `<root>/out.cpp`, and the C++ compiler (`cl`) turns that into `<root>/out.exe`. Run smt
// from the repository root, where `simse.exe` is, with `cl` on the path (`smt.bat` sets that
// environment up). `init`, `check` and `clean` go through the `io` module (writeFile,
// createDirectories, deleteFile); `install` is still a stub (`ai/dx.md` has the why).
//
// The program itself is built the same way every other one is - `smt.bat` does exactly this:
//
//   bun build.js --root bin/smt-tool/src --module modules/io --module modules/process \
//       --out bin/smt-tool/smt_src.cpp --exe bin/smt.exe

package smt

import io
import process

val smtVersion: Str = "0.2.0"

// One parsed command line. `smtParse` builds the value once, at the end; `main` only reads it.
data class SmtOptions(
    var root: Str,
    var modules: List<Str>,
    var release: Bool,
    var quiet: Bool,
    var command: Str,
    var rest: List<Str>,
    var help: Bool,
    var version: Bool,
    var error: Str
)

fun smtKnown(name: Str): Bool {
    return name == "init" || name == "build" || name == "run" || name == "test"
            || name == "check" || name == "clean" || name == "install" || name == "help"
            || name == "version"
}

// The commands that are still stubs: everything else is implemented.
fun smtStub(name: Str): Bool {
    return name == "install"
}

fun smtCommandUsage(name: Str): Str {
    if (name == "init") {
        return "init [<dir>]"
    }
    if (name == "run") {
        return "run [-- <args>...]"
    }
    if (name == "test") {
        return "test"
    }
    if (name == "install") {
        return "install [<module>...]"
    }
    if (name == "help") {
        return "help [<command>]"
    }
    return name
}

fun smtCommandSummary(name: Str): Str {
    if (name == "init") {
        return "scaffold a project: simse.md + src/main.kt"
    }
    if (name == "build") {
        return "transpile the project, then compile it"
    }
    if (name == "run") {
        return "build the project, then run its program"
    }
    if (name == "test") {
        return "build the @Test suite from <root>/test, then run it"
    }
    if (name == "check") {
        return "transpile the project only: the fast front-end check"
    }
    if (name == "clean") {
        return "remove the build artifacts (out.cpp, out.obj, out.exe)"
    }
    if (name == "install") {
        return "resolve the project's modules (external modules are deferred)"
    }
    if (name == "help") {
        return "the command list, or one command's"
    }
    if (name == "version") {
        return "the toolset's version"
    }
    return name
}

// What a command does, line by line: a stub's future work, or the steps the implemented
// commands take.
fun smtCommandPlan(name: Str): Unit {
    if (name == "install") {
        println("    - resolve the module list from <root>/simse.md")
        println("    - stage external modules beside the project (specs/modules.md: external modules are deferred)")
    } else if (name == "build") {
        println("    - simse.exe --root <root> [--module <dir>]... -o <root>/out.cpp")
        println("    - cl /nologo /std:c++20 /EHsc /I. /Fo:<root>/out.obj /Fe:<root>/out.exe <root>/out.cpp")
    } else if (name == "check") {
        println("    - simse.exe --root <root> [--module <dir>]... -o <root>/out.cpp (transpile only)")
    } else if (name == "clean") {
        println("    - remove <root>/out.cpp, <root>/out.obj, <root>/out.exe and the test artifacts (test.cpp, test.obj, test.exe) when they exist")
    } else if (name == "run") {
        println("    - build (above)")
        println("    - <root>/out.exe, forwarding -- <args>... to main(args)")
    } else if (name == "test") {
        println("    - simse.exe --root <root> [--module <dir>]... --module <root>/test --tests -o <root>/test.cpp")
        println("    - cl ... /Fo:<root>/test.obj /Fe:<root>/test.exe <root>/test.cpp")
        println("    - <root>/test.exe (a failing assert aborts; the exit code is the suite's)")
    }
}

fun smtUsage(): Unit {
    println("smt - the Simse toolset: a project CLI in the shape of npm.")
    println("")
    println("  smt <command> [options] [-- <args>...]")
    println("")
    println("Commands:")
    println("  init [<dir>]           scaffold a project: simse.md + src/main.kt + test/smoke_test.kt")
    println("  build                  transpile the project, then compile it")
    println("  run [-- <args>...]     build the project, then run its program")
    println("  test                   build the @Test suite from test/, then run it")
    println("  check                  transpile the project only: the fast front-end check")
    println("  clean                  remove the build artifacts (out.*, test.*)")
    println("  install [<module>...]  resolve the project's modules                   (stub)")
    println("  help [<command>]       the command list, or one command's")
    println("  version                the toolset's version")
    println("")
    println("Options:")
    println("  --root <dir>     the project root (default: .)")
    println("  --module <dir>   a module the project uses; passed to the compiler (repeatable)")
    println("  --release        optimize the C++ compile (/O2 /DNDEBUG)")
    println("  --quiet          one line per step")
    println("  -h, --help       this text; with a command, that command's")
    println("  --version        the toolset's version")
    println("")
    println("build, run and check call the compiler and the C++ compiler directly (no script")
    println("runtime); they expect to run from the repository root, with cl on the path.")
}

fun smtCommandHelp(name: Str): Int {
    if (!smtKnown(name)) {
        eprintln("smt: unknown command '" + name + "' (try 'smt help')")
        return 1
    }
    println("smt " + smtCommandUsage(name) + " - " + smtCommandSummary(name))
    if (smtStub(name)) {
        println("")
        println("not implemented yet; it will:")
        smtCommandPlan(name)
    } else if (name == "build" || name == "run" || name == "test" || name == "check" || name == "clean") {
        println("")
        println("it runs:")
        smtCommandPlan(name)
    }
    return 0
}

// The command line: global options anywhere, the first bare word the command, the rest
// positional (after `--`, program arguments). An error is a message in the value's `error`
// field, checked once by `main`.
fun smtParse(argv: List<Str>): SmtOptions {
    var root: Str = "."
    var modules: List<Str> = List<Str>()
    var release: Bool = false
    var quiet: Bool = false
    var command: Str = ""
    var rest: List<Str> = List<Str>()
    var help: Bool = false
    var version: Bool = false
    var error: Str = ""
    var i: Int = 0
    while (i < argv.size()) {
        val arg: Str = argv[i]
        if (arg == "--") {
            var j: Int = i + 1
            while (j < argv.size()) {
                rest.append(argv[j])
                j = j + 1
            }
            i = argv.size()
        } else if (arg == "--root") {
            if (i + 1 >= argv.size()) {
                error = "missing value for --root"
                i = argv.size()
            } else {
                root = argv[i + 1]
                i = i + 2
            }
        } else if (arg == "--module") {
            if (i + 1 >= argv.size()) {
                error = "missing value for --module"
                i = argv.size()
            } else {
                modules.append(argv[i + 1])
                i = i + 2
            }
        } else if (arg == "--release") {
            release = true
            i = i + 1
        } else if (arg == "--quiet") {
            quiet = true
            i = i + 1
        } else if (arg == "--version") {
            version = true
            i = i + 1
        } else if (arg == "-h" || arg == "--help") {
            help = true
            i = i + 1
        } else if (arg.startsWith("-")) {
            error = "unknown option '" + arg + "' (try 'smt help')"
            i = argv.size()
        } else {
            if (command == "") {
                command = arg
            } else {
                rest.append(arg)
            }
            i = i + 1
        }
    }
    return SmtOptions(root, modules, release, quiet, command, rest, help, version, error)
}

// A path for a shell command line, quoted (paths this tool passes rarely hold a quote).
fun smtQuote(text: Str): Str {
    return "\"" + text + "\""
}

// The compiler line: the project's tree, its named modules, and where the amalgamation goes.
fun smtTranspile(opts: SmtOptions, outCpp: Str): Str {
    var command: Str = "simse.exe --root " + smtQuote(opts.root)
    var i: Int = 0
    while (i < opts.modules.size()) {
        command = command + " --module " + smtQuote(opts.modules[i])
        i = i + 1
    }
    return command + " -o " + smtQuote(outCpp)
}

// The C++ line: MSVC, with the repository root on the include path (the emitted file includes
// `src/rtl/simse.hpp`), the object file beside the project's other build output, and the
// program's flags.
fun smtCompile(opts: SmtOptions, outCpp: Str, obj: Str, exe: Str): Str {
    var command: Str = "cl /nologo /std:c++20 /EHsc /bigobj /MD /I. "
    if (opts.release) {
        command = command + "/O2 /DNDEBUG "
    }
    return command + "/Fo:" + smtQuote(obj) + " /Fe:" + smtQuote(exe) + " " + smtQuote(outCpp)
}

// A path for a *command* (not an argument): cmd reads a forward slash as a switch, so
// `examples/hello/out.exe` would be a command `examples`, and the toolchain here is MSVC.
fun smtCommandPath(text: Str): Str {
    var out: Str = Str()
    var i: Int = 0
    while (i < text.size()) {
        val ch: Char = text[i]
        if (ch == '/') {
            out.append('\\')
        } else {
            out.append(ch)
        }
        i = i + 1
    }
    return out
}

// Transpile then compile. The transpile is captured so its diagnostics can be shown as a
// block when it fails; the compile inherits the console, so its progress is live.
fun smtBuild(opts: SmtOptions): Int {
    val outCpp: Str = opts.root + "/out.cpp"
    val obj: Str = opts.root + "/out.obj"
    val exe: Str = opts.root + "/out.exe"
    var code: Int = 0
    val diagnostics: Str = capture(smtTranspile(opts, outCpp), *code)
    if (code != 0) {
        print(diagnostics)
        eprintln("smt: transpiling failed (exit " + code.toString() + ")")
        return code
    }
    val compiled: Int = run(smtCompile(opts, outCpp, obj, exe))
    if (compiled != 0) {
        eprintln("smt: compiling failed (exit " + compiled.toString() + ")")
        return compiled
    }
    if (!opts.quiet) {
        println("smt: built " + exe)
    }
    return 0
}

// Build, then run what it produced (the program's own output streams through).
fun smtRun(opts: SmtOptions): Int {
    val built: Int = smtBuild(opts)
    if (built != 0) {
        return built
    }
    var command: Str = smtQuote(smtCommandPath(opts.root + "/out.exe"))
    var i: Int = 0
    while (i < opts.rest.size()) {
        command = command + " " + smtQuote(opts.rest[i])
        i = i + 1
    }
    return run(command)
}

// test: the suite build (specs/testing.md). The project's tree plus its named modules plus
// `<root>/test` (when it exists - the manifest's `module: src` keeps that folder out of
// build and run) go to the compiler with `--tests`, which swaps the program's main for the
// generated suite; the suite's own exit code - 0 when every test passed, the abort code
// when an assert fired - is the command's.
fun smtTest(opts: SmtOptions): Int {
    val outCpp: Str = opts.root + "/test.cpp"
    val obj: Str = opts.root + "/test.obj"
    val exe: Str = opts.root + "/test.exe"
    var command: Str = "simse.exe --root " + smtQuote(opts.root)
    var i: Int = 0
    while (i < opts.modules.size()) {
        command = command + " --module " + smtQuote(opts.modules[i])
        i = i + 1
    }
    val testDir: Str = opts.root + "/test"
    if (pathIsDirectory(testDir)) {
        command = command + " --module " + smtQuote(testDir)
    }
    command = command + " --tests -o " + smtQuote(outCpp)
    var code: Int = 0
    val diagnostics: Str = capture(command, *code)
    if (code != 0) {
        print(diagnostics)
        eprintln("smt: transpiling failed (exit " + code.toString() + ")")
        return code
    }
    val compiled: Int = run(smtCompile(opts, outCpp, obj, exe))
    if (compiled != 0) {
        eprintln("smt: compiling failed (exit " + compiled.toString() + ")")
        return compiled
    }
    if (!opts.quiet) {
        println("smt: built " + exe)
    }
    return run(smtQuote(smtCommandPath(exe)))
}

// init: scaffold a project at <dir> (or the root) - a manifest naming `src` (which keeps
// `test/` out of build and run), a main.kt that prints hello, and one smoke test under
// `test/`. It never overwrites: any file already there is a refusal, so an init on an
// existing project writes nothing.
fun smtInit(opts: SmtOptions): Int {
    var target: Str = opts.root
    if (opts.rest.size() > 0) {
        target = opts.rest[0]
    }
    val manifest: Str = target + "/simse.md"
    val mainKt: Str = target + "/src/main.kt"
    val testKt: Str = target + "/test/smoke_test.kt"
    if (pathExists(manifest)) {
        eprintln("smt: " + manifest + " already exists - refusing to overwrite it")
        return 1
    }
    if (pathExists(mainKt)) {
        eprintln("smt: " + mainKt + " already exists - refusing to overwrite it")
        return 1
    }
    if (pathExists(testKt)) {
        eprintln("smt: " + testKt + " already exists - refusing to overwrite it")
        return 1
    }
    if (!createDirectories(target + "/src")) {
        eprintln("smt: could not create " + target + "/src")
        return 1
    }
    if (!createDirectories(target + "/test")) {
        eprintln("smt: could not create " + target + "/test")
        return 1
    }
    if (!writeFile(manifest, smtManifestTemplate())) {
        eprintln("smt: could not write " + manifest)
        return 1
    }
    if (!writeFile(mainKt, smtMainTemplate())) {
        eprintln("smt: could not write " + mainKt)
        return 1
    }
    if (!writeFile(testKt, smtTestTemplate())) {
        eprintln("smt: could not write " + testKt)
        return 1
    }
    if (!opts.quiet) {
        println("smt: wrote " + manifest)
        println("smt: wrote " + mainKt)
        println("smt: wrote " + testKt)
        println("next: smt build --root " + target + ", then smt test --root " + target)
    }
    return 0
}

// check: the fast loop - transpile only, no C++ compile. The transpile is the compiler's
// whole front end, so a clean check means parse, types and borrow all passed; what it cannot
// see is the C++ compile. It still writes <root>/out.cpp (the compiler's CLI is file-shaped),
// which a following build overwrites anyway.
fun smtCheck(opts: SmtOptions): Int {
    val outCpp: Str = opts.root + "/out.cpp"
    var code: Int = 0
    val diagnostics: Str = capture(smtTranspile(opts, outCpp), *code)
    if (code != 0) {
        print(diagnostics)
        eprintln("smt: check failed (exit " + code.toString() + ")")
        return code
    }
    if (!opts.quiet) {
        println("smt: " + opts.root + " transpiles cleanly")
    }
    return 0
}

// clean: remove the build artifacts. The deletion goes through the io module's deleteFile,
// not a shell escape, so the tool stays one Simse program with the filesystem behind the
// same FFI every other command uses.
fun smtClean(opts: SmtOptions): Int {
    val artifacts: List<Str> = List<Str>()
    artifacts.append("out.cpp")
    artifacts.append("out.obj")
    artifacts.append("out.exe")
    artifacts.append("test.cpp")
    artifacts.append("test.obj")
    artifacts.append("test.exe")
    var removed: Int = 0
    var failed: Int = 0
    for (name in artifacts) {
        val path: Str = opts.root + "/" + name
        if (!pathExists(path)) {
            continue
        }
        if (deleteFile(path)) {
            removed = removed + 1
            if (!opts.quiet) {
                println("smt: removed " + path)
            }
        } else {
            failed = failed + 1
            eprintln("smt: could not remove " + path + " (is it running?)")
        }
    }
    if (failed > 0) {
        return 1
    }
    if (!opts.quiet) {
        if (removed == 0) {
            println("smt: nothing to remove in " + opts.root)
        } else {
            println("smt: removed " + removed.toString() + " file(s)")
        }
    }
    return 0
}

// The scaffold's two files. The manifest names `src` (specs/simse-md.md); the program is the
// smallest main that prints.
fun smtManifestTemplate(): Str {
    return "# simse.md - the project manifest (specs/simse-md.md): the modules the project\n" +
           "# is built from. Prose lines are ignored; a `module:` line names one module.\n" +
           "\n" +
           "module: src\n"
}

fun smtMainTemplate(): Str {
    return "// main.kt - scaffolded by smt init. `smt run -- <args>` forwards <args> here.\n" +
           "package app\n" +
           "\n" +
           "fun main(args: List<Str>): Int {\n" +
           "    println(\"hello, Simse\")\n" +
           "    return 0\n" +
           "}\n"
}

// The smoke test: one @Test function (specs/testing.md), so `smt test` works on the
// scaffold out of the box. The manifest's `module: src` keeps this folder out of build
// and run; only `smt test` compiles it in.
fun smtTestTemplate(): Str {
    return "// smoke_test.kt - scaffolded by smt init. `smt test` builds the suite from the\n" +
           "// @Test functions under test/ (specs/testing.md).\n" +
           "package tests\n" +
           "\n" +
           "@Test fun smoke(): Unit {\n" +
           "    assert(1 + 1 == 2, \"one and one\")\n" +
           "}\n"
}

// The one stub body (install): say what the command is for, echo what it was given, and
// hand back a failure code. Nothing is written, so nothing can be half-done.
fun smtNotImplemented(name: Str, opts: SmtOptions): Int {
    println("smt " + name + ": not implemented yet - " + smtCommandSummary(name) + ".")
    println("  project root: " + opts.root)
    var flags: Str = ""
    if (opts.release) {
        flags = flags + "--release "
    }
    if (opts.quiet) {
        flags = flags + "--quiet "
    }
    if (flags == "") {
        flags = "none"
    }
    println("  flags:        " + flags)
    if (opts.rest.size() > 0) {
        var joined: Str = ""
        var i: Int = 0
        while (i < opts.rest.size()) {
            if (i > 0) {
                joined = joined + " "
            }
            joined = joined + opts.rest[i]
            i = i + 1
        }
        println("  arguments:    " + joined)
    }
    println("  it will:")
    smtCommandPlan(name)
    return 1
}

fun main(args: List<Str>): Int {
    val opts: SmtOptions = smtParse(args)
    if (opts.error != "") {
        eprintln("smt: " + opts.error)
        return 1
    }
    if (opts.version || opts.command == "version") {
        println("smt " + smtVersion)
        return 0
    }
    if (opts.command == "" || opts.command == "help") {
        if (opts.command == "help" && opts.rest.size() > 0) {
            return smtCommandHelp(opts.rest[0])
        }
        smtUsage()
        return 0
    }
    if (!smtKnown(opts.command)) {
        smtUsage()
        eprintln("smt: unknown command '" + opts.command + "' (try 'smt help')")
        return 1
    }
    if (opts.help) {
        return smtCommandHelp(opts.command)
    }
    if (opts.command == "build") {
        return smtBuild(opts)
    }
    if (opts.command == "run") {
        return smtRun(opts)
    }
    if (opts.command == "test") {
        return smtTest(opts)
    }
    if (opts.command == "init") {
        return smtInit(opts)
    }
    if (opts.command == "check") {
        return smtCheck(opts)
    }
    if (opts.command == "clean") {
        return smtClean(opts)
    }
    return smtNotImplemented(opts.command, opts)
}
