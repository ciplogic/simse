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
   `@SmGen("cpp", "defined-in-headers", "simse_str_trim")`.
4. **Machinery** - the two spellings fill the same attributes, and `bun tools/smgen.js`
   asserts their amalgamations are byte-identical (modulo the fixture path).
5. **`native` as sugar** - the parser synthesizes the attribute for `native(...)`;
   emissions unchanged.
6. **`Sections`** (`cppsrc/codegen/CgSections.kt`) - get-or-create that appends a new
   section at the end, last-write-wins `add`, render text-then-items. Routing the
   emitter through it is byte-neutral: the compiler before and after it transpiles
   `cppsrc` to identical C++.
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

1. **The sections the assembly needs** (`cppsrc/codegen/CgSections.kt`): `support` (text the
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

Still hand-written C++, in this order of ease: `fs.hpp` + `filestream.hpp` (declarations
over `native.cpp` bodies - the declarations can move, the bodies cannot until the language
has file/string APIs of its own) and the type core (`types.hpp`, `containers.hpp`,
`smstring.hpp`, `smdictionary.hpp`, `span.hpp`, `strview.hpp`, `strsmallvector.hpp`,
`optional.hpp`, `result.hpp`, `functional.hpp`, `xml.hpp`, `astxml.hpp`), which is what the
amalgamation is compiled *against* and which needs language features that do not exist yet
(statics in an object, a ref-counted layout), so it stays.

## Non-goals (for now)

- An interpreter or VM backend, an optimizer, or multiple backends.
- A full standard library.
- Performance work on the compiler itself.
- A complete FFI; only the narrow `native fun` fallback.
