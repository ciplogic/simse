---
# `simse.md`: the project file, and module-declared source generators

Status: **the manifest half is implemented; the extension is not**. A root's `simse.md`
(`module:` entries) and a module's (`sourcegen:`) are read by the driver, and a module that
declares generators is a hard error naming it (`stress/manifest-modules`,
`stress/diagnostic-manifest-sourcegen`). Everything below is the shape we agreed on; the
"Open questions" at the end say what is still undecided. It is the manifest
`specs/modules.md` left deferred ("dependency manifests/files, versions, transitive
dependency resolution") plus the one key that gives a module the right to extend the compiler.

## The file

A project carries a **`simse.md` at its root**: the manifest that says which modules the
project is built from.

    module: src
    module: libs/json
    module: libs/units

    prose lines are ignored - the same rule `_res.md` uses, a line with no `:` carries no
    entry - so the file can explain itself, like this paragraph does.

And a module carries a **`simse.md` of its own**: metadata about that module. The one key
this spec fixes is `sourcegen`:

    sourcegen: true

    `json` ships the source generators a program that imports it gets.

The format is deliberately the one the tree already reads (`specs/resources.md`): markdown,
prose plus `key: value` entries, a repeated key meaning "one more of these" (`module:`), so
`Resources.kt`'s reader is the obvious implementation and no second notation enters the
project.

## What the keys mean

| file | key | meaning |
| --- | --- | --- |
| root `simse.md` | `module: <dir>` | a module of the project, relative to the file. The entry may repeat. A file with no `module:` entry leaves the root scanned whole - which is what a module's own manifest is |
| module `simse.md` | `sourcegen: true` | this module ships source generators (`cppsrc/sourcegen`'s kind), so a project using it must be compiled by a compiler that carries them |

A root with no `simse.md` behaves exactly as today: `--root <dir>` is the single module root.
The manifest is **additive**: nothing that works now stops working, and `--root` remains the
way a compiler's own build names its tree.

**A root with a manifest is scanned as exactly the modules it names** - the manifest is what
the root means, not a hint on top of the directory walk. That is what the compiler's own
`--root cppsrc` would get if `cppsrc/simse.md` were ever written: it names the project's root,
and the file in it names the modules. A root whose manifest lists no module (a file carrying
only a module's own keys) is scanned whole, so a manifest cannot empty a scan by accident.

## What `sourcegen: true` means

`impl_specs/generators.md` fixes what a generator *is*: a function over data
(`*SourceGenContext`, `Sections`, the AST nodes and resources) registered in the compiler's
table, one file per generator. It is compiled **into the compiler**, so a project that
imports such a module is compiled by a compiler that has been **extended** with the module's
generator sources:

1. The compiler finds the module's generators (the module declares them).
2. It builds an **extended compiler**: its own source tree *plus* the module's generator
   sources, as one compilation - "the compiler takes these modules as source gens too, as
   part of the files of the compiler".
3. It runs the project with that extended compiler.

A module's generator is **Simse**, and it **registers itself**: it is an ordinary `.kt` file
of the extended compiler's build, ending in one line that adds it to the table -

    val jsonGenRegistered: Bool = registerSourceGen("json", jsonGen, false, true)

- `registerSourceGen` (`cppsrc/sourcegen/SourceGen.kt`) appends to `sourceGenTable` and
  answers `true`, because a file-level static's initializer is an expression and every static
  has a type (`specs/statics.md`). Its arguments after the name are the two facts only the
  generator knows: whether a declaration of it keeps a prototype, and whether its receiver
  pattern is registered.
- The table has **no initializer**, and every generator - the built-in three and a module's
  alike - appends itself. Static initializers run in an order the language does not specify,
  so a table that one file *assigned* would drop a registration that ran before it; an append
  onto storage that starts empty cannot. The dispatcher looks a generator up by *name*, so
  the order the table ends up in is not observable either.
- Because the generator is Simse, the compiler needs no second build path for it: the module
is one more module of its own tree, transpiled and compiled by the very machinery the tree
already uses.

Three consequences are the point of doing it this way:

- **The generator boundary stays checkable.** A generator may touch the AST and the sections,
  and nothing else (`impl_specs/generators.md`, "What a generator may touch"). Because the
  generator is compiled *against the compiler it extends*, a generator that used something
  the compiler no longer has **fails to build** - a visible, early error - rather than
  misbehaving at run time. That is the argument for self-extension over any plugin ABI.
- **The cost is explicit and cached.** Extending the compiler is one compiler build (the
  compiler's own build is ~17 s: 16.6 s of C++ compile, 0.85 s of transpile). It happens
  once per (compiler, generator sources) pair and the result is cached; a project that
  imports no generator module pays nothing.
- **Failure is contained, and final.** A generator module that does not build is rolled back:
  the staged copy is removed (so the next compilation starts from the compiler's own tree
  again), and the project's compilation is **dropped** - not compiled without the generators
  it asked for. The report names the module and the command's output. A build that *succeeded*
  keeps its cache entry, which is what is read when a generator misbehaves ("so we can spot
  what's wrong later").

The extended compiler is a *different binary* from the plain one, and it does not extend
itself again: it is built with its generators already inside, and it is handed a marker
(a flag or an environment variable) that says so.

## The build command

Extending the compiler needs a build, and how a machine builds C++ is the one thing this
compiler must not guess: the manifest carries the command. It is a **convention command** -
a *template* with `|` placeholders, filled positionally the way the tree's own `fmtStr` fills
a format string (`cppsrc/rtl/rtl.kt`) - and it is run with `system()`, so it is portable, it
may be any command line a shell accepts, and its **exit code is the answer** (0 = the build
worked).

The compiler does the transpiling itself (it is the transpiler, and it knows how to run over
a tree), so the command's job is the C++ half: compile the file the compiler generated into
the executable the compiler asks for.

| placeholder | what it is |
| --- | --- |
| the first one | the generated C++ file (the extended tree's amalgamation) |
| the second one | the executable to write |

The command runs with the compiler's own home as the working directory, so a template names
the runtime and the include root literally - they are the distribution's fixed layout, not
something to substitute. A build of this repository would say:

    build: bun build.js --release --cpp | --exe |

(`build.js --cpp <file>` is exactly "compile this C++ file instead of regenerating", and it
brings the MSVC environment, the flags and the include root with
it.) A plain toolchain says the same thing without the build tool:

    build: g++ -std=c++20 -O2 -I. | -o |
    build: cl /nologo /std:c++20 /EHsc /O2 /I. | /Fe:|

Two consequences to keep in mind. The command is *not* a sandbox: it runs with the user's
privileges and whatever the shell accepts, which is the point (a template can call a build
tool, a container, a remote machine) and the reason it is a manifest entry rather than
something the compiler derives. And `system()`'s return value is not the exit code on every
platform (POSIX packs the status), so the RTL native that wraps it normalizes: 0 for success,
the program's exit code otherwise.

The command belongs to the **compiler's own** `simse.md` - it describes how *that*
distribution is built, not how the user's project is - with the project's manifest able to
override it for a machine the distribution does not know about. A compiler that has no
source tree next to it cannot extend itself, and a project that needs a generator module from
such a compiler is a hard error that says so.

## The cache

What an extension produces is cached in **`_simse`, in the parent of the project's root
folder**: a project rooted at `.../json-demo/src` caches at `.../json-demo/_simse`. Beside
the project, never inside it - the sources the compiler scans and the artifacts it writes
stay two different things.

    _simse/
        <module>/            one directory per generator module that was merged
            cppsrc/          the staged compiler tree: the compiler's own files + the module's
            simse_out.cpp    the amalgamation the compiler generated from them
            simse-self.exe   the extended compiler
            self.md          what was staged - the module, its sources, the command, the fingerprint

- **Keyed, so a stale entry is never reused.** An entry is valid only for the compiler that
  made it and the generator sources it was staged from; anything else stages a new entry.
  (What the key is - a hash of the sources, their identity by size and timestamp - is one of
  the open questions below.)
- **A cache, so deleting it is always safe.** Nothing outside it is needed to build an entry
  again, and no project's own file is written to. A failed build removes its entry, which is
  the rollback "we delete it" from the sketch - and it is left in place to be inspected when
  the build *succeeds* but the generator misbehaves.
- **A build artifact, not source.** It belongs in `.gitignore` (the repository's now names
  `_simse/`), which matters where the root's parent is a repository - the shape `--root
  cppsrc` has, whose parent is this repository itself.

The *root folder* is the one the compilation already has: `--root <dir>`, the directory the
manifest sits in, or `.` when the compiler is left to scan the working directory. A
compilation given bare input files and no root has none, so a project that wants a generator
module is one of the first two shapes - asking for one without a root is a diagnostic, not a
licence to cache somewhere arbitrary.

## Staging

Two steps, the first useful without the second:

- ~~**`simse.md` alone**~~ **implemented** - the project file and module manifests: the
  module roots come from the manifest, `--root` still works, and a module's `sourcegen` key
  is read and reported. This is the deferred half of `specs/modules.md` and needed no
  toolchain (`stress/manifest-modules`, `stress/diagnostic-manifest-sourcegen`;
  `Driver.kt`'s `manifestValues`/`driverExpandRoot`).
- **The extension** - staging the generators, building the extended compiler, caching it,
  rolling back on failure, re-running the project. This needs the `build:` command to work,
  i.e. a C++ toolchain behind a `system()` call.

The step list lives in `impl_specs/roadmap.md` (T31).

## Open questions

1. ~~**What language are a module's generators written in?**~~ **answered: Simse.** A module's
   generator is a `.kt` file of the extended compiler's build. The compiler then needs no
   second build path for it: it is one more module of the compiler's own tree, transpiled
   and compiled by the machinery the tree already has. `.cpp` is not a form a module's
   generators take.
2. ~~**How does a module's generator get registered?**~~ **answered: self-registration** -
   one line at the end of the generator's own file (see "What `sourcegen: true` means"). The
   built-in generators register the same way now, which is what makes the mechanism proven
   rather than proposed: `SourceGen.kt`'s table has no initializer of its own, and the
   unspecified order of static initializers cannot matter because a registration appends onto
   storage that starts empty.
3. ~~**How is the extended compiler built?**~~ **answered**: a convention command in the
   manifest (`build:`), a `|` template run with `system()`, its exit code deciding the
   outcome (see "The build command"). Still to fix: whether the command's two placeholders
   are the generated C++ and the executable (recommended, above) or the staged tree root and
   the executable, and whether the override lives in the project's manifest or the module's.
4. ~~**Where does the extended tree live?**~~ **answered**: the `_simse` cache, in the parent
   of the project's root folder (see "The cache") - not the checked-in tree, which a copy
   under `cppsrc/` would silently become part of, and not a project-internal directory,
   which a scan of the module roots would then see.
5. **What is the cache key, and when is it invalidated?** Recommended: the compiler's own
   identity (its binary, or its sources when built from source) plus the sorted list of
   generator module sources - the *same* pair the extended compiler depends on, since a
   change to either is a different compiler.
6. ~~**Hard error or skip when a generator module fails to build?**~~ **answered: a hard
   error that drops the compilation**, not a skip - a program that asked for `json` must not
   compile silently without it.
7. **Is the compiler's own tree a project with a manifest?** It needs one for the `build:`
   command; the open half is whether its own `sourcegen` module is marked. If it were, a
   project importing the compiler's tree as a module would try to re-add generators the
   compiler already carries. Recommended: the built-in generators are *built in* - the
   compiler's own manifest carries the module list and the `build:` command, and marks
   nothing `sourcegen: true`.
8. ~~**Does the manifest replace `--root`, or add to it?**~~ **answered, and implemented
   differently from the first recommendation**: a root *with* a manifest is scanned as
   exactly the modules it names (so `--root cppsrc` plus a `cppsrc/simse.md` is one coherent
   thing, not a conflict), and a root *without* one is scanned whole, as today. A manifest
   that names no module leaves the root whole, so it cannot empty a scan by accident.

The implementation notes so far: staging a tree needs **directory creation** (a native, or
`simse_writeFile` creating parents) and running the command needs **`system()`** (a native
returning the normalized exit code, as above); the copy itself is `listFiles` +
`readWholeFile` + `writeFile`, which the RTL has (`cppsrc/rtl/fs.kt`, whose C++ is the
`fileio` section of `cppsrc/rtl/_res.md`).
