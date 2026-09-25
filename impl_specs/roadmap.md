# Roadmap to a self-hosted Simse

Status: planning baseline. Subject to revision as tasks are executed.

## Purpose

The executable breakdown of `impl_specs/plan-to-selfhost.md` and
`impl_specs/transpilation.md`; each item is a file under `impl_specs/tasks/`.

## Current state

The compiler is written in Simse (`cppsrc/**/*.kt`) and self-hosts: `cppsrc/simse_bootstrap.cpp`
is one amalgamation of those sources, and the compiler it builds transpiles them back into that
same file byte for byte (`bun tools/bootstrap.js`). `build.js` / `build.bat` drive the build.
Per-component status: `impl_specs/capability-matrix.md`.

## Conventions

- Status values: `Not started`, `In progress`, `Blocked`, `Done`.
- Each task file uses the same sections: Goal, Motivation, Scope (In/Out), Deliverables,
  Acceptance criteria, Steps, Risks / notes, References.
- Prefer small tasks that fit one working session; split a task if it grows.
- A task is `Done` only when its acceptance criteria are literally checked off and the
  project still builds and passes the harness.
- When a task reveals a new requirement, record it in `specs/` or in a new task file
  rather than only in code.
- A completed task file is deleted once its work is verified; the capability matrix and
  git history retain the detail.

## Tasks

| ID  | Task | Depends on | Status |
| --- | --- | --- | --- |
| T28 | Per-instantiation generators (`@Json`, enum-to-string, int-to-enum) | T27 | Not started |
| T29 | The RTL's generated C++ is a resource (`strtable`, `timeops`, `listops`, `spanOf`) | T26, T27 | Done |
| T30 | The generators are a package of their own (`cppsrc/sourcegen`) | T26, T27 | Done |
| T31 | `simse.md`: the project file, and module-declared source generators | T30 | In progress (manifest done; the extension not started) |

### T26 - Generators: attributes + `@SmGen` + `Sections` - **Done**

Spec of record: `impl_specs/generators.md`; log: `impl_specs/capability-matrix.md`.

- `@Identifier` token (`cppsrc/lex/Scanner.kt`), attribute parsing and the body-less rules
  (`cppsrc/parser/Parser.kt`), AST `Attribute`/`Generator`/`GeneratorArgs`; diagnostics
  `stress/diagnostic-attribute-{token,arg,body}`, `stress/diagnostic-bodyless-method`.
- `native` baseline `stress/smgen-native` (`native("simse_str_trim")`, `expected.cpp` golden)
  and its `@SmGen` twin `stress/smgen-cpp` (`@SmGen("cpp", "simse_str_trim")`); the two
  spellings fill the same attributes, and `bun tools/smgen.js` asserts their amalgamations are
  byte-identical (modulo the fixture path). The parser synthesizes the attribute for
  `native(...)`, so emissions are unchanged.
- `Sections` (`cppsrc/sourcegen/Sections.kt`): get-or-create that appends a new section at the
  end, last-write-wins `add`, render text-then-items; byte-neutral.
- `@SmGen("res", section)` reads the compiler's own resources: `stress/smgen-res` (generated
  declaration and definition), `stress/smgen-res-collision` (the double-creation hazard).
- `cppsrc/simse_bootstrap.cpp` refreshed; `bun tools/bootstrap.js` reports the fixed point byte
  for byte.
- `spanOf`: declaration and definition moved from `cppsrc/rtl/span.hpp` into
  `cppsrc/rtl/_res.md`, referenced by `@SmGen("res", "spanOf")` in `cppsrc/rtl/Span.kt`; call
  sites unchanged (`simse_spanOf(...)`).

### T27 - Generated Simse sources - **Done**

A `@SmGen("kt", section)` declaration's implementation is Simse source, read from
`<section>:source` (the program's own resources first, then the compiler's), joined by the
driver into one module named `<generated>/kt.kt`, and compiled with the program - parsed,
checked by `analyze`, emitted after it. The module is package `rtl`, so a bare name is the
symbol a call reaches and the generated function carries the declaration's name; nothing is
emitted for the declaration itself, not even a prototype. Verified by `stress/smgen-kt`
(declaration in `fixtures`, source in the case's `_res.md`, call site unchanged) and
`stress/diagnostic-smgen-kt-missing` (the driver names the missing section). Spec of
record: `impl_specs/generators.md`.

### T28 - Per-instantiation generators - Not started

Model (`impl_specs/generators.md`, "Deferred"): a generator whose output is *built in code*
rather than read from a resource, invoked per instantiation the emitter reached, with a mangled
symbol the emitter chose, emitting the transitive closure of what the type needs, with the
serializer written in Simse. Makes `@Json` (and enum-to-string, int-to-enum as trailing
sections) possible. Also deferred: attributes on things other than methods, user-supplied
generators. See `specs/attributes.md`.

### T29 - The RTL's generated C++ is a resource - **Done**

Spec of record: `impl_specs/generators.md`.

- **Section order** (`cppsrc/sourcegen/Sections.kt`): `support` (text the preamble needs),
  `profile`, `strings`, `resources`, `forward` (generated declarations), then
  types/statics/prototypes/init/bodies; each block renders with a blank line before it.
- **Lookup is the tree's own resources first** (`Emitter.resText`/`resHas`), the compiler's
  table second (while the compiler is built, the tree's `_res.md` is the newer one).
- **`@SmGen("res", section, symbol)`**: a *shared* section (no `symbol:`) holds a header's
  worth of functions, so each declaration names its symbol; `section:emit` = `always` marks
  text emitted for every program.
- **The generator pass runs after every body**, so its reachability rule sees what the emitter
  spelled itself (the entry point's `simse_list_append`).
- **The parser fills `NativeSymbol` for a `res` declaration**, which keeps `listOf<T>` the list
  literal.
- **`cppsrc/rtl/_res.md`** holds `strtable`, `timeops` (`emit: always`), `listops`, `dictops`,
  `strops` (shared), `spanOf`; `strtable.hpp`, `timeops.hpp`, `listops.hpp`, `dictops.hpp`,
  `strops.hpp` are deleted. `rtl.kt`'s declarations are `@SmGen("res", ...)`; no prelude
  string/list/dictionary operation is `native`.
- **A call records the declaration's symbol** (`collectNames`): a program naming an RTL symbol
  directly (`native("simse_str_trim") fun trimmedText(...)`) links only when the section is
  reached, and the call site spells only the *symbol* (`stress/smgen-native`).
- **Cases**: `stress/smgen-res-program` (a program's own `_res.md` supplies the text),
  `stress/main-args` (an emitter-spelled symbol is reached).

Verified: `bun tools/stress.js` 56/56, `bun tools/smgen.js` byte-identical, bootstrap fixed
point (`bun tools/bootstrap.js`).

Hand-written C++ (deferred), in order of ease: `filestream.hpp` (the `FileStream` struct and
its fields - its method bodies are the `filestream` section of `_res.md`; only the
constructor-like `open` was ever in `fileio`) and the type core (`types.hpp`, `containers.hpp`,
`smstring.hpp`, `smdictionary.hpp`, `span.hpp`, `strview.hpp`, `strsmallvector.hpp`,
`variant2.hpp`, `optional.hpp`, `result.hpp`, `functional.hpp`, `xml.hpp`, `astxml.hpp`), which
the amalgamation is compiled *against* and which needs language features that do not exist yet
(statics in an object, a ref-counted layout).

### T30 - The generators are a package of their own - **Done**

The generators are the one part of the compiler meant to be written by a *program's
author*; they are their own package, with a boundary documented as a rule (spec of record:
`impl_specs/generators.md`, "The generator table").

- **`cppsrc/sourcegen/`** - `Sections.kt` (the sink), `GenTypes.kt` (`SourceGenContext`,
  `SourceGenTransform`, `FullCompiledState`, the `OnSourceGen` typealias), one file per
  generator (`CppGen.kt`, `ResGen.kt`, `KtGen.kt`), and `SourceGen.kt` - the manager:
  `addSourceGen`/`makeSourceGens` the way `lex/Scanner.kt` builds its token rules,
  `sourceGenFind`/`sourceGenHas`, `sourceGenDeclare`/`sourceGenReparseSource`/`sourceGenEmit`.
- **A generator is a lambda over `*SourceGenContext`** - data in, a transform out - carrying
  the AST node, its arguments and symbol, the file, and pointers to the resources and the sink.
- **The three phases are the manager's**: `Declare` (resolve the symbol a call reaches),
  `Reparse` (hand back Simse source), `Emit` (place text once the reach set is known, plus
  once per generator for the program itself).
- **`Sections` and the generators call nothing from the compiler's stages**: a generator reads
  and writes AST nodes, resources and the sink, and cannot break when a compiler API changes.
- **`codegen` keeps only the caller**: `Emitter.sections` is a `*Sections`, `collect` asks
  `sourceGenHas`/`sourceGenDeclare`, `run` asks `sourceGenEmit`, and the driver asks
  `sourceGenBegin`/`sourceGenReparseSource`.

Trap: in Simse `*p` where `p: *Sections` *reads through*, so `sourceGenEmit(*this.sections,
...)` filled a copy of the sink and emitted a file with no generated text. The pointer is
passed, never dereferenced.

Verified: `bun tools/bootstrap.js` fixed point (after one refresh of
`cppsrc/simse_bootstrap.cpp`), `bun tools/stress.js` 56/56, `bun tools/smgen.js` 1/1.

### T31 - `simse.md`: module-declared source generators - Not started

Spec: `specs/simse-md.md`. A project carries a `simse.md` naming its modules; a module
may carry one of its own saying `sourcegen: true`; a project importing such a module is
compiled by a compiler **extended** with that module's generator sources - "the compiler
takes these modules as source gens too, as part of the files of the compiler".

Settled: generators are **Simse** (`.kt`), they **self-register**
(a file-level static whose initializer adds it to the table), the extended compiler is built
by a **convention command** in the compiler's own manifest (`build: bun build.js --release
--cpp | --exe |` - a `|` template filled positionally, run with `system()`, its exit code
the answer), its tree is staged in the **`_simse`** cache in the parent of the project's root,
and a self-compilation that fails **drops the compilation** rather than compiling without the
generators.

Steps, in order:

1. ~~**Self-registration**~~ **done** - the table has no initializer of its own
   (`var sourceGenTable: List<SourceGenerator>`), and each of the three built-in generators
   registers itself from its own file (`val cppGenRegistered: Bool = registerSourceGen("cpp",
   cppGen, true, true)`, `SourceGen.kt`'s `registerSourceGen`). A registration is an *append*
   onto storage that starts empty, so the initialization pass's order cannot matter. Verified:
   the compiler still builds itself, `bun tools/stress.js` 58/58, bootstrap fixed point after
   one refresh.
2. ~~**The manifest**~~ **done, minus the extension** - `simse.md` at a root
   (`module: <dir>` entries, repeated) and in a module (`sourcegen: true`), read by the
   driver's own small reader (`manifestValues`/`driverExpandRoot`, `Driver.kt`) in the
   resource idiom: prose lines ignored, `key: value`, a repeated key repeating. A root with
   a manifest is scanned as exactly the modules it names; a root without one is scanned
   whole (this compiler's own `--root cppsrc`). A module declaring `sourcegen: true` is a
   **hard error** naming it:
   `stress/manifest-modules` and `stress/diagnostic-manifest-sourcegen` pin both halves.
3. **Two natives** - `system()` (normalizing the exit code, since POSIX packs `system()`'s
   return) and directory creation for staging; the copy itself is `listFiles` +
   `readWholeFile` + `writeFile`, which the RTL already has.
4. **The extended tree** - stage the compiler's own sources plus the module's generators
   under `_simse` (never in `cppsrc/`, never inside a scanned root). The module's files are
   ordinary Simse modules of the compiler's build, and their registration statics are the
   whole registration step - nothing is synthesized. The entry is keyed on the compiler and
   the generator sources, and deleting `_simse` is always safe.
5. **The extended compiler** - transpile the staged tree, run the `build:` template with
   the generated C++ and the output executable, cache the result under a fingerprint (the
   compiler's identity plus the generator sources), and run the project with it; a build
   that fails removes the staged copy and **drops the compilation**, reporting the module
   and the command's output.
6. **The recursion guard** - the extended compiler carries its generators and must not
   extend itself again.
7. **A regression case** - a `stress/` project with a module shipping a generator whose
   output the program's own `expected.cpp`/`expected.stdout` pin, plus one whose generator
   does not build (the rollback must drop the compilation and leave the compiler usable).

Steps 1-2 done; spec: `specs/simse-md.md`.

## Non-goals (for now)

- An interpreter or VM backend, an optimizer, or multiple backends.
- A full standard library.
- Performance work on the compiler itself.
- A complete FFI; only the narrow `native fun` fallback.
