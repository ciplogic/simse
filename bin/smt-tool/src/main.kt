// main.kt
//
// The Simse toolset (smt): a project CLI in the shape of npm, written in Simse.
//
//   smt <command> [options] [-- <args>...]
//
// Commands:
//   init [<dir>]           scaffold a project: simse.md + src/main.kt   (stub)
//   build                  transpile and compile the project            (stub)
//   run [-- <args>...]     build the project, then run its program      (stub)
//   install [<module>...]  resolve the project's modules               (stub)
//   help [<command>]       the command list, or one command's
//   version                the toolset's version
//
// Options:
//   --root <dir>   the project root (default: .) - the directory a simse.md sits in
//   --release      optimize the build (what `build.js --release` means); build, run
//   --quiet        one summary line per step; build, run
//   -h, --help     this text; with a command, that command's
//   --version      the toolset's version
//
// The toolset is a front door to what the repository already has: `build` and `run` are
// `build.js` (and the `cl.exe` environment `tools/msvc.mjs` materializes) over the program
// it produces; `install` is the module resolution `specs/modules.md` defers ("External
// modules (deferred)"); `init` writes the `simse.md` manifest `specs/simse-md.md` defines.
//
// Every command is a **stub**: it prints the plan it will carry out and exits 1, so nothing
// is half-done silently. `help` and `version` answer normally (exit 0).
//
// The program is built like any other - `smt.bat` does exactly this, from the repository
// root (`smt_src.cpp` beside this file is the checked-in amalgamation, what gets compiled
// when no compiler is present):
//
//   bun build.js --root bin/smt-tool/src --module modules/io \
//       --out bin/smt-tool/smt_src.cpp --exe bin/smt.exe
//
// (`modules/io` is named for `eprintln`; everything else is the prelude.)

package smt

import io

// Bumped with the toolset's own changes; printed by `--version`/`version`.
val smtVersion: Str = "0.1.0"

// One parsed command line. `smtParse` builds the value once, at the end, so no field is
// mutated through a copy; `main` only reads it.
data class SmtOptions(
    var root: Str,
    var release: Bool,
    var quiet: Bool,
    var command: Str,
    var rest: List<Str>,
    var help: Bool,
    var version: Bool,
    var error: Str
)

fun smtKnown(name: Str): Bool {
    return name == "init" || name == "build" || name == "run"
            || name == "install" || name == "help" || name == "version"
}

fun smtCommandUsage(name: Str): Str {
    if (name == "init") {
        return "init [<dir>]"
    }
    if (name == "run") {
        return "run [-- <args>...]"
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
        return "scaffold a Simse project (simse.md + src/main.kt)"
    }
    if (name == "build") {
        return "transpile and compile the project into an executable"
    }
    if (name == "run") {
        return "build the project, then run its program"
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

// The plan a stub prints: the work the command will do once it is implemented. A command
// with no plan (`help`, `version`) prints nothing.
fun smtCommandPlan(name: Str): Unit {
    if (name == "init") {
        println("    - write <dir>/simse.md - the manifest a project root carries (specs/simse-md.md)")
        println("    - write <dir>/src/main.kt - a main(args) that prints hello")
        println("    - report the smt build / smt run commands to try next")
    } else if (name == "build") {
        println("    - read the module list from <root>/simse.md, else scan <root> whole (specs/simse-md.md)")
        println("    - transpile it with the compiler CLI (--root <root> [--module <dir>]... -o <out>.cpp, ai/building.md)")
        println("    - compile the one translation unit with cl.exe, the environment tools/msvc.mjs materializes")
    } else if (name == "run") {
        println("    - run smt build first (above)")
        println("    - run the produced executable, forwarding -- <args>... to main(args)")
    } else if (name == "install") {
        println("    - resolve the module list from <root>/simse.md")
        println("    - stage external modules beside the project (specs/modules.md: external modules are deferred)")
    }
}

fun smtUsage(): Unit {
    println("smt - the Simse toolset: a project CLI in the shape of npm.")
    println("")
    println("  smt <command> [options] [-- <args>...]")
    println("")
    println("Commands:")
    println("  init [<dir>]           scaffold a project: simse.md + src/main.kt   (stub)")
    println("  build                  transpile and compile the project            (stub)")
    println("  run [-- <args>...]     build the project, then run its program      (stub)")
    println("  install [<module>...]  resolve the project's modules               (stub)")
    println("  help [<command>]       the command list, or one command's")
    println("  version                the toolset's version")
    println("")
    println("Options:")
    println("  --root <dir>   the project root (default: .) - the directory a simse.md sits in")
    println("  --release      optimize the build (what build.js --release means); build, run")
    println("  --quiet        one summary line per step; build, run")
    println("  -h, --help     this text; with a command, that command's")
    println("  --version      the toolset's version")
    println("")
    println("Every command is a stub: it prints the plan it will carry out and exits 1, so")
    println("nothing is half-done silently. help and version answer normally (exit 0).")
}

fun smtCommandHelp(name: Str): Int {
    if (!smtKnown(name)) {
        eprintln("smt: unknown command '" + name + "' (try 'smt help')")
        return 1
    }
    println("smt " + smtCommandUsage(name) + " - " + smtCommandSummary(name))
    println("")
    println("not implemented yet; it will:")
    smtCommandPlan(name)
    return 0
}

// The command line: global options anywhere, the first bare word the command, the rest
// positional (after `--`, program arguments). An error is a message in the value's `error`
// field, checked once by `main` - a returned code, not an exit inside the parser.
fun smtParse(argv: List<Str>): SmtOptions {
    var root: Str = "."
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
    return SmtOptions(root, release, quiet, command, rest, help, version, error)
}

// The one stub body: say what the command is for, echo what it was given, and hand back
// a failure code. Nothing is written, so nothing can be half-done.
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
    return smtNotImplemented(opts.command, opts)
}
