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

## 2. Current state (2026-09-13)

- The compiler is **fully ported to `.simse` and self-hosts to a fixed point**:
  the hand-written C++ compiler transpiles the Simse compiler sources; the
  resulting stage-1 binary transpiles the same sources to **byte-identical**
  output.
- **Five differentials are byte-identical**: scanner, skeleton parser, parser,
  sema, codegen (hand-written vs transpiled Simse).
- `simse_tests.exe`: 48 tests, all passing. Clean build green, including all the
  differentials and `stage1_check`, run automatically by the build.
- Ported components: `common`/`StrView`/`xmlutil`, scanner, skeleton parser,
  parser, sema, codegen, compiler driver.
- Runtime alignment: `Str` is the inline `SmString` (`SmallVector<char, 24>` +
  terminating NUL, the `specs/containers.md` layout) and `List<T>` is
  `SmallVector<T, 4>`, with `std::string` / `std::vector` as escape hatches
  (`SIMSE_STR_STD_STRING` / `SIMSE_LIST_STD_VECTOR`). Both `Str` backings are
  green on the full build and produce **byte-identical** compiler output.
- Planned tasks: **static storage** slice 1 (file-level `var`/`val`,
  `specs/statics.md` / `impl_specs/statics.md`) is implemented in both rings;
  slices 2-5 (`object`, generic `object`, `arrayEmpty` in Simse, object methods)
  are next.
- The directory compiler's default output is `simse_out.cpp`; the two-step
  bootstrap artifacts live under `cmake-build-debug/stage1/` (`gen/simse_out.cpp`,
  `gen/simse_out1.cpp`, `run/simse_out.cpp`).
- **Tree nodes hold their children in an `Array`** (`specs/xml-node.md`):
  `XmlNode.Children` is `Array<XmlNode>`, built as a `List` and frozen with
  `toArray()` (`xmlAddChild`/`xmlAddChildren` in `cppsrc/common/xmlutil.simse`),
  and a leaf node points at the shared empty array instead of allocating. Peak
  working set of a self-transpile (release, self-hosted) fell from 48.8 MB to
  21.0 MB and the min/median runtime from 128/141 ms to 121/131 ms over 6,357
  lines of Simse (both binaries emit byte-identical C++).
- **The AST's roles, categories and attribute keys are enums** (`AstXmlNode`,
  `cppsrc/rtl/astxml.simse`): `AstNodeKind` is the role, `AstNodeCategory` the
  schema's `kind`, `AstNodeAttributeKind` an attribute's key, so every test on a
  node - role, kind, attribute lookup - is an integer compare; the only text left
  in a tree is an attribute value. The category change alone was a few percent
  (2-16% across windows), the attribute-key change was the big one: min/median
  **121/131 ms -> 81/84 ms** (~33%) and peak working set 21.3 -> **16.1 MB**, below
  the hand-written ring's 22.8 MB.
- **Table lookups share their tables** (statics, the `statics.md` feature's first
  use in the compiler): the scanner's keyword, operator and token-rule tables are
  file-level `var`s built once by the pass and read through raw pointers
  (`tableMatch(view, table, exact)` is the one comparison both lookups use), where
  returning a `List<Str>` rebuilt them per call - a copy per token. Min/median
  **86-88/91-93 ms -> 70-72/75-77 ms** (**15-20%**) over three windows. Cumulative
  on the self-transpile over 6,357 lines: **128/121 ms at the start of the run ->
  ~71/76 ms**, and the gap to the hand-written ring closed from 3.5-3.7x to
  **~2.0x** (its 36.4/42.7 ms).
- **The scanner's table match runs cheap pre-tests** (first character, then length,
  then the rest) and reads its entries through raw pointers, so no lookup copies
  text (`StrView.startsWithPtr` is the pointer-taking comparison). Self-transpile
  **73.1/78.5 -> 64.9/69.8 ms** (-11%); the scanner stage alone over the same source
  set **317.9/333.0 -> 261.1/271.3 ms** (-18/-19%), which is **1.49x** the
  hand-written scanner where it was ~1.8x.

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

# the end-to-end stress corpus: one folder per program under stress/, each with
# its expected output; the harness transpiles, compiles and runs every one of
# them with the compiler under test (./simse.exe by default)
bun tools/stress.js                     # or ./stress.bat; see stress/README.md
bun tools/stress.js --list              # what the corpus contains
bun tools/stress.js --filter modules --jobs 4

# the alternative runtime backing (Str = std::string): a separate build folder,
# so the compiler and the amalgamations it is linked with agree on the define
cd cmake-build-strstd && cmd //c _msvc_build.bat

# compile the whole compiler tree into one amalgamated file in the CURRENT folder
./cmake-build-debug/simse_transpile.exe --root cppsrc   # -> ./simse_out.cpp (one main)
./cmake-build-debug/simse_transpile.exe                 # -> scans "." (hits tests/fixtures: errors by design)

# explicit-input form (used by the build/tests); -o defaults to simse_out.cpp
./cmake-build-debug/simse_transpile.exe <files...> [-o out.cpp] [--prelude <path>] [--root <dir>] [--module-root <dir>...]

# transpile the compiler and compile it (bun + cl.exe, loads the VS environment)
# compile an amalgamated output with cl.exe (loads the VS environment itself)
./build.bat                             # cppsrc -> ./simse_out.cpp -> ./simse.exe (debug)
./build.bat --release                   # release: /O2 /Ob3 /DNDEBUG, cmake-build-release libs (/MD)
./build.bat --release --lto             # + whole-program optimization (/GL + /LTCG; measured neutral)
./build.bat my_simse.exe                # same, different executable name
./build.bat --cpp other.cpp --exe x.exe # compile an existing amalgamation
./build.bat --help                      # all options (see build.js)

# profiling: open simse.sln (ARM64; Release is the default configuration and
# carries /Zi + /DEBUG). It compiles ONLY simse_out.cpp, refreshes that file
# from cppsrc when a source is newer, and writes profile\<config>\simse.exe.
```

The default build runs, as part of `ALL`: the five differentials plus
`stage1_check` (the end-to-end programs are no longer CMake targets - they run
under `bun tools/stress.js`, which is not part of the build because it tests the
built compiler rather than the C++ ring).
**If `simse*.exe` is running, linking fails with `LNK1168` - kill it first.**

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

- `specs/` - the language specification (normative). Start with
  `specs/modules.md` (modules/packages), `specs/declarations.md`,
  `specs/functions.md`, `specs/memory-model.md`, `specs/generics.md`,
  `specs/core-types.md`, `specs/built-in-types.md`, `specs/containers.md`,
  `specs/dictionary.md`, `specs/xml-node.md`, `specs/ref-counted-layout.md`.
  `specs/statics.md` (file-level `var`/`val` and `object`): the file-level half is
  implemented, the `object` half is specified only; the plan is
  `impl_specs/statics.md`.
- `impl_specs/` — implementation plans/records: `plan-to-selfhost.md`,
  `transpilation.md`, `roadmap.md`, `capability-matrix.md`, `rtl-abi.md`,
  `reification.md`, `native-interop.md`, `ast-xmlnode.md`, `tasks/`.
- `cppsrc/rtl/` — hand-written runtime: C++ headers (`types.hpp`,
  `containers.hpp`, `smstring.hpp`, `strsmallvector.hpp`, `optional.hpp`,
  `functional.hpp`, `result.hpp`, `xml.hpp`, `cursor.hpp`, `listops.hpp`,
  `strops.hpp`, `dictops.hpp`, `fs.hpp`, `simse.hpp`) AND the **prelude** `.simse`
  files (`rtl.simse`, `Cursor.simse`, `xml.simse`, `fs.simse`) declaring the RTL
  surface. `List<T>` is `SmallVector<T, 4>` and `Str` is the inline `SmString`
  (`smstring.hpp`), whose buffer is `strsmallvector.hpp`'s `StrSmallVector`, the
  char-specialized form of the `SmallVector<char, 24>` layout — `Int _len`
  (character count, zero-based), `Int _cap` (allocated bytes), a 24-byte inline
  buffer unioned with the heap pointer, the terminating NUL one byte past the
  text, `constexpr` while inline — by default. That 24 is the single constant
  `kStrInlineCapacity` (`SIMSE_STR_INLINE_CAPACITY` overrides it per build; 16
  saves ~18% of the peak working set but spills 16-character strings, so 24
  stays the default — `impl_specs/capability-matrix.md` T33).
  The CMake options `SIMSE_LIST_STD_VECTOR` / `SIMSE_STR_STD_STRING` (or
  `build.bat --define ...`) switch them to `std::vector<T>` / `std::string`;
  the choice has to match between the compiler and the amalgamated output it
  is linked with, and `build.js` mirrors the CMake cache automatically
  (`impl_specs/rtl-abi.md`). The language's layout model is
  **4-byte packing** (`specs/memory-model.md`): the emitter brackets every
  generated aggregate in `SIMSE_PACK_PUSH`/`SIMSE_PACK_POP`, and `SIMSE_NO_PACK4`
  reverts to the host's default alignment.
- `cppsrc/common/` — `readFile`/`filesInDir`, `StrView`, `xmlutil` (C++ + Simse).
- `cppsrc/lex/`, `cppsrc/skelparser/`, `cppsrc/parser/`, `cppsrc/sema/`,
  `cppsrc/linear/`, `cppsrc/codegen/`, `cppsrc/compiler/` — the compiler stages;
  each has a C++ implementation AND a `.simse` mirror. `linear` is the post-sema
  lowering of control flow to labels/gotos (`impl_specs/linear-lowering.md`).
- `Compiler.{h,cpp}` — the shared transpile core; `cppsrc/codegen/TranspileMain.cpp`
  — the `simse_transpile` CLI, the C++ compiler driver (the Simse mirror of it
  is `cppsrc/compiler/Driver.simse`).
- `cppsrc/native/` — hand-written C++ for `native(...)` symbols
  (e.g. `simse_native_readFile`).
- `tests/` - fixtures, goldens (`<fixture>.simse.{tokens,ast,astxml,sema,cpp}.expected`),
  the test runner, and the differential drivers (`*_ref_main.cpp` /
  `*_simse_main.cpp`).
- `stress/` - the end-to-end stress corpus: one folder per Simse project
  (`src/` + `expected.stdout` + optional `args`/`stdin`/`expected.cpp`/
  `expected.transpile-error`), run by `tools/stress.js` against the compiler under
  test (`stress/README.md`).
- `tools/` - the JavaScript harness: `stress.js` (the corpus above), `msvc.mjs`
  (the Visual Studio environment shared with `build.js`), the A/B helpers
  (`_bench_ab.mjs`, `_hoist_ab.bat`, `_cap_ab.bat`, `_probe.bat`, `memrun.cpp`,
  `str_bench.cpp`, ...) and the probe programs.

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
(AST) -> resolve names/types -> reify generics -> lower control flow to
labels/gotos -> simplify the linear form -> lower to C++ -> amalgamate. The
emitters only know the linear statement forms (`Stmt.Label`/`Goto`/`IfTrue`/
`IfFalse`/`Block`); structured `if`/`while`/`switch` never reach emission.

Key design points:

- **AST carrier is `AstXmlNode`** (see `impl_specs/ast-xmlnode.md`): one uniform
  node; `name` is an `AstNodeKind` (the structural role), `kind` an
  `AstNodeCategory` (the schema's category), an attribute key an
  `AstNodeAttributeKind` - all enums, in `cppsrc/rtl/astxml.simse` - so every test
  on a node is an integer compare. Attribute *values* are text, children are an
  `Array<AstXmlNode>` (one counted block, count first, the shared empty array for a
  leaf). The C++ side has `ast::toXmlNode`/`dumpXmlNode` and the enum→text
  spellings; the language-level `XmlNode` (`specs/xml-node.md`) stays the general
  tree a program builds.
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
  identifiers; there is no qualified-name access form. The built-in types
  (`Str`, `List<T>`, ... `XmlNode`) are declared in `rtl`, which is the implicit
  import.
- **Emitted symbols are package-qualified** (`impl_specs/rtl-abi.md`): `rtl` is
  emitted bare, and every other package gets `ns<index>_` from a global
  dictionary the emitter fills in sorted package order (`codegen` `ns1_`,
  `common` `ns2_`, `compiler` `ns3_`, ... for the compiler's own source set).
  Declarations and every reference to them carry the prefix, so two packages can
  both declare `Point` or `bump` without colliding in the amalgamated translation
  unit. `main` keeps its name and `native` symbols are never prefixed.

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
- **The stress corpus stays green**: `bun tools/stress.js` transpiles, compiles
  and runs every program under `stress/` with the self-hosted compiler and
  compares its output (one folder per program, `stress/README.md`). Both rings
  must pass it: `--simse cmake-build-debug/simse_transpile.exe` checks the other
  side of the port on the same corpus.

## 7. Change protocol (read before editing)

- **Two rings**: any compiler behavior change must be made in BOTH the C++
  implementation and the matching `.simse` mirror (scanner, parser, sema,
  codegen, driver, xmlutil). Keep them behaviorally identical.
- After a change: rebuild (`_msvc_build.bat`), run `simse_tests.exe`
  (`--update` then check mode), confirm the five differentials are still
  byte-identical, and re-run `stage1_check` (the fixed point must still hold).
  For anything visible to a program - the runtime, codegen, the driver - also
  rebuild the self-hosted compiler (`bun build.js` or `build.bat`) and run
  `bun tools/stress.js`; add a case under `stress/` for behavior that is not yet
  covered there.
- **Never** weaken the C++ reference or the XmlNode schema to make a mirror
  pass; fix the transpiler or the mirror generically. Do not special-case a
  specific file.
- Prefer `while` + `Cursor<T>` over `for`/range-for in Simse code (no range-for
  yet). Use `switch` for kind dispatch; lambdas are supported (by-value capture).
- **Borrow AST-carrying structs; don't copy them.** A `val x: T = list[i]` where
  `T` holds an `XmlNode` (`CgFn`, `CgNativeExt`, `CgInput`, `SemaInput`, ...)
  deep-copies the subtree. In read-only loops use a pointer into the owner
  (`val fn: *CgFn = *this.functions[i]`, `val decl: *XmlNode = *fn.decl`) and let
  every function that only reads take `*T`; a by-value copy *per lookup* makes
  emission quadratic in the function/AST count (the `CgFn` copies did: the Debug
  `stage1_check` cost 6.6-8.7 s before they were removed, 2.1 s after).
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
`native fun`; `val`/`var` (locals, and at file level **static storage** -
`specs/statics.md`); `if`/`else`, `while`, `switch`/`case`/`default`,
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
   `unsafe` blocks / raw-pointer escape rules; **static storage** - file-level
   `var`/`val` (done, `impl_specs/statics.md` slice 1) and `object` declarations
   (generic-capable, slices 2-5), specified in `specs/statics.md`; the `object`
   slices are the piece the RTL needs to move per-type statics such as
   `arrayEmpty<T>()`'s empty block out of hand-written C++.
4. **RTL spec convergence**: the RTL is a shim (`Str` is the spec-shaped inline
   `SmString`, `List` is `SmallVector<T, 4>`, both with a
   `std::string`/`std::vector` escape hatch behind `SIMSE_STR_STD_STRING` /
   `SIMSE_LIST_STD_VECTOR`; no `[refcount][typeId]` header). Divergences are
   documented in `impl_specs/rtl-abi.md`; the eventual target must match
   `specs/`.
5. **Ergonomics/robustness**: lambda typing is conservative (a body/return
   mismatch surfaces as a C++ compile error, not a Simse diagnostic); generic
   type aliases aren't expanded when resolving an expected callable type; `Str`
   is byte-oriented (ASCII case mapping); the single ~190 KB amalgamated TU may
   need attention as the compiler grows.

## 10. Gotchas

- `LNK1168` on build = a running `simse*.exe` holds the output; kill it first.
- `build.bat` defaults to a debug build (`/MDd`), so the MSVC debug STL asserts are
  live: bad input such as a directory passed where a `.simse` file is expected can
  pop an assert dialog instead of a diagnostic. Use `build.bat --release`
  (`/O2 /DNDEBUG`, release libs) for a build with the asserts compiled out.
- Scanning `.` (no args) walks `tests/fixtures/*`, which intentionally contain
  bad input and will make `simse_transpile` exit non-zero. Pass `--root cppsrc`
  (or another clean module root) instead.
- Prelude `.simse` bodies are not emitted; put behavior in the RTL C++ headers.
- Source-map comments embed the path as given, so absolute and relative runs
  differ — cosmetic.
- Goldens are sensitive to line-number shifts; regenerate with `--update` when
  intentionally changing sources, then confirm check-mode passes.
