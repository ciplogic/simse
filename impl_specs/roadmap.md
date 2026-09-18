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
| T27 | Per-instantiation generators (`@Json`, enum-to-string, int-to-enum) | T26 | Not started |

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

### T27 - Per-instantiation generators - Not started

The model is in `impl_specs/generators.md` ("Deferred"): instantiation collection,
mangled symbols, a generator invoked per instantiation with its concrete type
arguments, positioned diagnostics, and the `@Json` sugar. Also: enum-to-string and
int-to-enum as trailing sections, the program's own `_res.md` as a generator's input,
and attributes on things other than methods.

See `impl_specs/generators.md` and `specs/attributes.md`.

## Non-goals (for now)

- An interpreter or VM backend, an optimizer, or multiple backends.
- A full standard library.
- Performance work on the compiler itself.
- A complete FFI; only the narrow `native fun` fallback.
