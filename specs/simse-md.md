---
# `simse.md`: the project file, and module-declared source generators

Status: **the manifest half is implemented; the extension is not**. A root's `simse.md`
(`module:` entries) and a module's (`sourcegen:`) are read by the driver, and a module that
declares generators is a hard error naming it (`stress/manifest-modules`,
`stress/diagnostic-manifest-sourcegen`). This is the manifest `specs/modules.md` left
deferred plus the right for a module to extend the compiler; the "Open questions" at the
end say what is still undecided.

## The file

A project carries a **`simse.md` at its root**: the manifest that says which modules the
project is built from.

    module: src
    module: libs/json
    module: libs/units

    prose lines are ignored - a line with no `:` carries no entry - so the file can explain
    itself.

A module carries a **`simse.md` of its own**: metadata about that module. The one key this
spec fixes is `sourcegen`:

    sourcegen: true

The format is the one `_res.md` already reads (`specs/resources.md`): markdown, prose plus
`key: value` entries, a repeated key meaning "one more of these" (`module:`), so
`Resources.kt`'s reader is the implementation.

## What the keys mean

| file | key | meaning |
| --- | --- | --- |
| root `simse.md` | `module: <dir>` | a module of the project, relative to the file. The entry may repeat. A file with no `module:` entry leaves the root scanned whole - which is what a module's own manifest is |
| module `simse.md` | `sourcegen: true` | this module ships source generators (`cppsrc/sourcegen`'s kind), so a project using it must be compiled by a compiler that carries them |

A root with no `simse.md` behaves as before: `--root <dir>` is the single module root. The
manifest is additive: nothing that works now stops working, and `--root` remains the way a
compiler's own build names its tree.

**A root with a manifest is scanned as exactly the modules it names** - the manifest is what
the root means, not a hint on top of the directory walk. A root whose manifest lists no
module (a file carrying only a module's own keys) is scanned whole, so a manifest cannot
empty a scan by accident.

## What `sourcegen: true` means

`impl_specs/generators.md` fixes what a generator *is*: a function over data
(`*SourceGenContext`, `Sections`, the AST nodes and resources) registered in the compiler's
table, one file per generator. It is compiled into the compiler, so a project that imports
such a module is compiled by a compiler **extended** with the module's generator sources:

1. The compiler finds the module's generators (the module declares them).
2. It builds an extended compiler: its own source tree plus the module's generator sources,
   as one compilation.
3. It runs the project with that extended compiler.

A module's generator is Simse, and it registers itself: it is an ordinary `.kt` file of the
extended compiler's build, ending in one line that adds it to the table -

    val jsonGenRegistered: Bool = registerSourceGen("json", jsonGen, false, true)

- `registerSourceGen` (`cppsrc/sourcegen/SourceGen.kt`) appends to `sourceGenTable` and
  answers `true`, because a file-level static's initializer is an expression and every static
  has a type (`specs/statics.md`). Its arguments after the name are the two facts only the
  generator knows: whether a declaration of it keeps a prototype, and whether its receiver
  pattern is registered.
- The table has no initializer, and every generator - the built-in three and a module's alike
  - appends itself. Static initializers run in an order the language does not specify, so a
  table one file *assigned* would drop a registration that ran before it; an append onto
  storage that starts empty cannot. The dispatcher looks a generator up by name, so the
  table's order is not observable either.
- The generator being Simse, the compiler needs no second build path for it: the module is
  one more module of its own tree, transpiled and compiled by the machinery the tree already
  uses.

- **The generator boundary stays checkable.** A generator may touch the AST and the sections,
  and nothing else (`impl_specs/generators.md`, "What a generator may touch"). A generator
  that used something the compiler no longer has fails to build, an early error rather than
  run-time misbehavior.
- **The cost is explicit and cached.** Extending the compiler is one compiler build (the
  compiler's own build is ~17 s: 16.6 s of C++ compile, 0.85 s of transpile), once per
  (compiler, generator sources) pair; a project that imports no generator module pays
  nothing.
- **Failure is contained.** A generator module that does not build is rolled back: the
  staged copy is removed, and the project's compilation is dropped, not compiled without the
  generators it asked for. The report names the module and the command's output. A build that
  succeeded keeps its cache entry, read when a generator misbehaves.

The extended compiler is a *different binary* from the plain one, and it does not extend
itself again: it is built with its generators already inside, and it is handed a marker
(a flag or an environment variable) that says so.

## The build command

Extending the compiler needs a build, and the manifest carries the command. It is a
convention command - a template with `|` placeholders, filled positionally like `fmtStr`
fills a format string (`cppsrc/rtl/rtl.kt`) - run with `system()`, so it may be any command
line a shell accepts, and its exit code is the answer (0 = the build worked).

The compiler does the transpiling itself, so the command's job is the C++ half: compile the
file the compiler generated into the executable it asks for.

| placeholder | what it is |
| --- | --- |
| the first one | the generated C++ file (the extended tree's amalgamation) |
| the second one | the executable to write |

The command runs with the compiler's own home as the working directory, so a template names
the runtime and the include root literally. A build of this repository would say:

    build: bun build.js --release --cpp | --exe |

(`build.js --cpp <file>` is exactly "compile this C++ file instead of regenerating".) A
plain toolchain says the same thing:

    build: g++ -std=c++20 -O2 -I. | -o |
    build: cl /nologo /std:c++20 /EHsc /O2 /I. | /Fe:|

The command is not a sandbox: it runs with the user's privileges and whatever the shell
accepts, which is the reason it is a manifest entry rather than something the compiler
derives. `system()`'s return value is not the exit code on every platform (POSIX packs the
status), so the RTL native that wraps it normalizes: 0 for success, the program's exit code
otherwise.

The command belongs to the compiler's own `simse.md` - it describes how that distribution is
built, not how the user's project is - with the project's manifest able to override it. A
compiler with no source tree next to it cannot extend itself, and a project that needs a
generator module from such a compiler is a hard error that says so.

## The cache

What an extension produces is cached in **`_simse`, in the parent of the project's root
folder**: a project rooted at `.../json-demo/src` caches at `.../json-demo/_simse`, beside
the project and never inside it.

    _simse/
        <module>/            one directory per generator module that was merged
            cppsrc/          the staged compiler tree: the compiler's own files + the module's
            simse_out.cpp    the amalgamation the compiler generated from them
            simse-self.exe   the extended compiler
            self.md          what was staged - the module, its sources, the command, the fingerprint

- **Keyed, so a stale entry is never reused.** An entry is valid only for the compiler that
  made it and the generator sources it was staged from; anything else stages a new entry.
- **A cache, so deleting it is always safe.** Nothing outside it is needed to build an entry
  again, and no project's own file is written to. A failed build removes its entry; it is
  left in place to be inspected when the build succeeds but the generator misbehaves.
- **A build artifact, not source.** It belongs in `.gitignore` (the repository's names
  `_simse/`).

The root folder is the one the compilation already has: `--root <dir>`, the directory the
manifest sits in, or `.` when the compiler scans the working directory. A compilation given
bare input files and no root has none, so a project that wants a generator module is one of
the first two shapes; asking for one without a root is a diagnostic.

## Staging

Two steps:

- **`simse.md` alone - implemented**: the project file and module manifests. The module roots
  come from the manifest, `--root` still works, and a module's `sourcegen` key is read and
  reported. This is the deferred half of `specs/modules.md` and needs no toolchain
  (`stress/manifest-modules`, `stress/diagnostic-manifest-sourcegen`; `Driver.kt`'s
  `manifestValues`/`driverExpandRoot`).
- **The extension**: staging the generators, building the extended compiler, caching it,
  rolling back on failure, re-running the project. This needs the `build:` command to work,
  i.e. a C++ toolchain behind a `system()` call.

The step list lives in `impl_specs/roadmap.md` (T31).

## Open questions

1. **What language are a module's generators written in?** Answered: **Simse**, a `.kt`
   file of the extended compiler's build.
2. **How does a module's generator get registered?** Answered: **self-registration**, one
   line at the end of the generator's own file. The built-in generators do the same, and the
   unspecified order of static initializers cannot matter because a registration appends
   onto storage that starts empty.
3. **How is the extended compiler built?** Answered: a convention command in the manifest
   (`build:`), a `|` template run with `system()`, its exit code deciding the outcome. Still
   to fix: whether the command's two placeholders are the generated C++ and the executable
   (recommended) or the staged tree root and the executable, and whether the override lives
   in the project's manifest or the module's.
4. **Where does the extended tree live?** Answered: the `_simse` cache, in the parent of the
   project's root folder.
5. **What is the cache key, and when is it invalidated?** Recommended: the compiler's own
   identity plus the sorted list of generator module sources.
6. **Hard error or skip when a generator module fails to build?** Answered: a hard error
   that drops the compilation, not a skip.
7. **Is the compiler's own tree a project with a manifest?** It needs one for the `build:`
   command; the open half is whether its own `sourcegen` module is marked. Recommended: the
   built-in generators are built in.
8. **Does the manifest replace `--root`, or add to it?** Answered: a root *with* a manifest
   is scanned as exactly the modules it names; a root *without* one is scanned whole, so a
   manifest that names no module cannot empty a scan.

The implementation notes so far: staging a tree needs **directory creation** (a native, or
`simse_writeFile` creating parents) and running the command needs **`system()`** (a native
returning the normalized exit code, as above); the copy itself is `listFiles` +
`readWholeFile` + `writeFile`, which the RTL has (`cppsrc/rtl/fs.kt`, whose C++ is the
`fileio` section of `cppsrc/rtl/_res.md`).
