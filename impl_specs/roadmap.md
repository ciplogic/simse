# Roadmap to a self-hosted Simse

Status: planning baseline. Subject to revision as tasks are executed.

## Purpose

This is the concrete, executable breakdown of `impl_specs/plan-to-selfhost.md`
and the staged pipeline in `impl_specs/transpilation.md`. Each outstanding work
item lives in its own file under `impl_specs/tasks/` and has a status,
dependencies, scope, deliverables, acceptance criteria, and steps. Completed
tasks are pruned once verified; the capability matrix and git history retain
that detail.

## Current state

The compiler is written in Simse (`cppsrc/**/*.kt`) and self-hosts: the
published `cppsrc/simse_bootstrap.cpp` is one amalgamation of those sources, and
the compiler it builds transpiles them back into that same file byte for byte
(`bun tools/bootstrap.js`). `build.js` / `build.bat` drive the build, and the
stress corpus keeps the emitted behavior honest. For per-component and
per-feature status, see `impl_specs/capability-matrix.md`.

## Conventions

- Status values: `Not started`, `In progress`, `Blocked`, `Done`.
- Each task file uses the same sections: Goal, Motivation, Scope (In/Out),
  Deliverables, Acceptance criteria, Steps, Risks / notes, References.
- Prefer small tasks that fit one working session; split a task if it grows.
- A task is `Done` only when its acceptance criteria are literally checked off
  and the project still builds and passes the harness.
- When a task reveals a new requirement, record it in the relevant spec
  (`specs/`) or in a new task file rather than leaving it only in code.
- A completed task file is deleted once its work is verified; the capability
  matrix and git history retain the detail.

## Tasks

| ID  | Task | Depends on | Status |
| --- | --- | --- | --- |
| T28 | Per-instantiation generators (`@Json`, enum-to-string, int-to-enum) | T27 | Not started |
| T29 | The RTL's generated C++ is a resource (`strtable`, `timeops`, `listops`, `spanOf`) | T26, T27 | Done |
| T30 | The generators are a package of their own (`cppsrc/sourcegen`) | T26, T27 | Done |
| T31 | `simse.md`: the project file, and module-declared source generators | T30 | In progress (manifest done; the extension not started) |

### T26 - Generators: attributes + `@SmGen` + `Sections` - **Done**

Each step is verified; `impl_specs/generators.md` is the spec of record and
`impl_specs/capability-matrix.md` the log.

1. **Parse-only** - the `@Identifier` token (`cppsrc/lex/Scanner.kt`), attribute parsing
   and the body-less rules (`cppsrc/parser/Parser.kt`), the AST's
   `Attribute`/`Generator`/`GeneratorArgs`, and four cases pinning the new
   diagnostics: `stress/diagnostic-attribute-{token,arg,body}` and
   `stress/diagnostic-bodyless-method`.
2. **`native` baseline** - `stress/smgen-native` (`native("simse_str_trim")`), with an
   `expected.cpp` golden.
3. **`@SmGen` twin** - `stress/smgen-cpp`, the same program written as
   `@SmGen("cpp", "simse_str_trim")`.
4. **Machinery** - the two spellings fill the same attributes, and `bun tools/smgen.js`
   asserts their amalgamations are byte-identical (modulo the fixture path).
5. **`native` as sugar** - the parser synthesizes the attribute for `native(...)`;
   emissions unchanged.
6. **`Sections`** (`cppsrc/codegen/CgSections.kt`, now `cppsrc/sourcegen/Sections.kt`) -
   get-or-create that appends a new section at the end, last-write-wins `add`, render
   text-then-items. Routing the emitter through it is byte-neutral: the compiler before and
   after it transpiles `cppsrc` to identical C++.
7. **Resource-backed generator** - `@SmGen("res", section)` reads the compiler's own
   resources: `stress/smgen-res` (the generated declaration and definition) and
   `stress/smgen-res-collision` (the documented double-creation hazard).
8. **Bootstrap** - `cppsrc/simse_bootstrap.cpp` refreshed from the new compiler;
   `bun tools/bootstrap.js` reports the fixed point byte for byte. No hand-patch was
   needed: the refreshed file carries the `@` scanner and parser, and the compiler's
   own sources only gained `@` after step 1 made a compiler that understands it.
9. **Port `spanOf`** - the declaration and the definition moved from
   `cppsrc/rtl/span.hpp` into `cppsrc/rtl/_res.md`, referenced by
   `@SmGen("res", "spanOf")` in `cppsrc/rtl/Span.kt`. Call sites are unchanged
   (`simse_spanOf(...)`) and the whole corpus is green.

### T27 - Generated Simse sources - **Done**

A `@SmGen("kt", section)` declaration's implementation is Simse source, read from
`<section>:source` (the program's own resources first, then the compiler's), joined by the
driver into one module under the synthetic name `<generated>/kt.kt`, and compiled with the
program - parsed, checked by `analyze`, emitted after it. The generated module is package
`rtl`, so a bare name is the symbol a call reaches and the generated function carries the
declaration's own name; nothing is emitted for the declaration itself, not even a
prototype.

Verified by `stress/smgen-kt` (end to end: declaration in `fixtures`, source in the
case's `_res.md`, call site unchanged) and `stress/diagnostic-smgen-kt-missing` (the
driver names the missing section). `impl_specs/generators.md` is the spec of record.

### T28 - Per-instantiation generators - Not started

The model is in `impl_specs/generators.md` ("Deferred"): a generator whose output is
*built in code* rather than read from a resource, invoked per instantiation the emitter
actually reached, with a mangled symbol the emitter chose, emitting the transitive
closure of what the type needs - and the serializer itself written in Simse. That is the
step that makes `@Json` (and enum-to-string, int-to-enum as trailing sections) possible.
Also deferred: attributes on things other than methods, and user-supplied generators.

See `impl_specs/generators.md` and `specs/attributes.md`.

### T29 - The RTL's generated C++ is a resource - **Done**

What a header held is a `_res.md` section now (`impl_specs/generators.md` is the spec of
record). Steps, all verified:

1. **The sections the assembly needs** (`cppsrc/sourcegen/Sections.kt`): `support` (text the
   preamble needs), `profile`, `strings`, `resources`, `forward` (generated declarations),
   then types/statics/prototypes/init/bodies. Each block renders with a blank line before
   it, so a generated text reads as its own block.
2. **The lookup is the tree's own resources first** (`Emitter.resText`/`resHas`), the
   compiler's table second - the rule `kt` already used. This is what lets the RTL's C++ be
   a resource at all: while the compiler is built, the tree's `_res.md` is the newer one.
3. **`@SmGen("res", section, symbol)`** - a *shared* section (one with no `symbol:`) holds
   a header's worth of functions, so each declaration names its symbol; `section:emit` =
   `always` marks text the compiler emits for every program.
4. **The generator pass runs after every body**, because its reachability rule must see
   what the emitter spelled itself (the entry point's `simse_list_append`).
5. **The parser fills `NativeSymbol` for a `res` declaration** - a pass that reads the
   declaration without the emitter's tables reads the symbol there, which is what keeps
   `listOf<T>` the list literal.
6. **The RTL text moved** into `cppsrc/rtl/_res.md` - `strtable` and `timeops`
   (`emit: always`), `listops`, `dictops` and `strops` (shared) and `spanOf` - and
   `strtable.hpp`, `timeops.hpp`, `listops.hpp`, `dictops.hpp` and `strops.hpp` are deleted
   (their `#include`s went with them). `rtl.kt`'s declarations are `@SmGen("res", ...)`, and
   none of the prelude's string/list/dictionary operations is `native` any more.
7. **A call records the symbol of the declaration it names** (`collectNames`), not just a
   call on a type name: a program that names an RTL symbol directly
   (`native("simse_str_trim") fun trimmedText(...)`) linked while the C++ was in a header
   and stopped when it moved into a section - the section is emitted only when it is
   reached, and the only thing the program's call site spells is the *symbol*
   (`stress/smgen-native` is the case).
8. **Regression cases**: `stress/smgen-res-program` (a program's own `_res.md` supplies the
   text) and `stress/main-args` (an emitter-spelled symbol is reached).

Verified: `bun tools/stress.js` 56/56, `bun tools/smgen.js` byte-identical, and the
bootstrap fixed point holds (`bun tools/bootstrap.js`).

Still hand-written C++, in this order of ease: `filestream.hpp` (the `FileStream`
struct and its methods; only the free `open` moved into the `fileio` section with the
rest of the platform's C++) and the type core (`types.hpp`, `containers.hpp`,
`smstring.hpp`, `smdictionary.hpp`, `span.hpp`, `strview.hpp`, `strsmallvector.hpp`,
`optional.hpp`, `result.hpp`, `functional.hpp`, `xml.hpp`, `astxml.hpp`), which is what the
amalgamation is compiled *against* and which needs language features that do not exist yet
(statics in an object, a ref-counted layout), so it stays.

### T30 - The generators are a package of their own - **Done**

The generators lived inside `codegen`, which is what a generator must not be: they are the
one part of the compiler meant to be written by a *program's author*, so they got their own
package and a boundary that can be documented as a rule rather than trusted (the spec of
record is `impl_specs/generators.md`, "The generator table").

1. **`cppsrc/sourcegen/`** - `Sections.kt` (the sink, moved out of `codegen`), `GenTypes.kt`
   (`SourceGenContext`, `SourceGenTransform`, `FullCompiledState`, the `OnSourceGen`
   typealias), one file per generator (`CppGen.kt`, `ResGen.kt`, `KtGen.kt`), and
   `SourceGen.kt` - the manager: `addSourceGen`/`makeSourceGens` the way
   `lex/Scanner.kt` builds its token rules, `sourceGenFind`/`sourceGenHas`,
   `sourceGenDeclare`/`sourceGenReparseSource`/`sourceGenEmit`.
2. **A generator is a lambda over `*SourceGenContext`** - data in, a transform out - and
   the context carries what it may touch: the AST node, its arguments and symbol, the file,
   and pointers to the resources and the sink.
3. **The three phases are the manager's**, not a generator's: `Declare` (resolve the symbol
   a call reaches), `Reparse` (hand back Simse source), `Emit` (place text once the reach
   set is known, plus once per generator for the program itself).
4. **`Sections` and the generators call nothing from the compiler's stages** - the rule the
   package exists for: a generator reads and writes AST nodes, resources and the sink, and
   cannot break when a compiler API changes.
5. **`codegen` keeps only the caller**: `Emitter.sections` is a `*Sections`, `collect`
   asks `sourceGenHas`/`sourceGenDeclare`, `run` asks `sourceGenEmit`, and the driver asks
   `sourceGenBegin`/`sourceGenReparseSource`.

One trap found and paid for, worth remembering: in Simse `*p` where `p: *Sections` *reads
through*, so `sourceGenEmit(*this.sections, ...)` filled a copy of the sink and emitted a
file with no generated text at all. The pointer is passed, never dereferenced.

Verified byte-neutral: the refactored compiler and the previous one transpile `cppsrc` to
*identical* C++ (`bun tools/bootstrap.js` fixed point, after one refresh of
`cppsrc/simse_bootstrap.cpp`), `bun tools/stress.js` 56/56, `bun tools/smgen.js` 1/1.

### T31 - `simse.md`: module-declared source generators - Not started

Specified in `specs/simse-md.md`. The idea, in the words it has to be implemented from: a
project carries a `simse.md` naming its modules, a module may carry one of its own saying
`sourcegen: true`, and a project that imports such a module is compiled by a compiler
**extended** with that module's generator sources - "the compiler takes these modules as
source gens too, as part of the files of the compiler".

Settled, after the design turns: generators are **Simse** (`.kt`), they **self-register**
(a file-level static whose initializer adds it to the table), the extended compiler is built
by a **convention command** in the compiler's own manifest (`build: bun build.js --release
--cpp | --exe |` - a `|` template filled positionally, run with `system()`, its exit code
the answer), its tree is staged in the **`_simse`** cache in the parent of the project's root,
and a self-compilation that fails **drops the compilation** rather than compiling without the
generators.

Steps, in order:

1. ~~**Self-registration**~~ **done** - the table has no initializer of its own
   (`var sourceGenTable: List<SourceGenerator>`), and each of the three built-in generators
   registers itself from its own file (`val cppGenRegistered: Bool = registerSourceGen(
   "cpp", cppGen, true, true)`, `SourceGen.kt`'s `registerSourceGen`). A registration is an
   *append* onto storage that starts empty, so the initialization pass's unspecified order
   cannot matter; a module's generator will use the same one line. Verified: the compiler
   still builds itself, `bun tools/stress.js` 58/58 (the two new manifest cases below), the
   bootstrap fixed point holds after one refresh.
2. ~~**The manifest**~~ **done, minus the extension** - `simse.md` at a root
   (`module: <dir>` entries, repeated) and in a module (`sourcegen: true`), read by the
   driver's own small reader (`manifestValues`/`driverExpandRoot`, `Driver.kt`) in the
   resource idiom: prose lines ignored, `key: value`, a repeated key repeating. A root with
   a manifest is scanned as exactly the modules it names; a root without one is scanned
   whole, which is what this compiler's own `--root cppsrc` keeps doing. A module that
   declares `sourcegen: true` is a **hard error** naming it, because the extension is not
   implemented - `stress/manifest-modules` (the manifest's modules are scanned, a `stray.kt`
   beside the manifest is not) and `stress/diagnostic-manifest-sourcegen` pin both halves.
3. **Two natives** - `system()` (normalizing the exit code, since POSIX packs `system()`'s
   return) and directory creation for staging; the copy itself is `listFiles` +
   `readWholeFile` + `writeFile`, which the RTL already has.
4. **The extended tree** - stage the compiler's own sources plus the module's generators
   under `_simse` (never in `cppsrc/`, never inside a scanned root). The module's files are
   ordinary Simse modules of the compiler's build, and their registration statics are the
   whole registration step - nothing is synthesized. The entry is keyed on the compiler and
   the generator sources, and deleting `_simse` is always safe.
5. **The extended compiler** - transpile the staged tree (the compiler is the transpiler),
   run the `build:` template with the generated C++ and the output executable, cache the
   result under a fingerprint (the compiler's identity plus the generator sources), and run
   the project with it; a build that fails removes the staged copy and **drops the
   compilation**, reporting the module and the command's output.
6. **The recursion guard** - the extended compiler carries its generators and must not
   extend itself again.
7. **A regression case** - a `stress/` project with a module that ships a generator whose
   output the program's own `expected.cpp`/`expected.stdout` pin, plus one whose generator
   does not build (the rollback must drop the compilation and leave the compiler usable).

Steps 1 and 2 are implemented; `specs/simse-md.md` is the spec of record.

## Non-goals (for now)

- An interpreter or VM backend, an optimizer, or multiple backends.
- A full standard library.
- Performance work on the compiler itself.
- A complete FFI; only the narrow `native fun` fallback.
