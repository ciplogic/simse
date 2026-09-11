# guide4ai.md — orientation for a fresh session

Purpose: re-orient a new agent/session fast. Read this first, then
`impl_specs/capability-matrix.md`, `specs/modules.md`, and
`impl_specs/roadmap.md`. Status snapshot below is as of 2026-09-11; counts and
byte sizes drift — trust the build, not these numbers.

## 1. What this is

**Simse** is a small, statically typed language (Kotlin/.NET influenced) that
transpiles to a single amalgamated C++20 file. The long-term goal is a
**self-hosted** compiler: the compiler is written in Simse and compiles its own
sources, with a small hand-written C++ foundation (the RTL) for things not yet
expressible in the language.

## 2. Current state (2026-09-11)

- The compiler is **fully ported to `.simse` and self-hosts to a fixed point**:
  the hand-written C++ compiler transpiles the Simse compiler sources; the
  resulting stage-1 binary transpiles the same sources to **byte-identical**
  output.
- **Five differentials are byte-identical**: scanner, skeleton parser, parser,
  sema, codegen (hand-written vs transpiled Simse).
- `simse_tests.exe`: ~59 tests, all passing. Clean build green, including all
  e2e programs and the differentials, run automatically by the build.
- Ported components: `common`/`StrView`/`xmlutil`, scanner, skeleton parser,
  parser, sema, codegen, compiler driver.
- Planned tasks: none outstanding (`T25 Modules and packages` is Done).
- The directory compiler's default output is `simse_out.cpp`; the two-step
  bootstrap artifacts live under `cmake-build-debug/stage1/` (`gen/simse_out.cpp`,
  `gen/simse_out1.cpp`, `run/simse_out.cpp`).

## 3. Build / test / run

Toolchain: CMake + Ninja + MSVC (arm64), C++20. CLion bundles cmake/ninja.
From the build dir the wrapper sources `vcvarsall arm64` then runs
`cmake --build .`; without it `cl` cannot find its headers.

```sh
# build (from repo root)
cd cmake-build-debug
cmd //c _msvc_build.bat                 # incremental
cmd //c "_msvc_build.bat --clean-first" # clean rebuild

# tests / goldens
./simse_tests.exe                       # check mode
./simse_tests.exe --update              # regenerate goldens deliberately

# compile the whole compiler tree into one amalgamated file in the CURRENT folder
./cmake-build-debug/simse_transpile.exe --root cppsrc   # -> ./simse_out.cpp (one main)
./cmake-build-debug/simse_transpile.exe                 # -> scans "." (hits tests/fixtures: errors by design)

# explicit-input form (used by the build/tests); -o defaults to simse_out.cpp
./cmake-build-debug/simse_transpile.exe <files...> [-o out.cpp] [--prelude <path>] [--root <dir>] [--module-root <dir>...]

# compile an amalgamated output with cl.exe (loads the VS environment itself)
./build.bat                             # ./simse_out.cpp -> ./simse_out.exe
./build.bat <input.cpp> [<out.exe>]     # defaults: simse_out.cpp / <name>.exe
```

The default build runs, as part of `ALL`: every e2e program (transpile ->
compile -> run -> stdout diff) and the five differentials plus `stage1_check`.
**If `simse*.exe` is running, linking fails with `LNK1168` — kill it first.**

`stage1_check` *is* the two-step transpiling check: it transpiles the compiler
source set (`cppsrc/compiler/Driver.simse` plus the module roots) into
`stage1/gen/simse_out.cpp`, keeps that as `stage1/gen/simse_out1.cpp`, compiles
that copy into `stage1/simse_stage1.exe`, runs it over the same source set to
regenerate `stage1/run/simse_out.cpp`, and requires the two files to be
**byte-identical**. Re-run just this step from the build dir with
`cmake --build . --target stage1_check`.

`--root cppsrc` scans the whole source tree (the prelude under `cppsrc/rtl` is
excluded as prelude), so the amalgamation contains exactly one `main` — the
driver's — and `build.bat` can compile it. `stage1_check` uses the equivalent
explicit `cppsrc/compiler/Driver.simse` input.

## 4. Repo map

- `specs/` — the language specification (normative). Start with
  `specs/modules.md` (modules/packages), `specs/declarations.md`,
  `specs/functions.md`, `specs/memory-model.md`, `specs/generics.md`,
  `specs/core-types.md`, `specs/built-in-types.md`, `specs/containers.md`,
  `specs/dictionary.md`, `specs/xml-node.md`, `specs/ref-counted-layout.md`.
- `impl_specs/` — implementation plans/records: `plan-to-selfhost.md`,
  `transpilation.md`, `roadmap.md`, `capability-matrix.md`, `rtl-abi.md`,
  `reification.md`, `native-interop.md`, `ast-xmlnode.md`, `tasks/`.
- `cppsrc/rtl/` — hand-written runtime: C++ headers (`types.hpp`,
  `containers.hpp`, `optional.hpp`, `functional.hpp`, `result.hpp`, `xml.hpp`,
  `cursor.hpp`, `listops.hpp`, `strops.hpp`, `dictops.hpp`, `fs.hpp`,
  `simse.hpp`) AND the **prelude** `.simse` files (`rtl.simse`, `Cursor.simse`,
  `xml.simse`, `fs.simse`) declaring the RTL surface.
- `cppsrc/common/` — `readFile`/`filesInDir`, `StrView`, `xmlutil` (C++ + Simse).
- `cppsrc/lex/`, `cppsrc/skelparser/`, `cppsrc/parser/`, `cppsrc/sema/`,
  `cppsrc/codegen/`, `cppsrc/compiler/` — the compiler stages; each has a C++
  implementation AND a `.simse` mirror.
- `Compiler.{h,cpp}` — the shared transpile core; `cppsrc/codegen/TranspileMain.cpp`
  — the `simse_transpile` CLI, the C++ compiler driver (the Simse mirror of it
  is `cppsrc/compiler/Driver.simse`).
- `cppsrc/native/` — hand-written C++ for `native(...)` symbols
  (e.g. `simse_native_readFile`).
- `tests/` — fixtures, goldens (`*.tokens/ast/astxml/sema/cpp/stdout.expected`),
  the test runner, and the differential drivers (`*_ref_main.cpp` /
  `*_simse_main.cpp`).

## 5. Architecture

Two "rings" that must stay in lockstep:

1. **Bootstrap ring (C++)**: the hand-written compiler — scanner, parser, sema,
   codegen, driver — plus the RTL.
2. **Self-host ring (`.simse`)**: the same compiler, ported, in `cppsrc/**/*.simse`.

`simse_transpile` (C++, bootstrap) transpiles the self-host ring into one
`simse_out.cpp`; that file (kept as `simse_out1.cpp`) is compiled into
`simse_stage1`, which transpiles the same sources into a fresh `simse_out.cpp`
that must be byte-identical (the fixed point).

Pipeline (per `impl_specs/transpilation.md`): discover sources -> scan -> parse
(AST) -> resolve names/types -> reify generics -> lower to C++ -> amalgamate.

Key design points:

- **AST carrier is `XmlNode`** (see `impl_specs/ast-xmlnode.md`): one uniform
  node; `name` = structural role, a `kind` attribute distinguishes categories,
  scalars are string attributes, children are `PList<XmlNode>`. This keeps the
  AST expressible in Simse today; the cost is that attributes are
  stringly-typed. The C++ side has `ast::toXmlNode`/`dumpXmlNode`.
- **Generics are reified via emitted C++ templates** (see
  `impl_specs/reification.md`): distinct Simse instantiations become distinct
  C++ types; `SmallVector<N,T>` maps to `SmallVector<T,N>`.
- **Native boundary** (see `impl_specs/native-interop.md`): `native fun` /
  `native("Symbol") fun` declares a function whose body is hand-written C++.
  Container/string/dict/fs operations are prelude extensions over RTL C++
  templates (`listops.hpp`, `strops.hpp`, `dictops.hpp`, `cursor.hpp`, `fs.hpp`).
- **Prelude**: `cppsrc/rtl/*.simse` is implicitly in scope everywhere; its
  method bodies are NOT emitted (behavior lives in the RTL C++ headers).
- **Modules/packages** (see `specs/modules.md`): a **module is a directory**, a
  **package is a namespace** declared by a mandatory `package a.b.c` as each
  file's first declaration. Imports are style (A): the compiler scans module
  roots and includes every `.simse`; `import pkg` only makes `pkg` visible
  unqualified (never adds files); `rtl` is implicit; import of a package no
  scanned file declares is an error. Package names are opaque dotted
  identifiers; there is no qualified-name access form.

## 6. Invariants and how they are verified

- **FIVE differentials byte-identical**: `scanner_diff`, `skel_diff`,
  `parser_diff`, `sema_diff`, `codegen_diff` (hand-written C++ vs transpiled
  Simse, over the fixture set). Run automatically by the build.
- **Two-step bootstrap fixed point**: `simse_out1.cpp` (the C++ transpiler's
  output, kept) == `run/simse_out.cpp` (the stage-1 compiler's regeneration),
  byte for byte (`stage1_check`).
- **Determinism**: transpiling the same inputs twice is byte-identical.
- **Goldens**: `simse_tests.exe` compares against `tests/golden/*.expected`
  (regenerate with `--update` only when behavior intentionally changes).

## 7. Change protocol (read before editing)

- **Two rings**: any compiler behavior change must be made in BOTH the C++
  implementation and the matching `.simse` mirror (scanner, parser, sema,
  codegen, driver, xmlutil). Keep them behaviorally identical.
- After a change: rebuild (`_msvc_build.bat`), run `simse_tests.exe`
  (`--update` then check mode), confirm the five differentials are still
  byte-identical, and re-run `stage1_check` (the fixed point must still hold).
- **Never** weaken the C++ reference or the XmlNode schema to make a mirror
  pass; fix the transpiler or the mirror generically. Do not special-case a
  specific file.
- Prefer `while` + `Cursor<T>` over `for`/range-for in Simse code (no range-for
  yet). Use `switch` for kind dispatch; lambdas are supported (by-value capture).
- Every `.simse` file must start with a mandatory `package`; update `import`
  lines to package names when adding files.
- **Do not commit** unless the user explicitly asks.

## 8. Language features currently implemented

Scalars (`Int8..64`, `Float32/64`, `Char`, `Bool`), `Str` (with a method library:
`find`, `substr`, `startsWith`, `endsWith`, `replace`, `toInt`, `toFloat`,
`charAt`, `trim`, `split`, `toUpper`, `toLower`, `isEmpty`, `indexOf`,
`lastIndexOf`); `List<T>` (with `append`, `removeAt`, `removeRange`, `insert`,
`clear`, `contains`, `sort`); `Dictionary<K,V>` (`get`/`has`/`insert`/`remove`/
`keys`/`values`/`size`/`clear`); `Opt<T>`, `Res<T>` (with `Res<T>.ok/.err`,
`Opt<T>.some/.none`); `Cursor<T>` (immutable span-like); `XmlNode`/`Attribute`;
`data class` (with methods), `enum` (with `toInt`/`fromInt`), `typealias`
(incl. generic and function types); functions incl. extension functions and
`native fun`; `val`/`var`; `if`/`else`, `while`, `switch`/`case`/`default`,
`break`/`continue`, `return`; `null`; memory operators `&T`/`*T`/`copy`;
lambdas with by-value capture; generics reified via C++ templates; modules and
packages.

## 9. TODOs / deferred

Do these only when asked; roughly prioritized:

1. **Commit the work.** Nothing is committed; large amounts of source and docs
   are uncommitted or untracked.
2. **Stage-2 self-host**: have `simse_stage1` compile itself a second time and
   verify the fixed point again (stronger bootstrap proof). Also broaden
   `stage1_check` to sweep more fixtures.
3. **Deferred language features** (spec'd or implied, not implemented):
   multiple `package` declarations per file (file-split shape); external-module
   manifests/versions/transitive resolution; `for`/range-for; reference captures
   and explicit capture lists; `when`/pattern matching; string interpolation;
   interfaces/virtual dispatch; method overriding; default parameter values;
   `unsafe` blocks / raw-pointer escape rules.
4. **RTL spec convergence**: the RTL is a shim (`Str`=`std::string`,
   `List`=`std::vector`, `SmallVector` has no SBO operations, no
   `[refcount][typeId]` header). Divergences are documented in
   `impl_specs/rtl-abi.md`; the eventual target must match `specs/`.
5. **Ergonomics/robustness**: lambda typing is conservative (a body/return
   mismatch surfaces as a C++ compile error, not a Simse diagnostic); generic
   type aliases aren't expanded when resolving an expected callable type; `Str`
   is byte-oriented (ASCII case mapping); the single ~190 KB amalgamated TU may
   need attention as the compiler grows.

## 10. Gotchas

- `LNK1168` on build = a running `simse*.exe` holds the output; kill it.
- Scanning `.` (no args) walks `tests/fixtures/*`, which intentionally contain
  bad input and will make `simse_transpile` exit non-zero. Pass `--root cppsrc`
  (or another clean module root) instead.
- Prelude `.simse` bodies are not emitted; put behavior in the RTL C++ headers.
- Source-map comments embed the path as given, so absolute and relative runs
  differ — cosmetic.
- Goldens are sensitive to line-number shifts; regenerate with `--update` when
  intentionally changing sources, then confirm check-mode passes.
