# guide4ai.md — orientation for a fresh session

Purpose: re-orient a new agent/session fast. Read this first, then
`impl_specs/capability-matrix.md`, `specs/modules.md`, and
`impl_specs/roadmap.md`. `impl_specs/user-language-roadmap.md` is the companion
roadmap for what a *user* of the language is blocked on (protocols, JSON, tooling,
the single-threaded server story).

The guide deliberately holds **no status**: what is implemented, what moved and what
it cost lives in `impl_specs/` (`capability-matrix.md` is the update log) and in
`benchmarks/`, and neither is worth copying here - it drifts by the hour.

## 1. What this is

**Simse** is a small, statically typed language (Kotlin/.NET influenced) that
transpiles to a single amalgamated C++20 file. The long-term goal is a
**self-hosted** compiler: the compiler is written in Simse and compiles its own
sources, with a small hand-written C++ foundation (the RTL) for things not yet
expressible in the language.

## 2. Build / test / run

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

# compile the whole compiler tree into one amalgamated file in the CURRENT folder
./cmake-build-debug/simse_transpile.exe --root cppsrc   # -> ./simse_out.cpp (one main)
./cmake-build-debug/simse_transpile.exe                 # -> scans "." (hits tests/fixtures: errors by design)

# explicit-input form (used by the build/tests); -o defaults to simse_out.cpp
./cmake-build-debug/simse_transpile.exe <files...> [-o out.cpp] [--prelude <path>] [--root <dir>] [--module-root <dir>...]

# the linear IL of every emitted body, on stderr (debug view; the C++ output is
# identical with and without it - impl_specs/linear-il.md)
./cmake-build-debug/simse_transpile.exe --root cppsrc -o a.cpp --showLinearRepresentation 2> il.txt

# codegen: the C++ of every body comes from its instruction list - the IL is the only
# codegen (a body it cannot spell is a hard error). The 2 lambda bodies are spelled as
# closure classes, which is the one shape the model owns.
./cmake-build-debug/simse_transpile.exe --root cppsrc -o a.cpp           # the default

# transpile the compiler and compile it (bun + cl.exe, loads the VS environment)
# compile an amalgamated output with cl.exe (loads the VS environment itself)
./build.bat                             # cppsrc -> ./simse_out.cpp -> ./simse.exe (debug)
./build.bat --release                   # release: /O2 /Ob3 /DNDEBUG, cmake-build-release libs (/MD)
./build.bat --release --lto             # + whole-program optimization (/GL + /LTCG; measured neutral)
./build.bat my_simse.exe                # same, different executable name
./build.bat --cpp other.cpp --exe x.exe # compile an existing amalgamation
./build.bat --help                      # all options (see build.js)

# the published bootstrap: the same amalgamation, checked in at
# cppsrc/simse_bootstrap.cpp so it can be built with a C++ compiler alone
# (docs/getting-started.md, "Build the compiler without a compiler"). Refresh it
# after compiler changes (the file is generated, never hand-edited):
bun build.js --release --out cppsrc/simse_bootstrap.cpp   # also builds ./simse.exe

# how fast does it bootstrap, and does the fixed point hold?
bun tools/bootstrap.js                  # times: transpile, cl.exe, and the fixed point
bun tools/bootstrap.js --debug          # the same with the debug toolchain

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
source set (`cppsrc/compiler/Driver.kt` plus the module roots) into
`stage1/gen/simse_out.cpp`, keeps that as `stage1/gen/simse_out1.cpp`, compiles
that copy into `stage1/simse_stage1.exe`, runs it over the same source set to
regenerate `stage1/run/simse_out.cpp`, and requires the two files to be
**byte-identical**. Re-run just this step from the build dir with
`cmake --build . --target stage1_check`.

`--root cppsrc` scans the whole source tree (the prelude under `cppsrc/rtl` is
excluded as prelude), so the amalgamation contains exactly one `main` — the
driver's — and `build.bat` can compile it. `stage1_check` uses the equivalent
explicit `cppsrc/compiler/Driver.kt` input.

## 3. Repo map

- `README.md` and `docs/` - the published documentation: what the language is
  (`README.md`), the tutorial (`docs/language-tour.md`), the pipeline and emitted
  C++ (`docs/how-it-works.md`), honest status and comparisons
  (`docs/state-of-the-field.md`), and the runnable examples under
  `docs/examples/<name>/src/` (built with the commands in
  `docs/getting-started.md`). Keep these true when behavior changes.
- `specs/` - the language specification (normative). Start with
  `specs/modules.md` (modules/packages), `specs/declarations.md`,
  `specs/functions.md`, `specs/memory-model.md`, `specs/generics.md`,
  `specs/core-types.md`, `specs/built-in-types.md`, `specs/containers.md`,
  `specs/dictionary.md`, `specs/xml-node.md`, `specs/ref-counted-layout.md`.
  `specs/statics.md` (file-level `var`/`val` and `object`): the file-level half is
  implemented, the `object` half is specified only; the plan is
  `impl_specs/statics.md`.
- `impl_specs/` — implementation plans/records: `plan-to-selfhost.md`,
  `transpilation.md`, `roadmap.md`, `user-language-roadmap.md` (the user-facing
  feature roadmap: static protocols, JSON codegen, sockets/HTTP, toolchain),
  `capability-matrix.md`, `rtl-abi.md`, `reification.md`, `native-interop.md`,
  `ast-xmlnode.md`, `linear-lowering.md`, `linear-il.md` (the flat instruction list
  the backend is meant to consume, with its dump), `yield.md` (`yield` as a pure
  lowering to a state machine), `tasks/.
- `cppsrc/rtl/` — hand-written runtime: C++ headers (`types.hpp`,
  `containers.hpp`, `smstring.hpp`, `strsmallvector.hpp`, `optional.hpp`,
  `functional.hpp`, `result.hpp`, `xml.hpp`, `span.hpp`,
  `listops.hpp`, `strops.hpp`, `dictops.hpp`, `fs.hpp`, `filestream.hpp`,
  `timeops.hpp`, `simse.hpp`) AND the **prelude** `.kt`
  files (`rtl.kt`, `Span.kt`, `xml.kt`, `fs.kt`)
  declaring the RTL surface.
  surface. `List<T>` is `SmallVector<T, 4>`, `Str` is the inline `SmString`
  (`smstring.hpp`), whose buffer is `strsmallvector.hpp`'s `StrSmallVector`, the
  char-specialized form of the `SmallVector<char, 24>` layout — `Int _len`
  (character count, zero-based), `Int _cap` (allocated bytes), a 24-byte inline
  buffer unioned with the heap pointer, the terminating NUL one byte past the
  text, `constexpr` while inline — and `Dictionary<K, V>` is the RTL's own value
  dictionary (`SmDictionary`, the .NET row/bucket shape, `smdictionary.hpp`).
  Each is the type's only implementation; the old `SIMSE_LIST_STD_VECTOR` /
  `SIMSE_STR_STD_STRING` / `SIMSE_DICT_SM` alternatives are gone. Sizes, lengths
  and indexes are the language's `Int` (32-bit signed; `Str::size_type` is
  `int32_t`, `Str::npos` is `-1`), with `std::size_t` only where the standard
  library's own signature needs one, and `std::string` survives only at the
  native boundary (`simse_toStdString`/`simse_fromStdString`, the `getline`
  helper, `FileStream`'s line buffer). The 24 is the single constant
  `kStrInlineCapacity` (`SIMSE_STR_INLINE_CAPACITY` overrides it per build; 16
  saves ~18% of the peak working set but spills 16-character strings, so 24
  stays the default — `impl_specs/capability-matrix.md` T33). The knobs that
  remain (`SIMSE_STR_INLINE_CAPACITY`, `SIMSE_NO_PACK4`) have to match between
  the compiler and the amalgamated output it is linked with, and `build.js`
  mirrors the CMake cache automatically (`impl_specs/rtl-abi.md`). The language's layout model is
  **4-byte packing** (`specs/memory-model.md`): the emitter brackets every
  generated aggregate in `SIMSE_PACK_PUSH`/`SIMSE_PACK_POP`, and `SIMSE_NO_PACK4`
  reverts to the host's default alignment.
- `cppsrc/common/` — `readFile`/`filesInDir`, `xmlutil` (C++ + Simse).
- `cppsrc/lex/`, `cppsrc/skelparser/`, `cppsrc/parser/`, `cppsrc/sema/`,
  `cppsrc/linear/`, `cppsrc/codegen/`, `cppsrc/compiler/` - the compiler stages;
  each has a C++ implementation AND a `.kt` mirror. `linear` is the post-sema
  lowering of control flow to labels/gotos (`Linear.{h,cpp}`/`Linear.kt`), the
  peephole trim of that form (`Simplify.*`), and the lowering of nested expressions
  into `_sm_expr<n>` temporaries (`ExpressionLowering.*`); `sema` also carries the
  lowering-time type inference that types those temporaries
  (`TypeInfer.{h,cpp,simse}`). All of it is specified in
  `impl_specs/linear-lowering.md`. `LinearForm.{h,cpp}` projects the same body into
  the flat linear IL (one instruction list, no blocks), prints it for
  `--showLinearRepresentation` and emits C++ **from** it - the only codegen
  (`impl_specs/linear-il.md`). The IL is a strict bytecode: one instruction is one
  operation, every operand is a slot of the declared frame or a constant, and a slot the
  extractor synthesizes (a read's base, a call's receiver, a borrow's operand) is typed
  by the type pass's own rules (`sema::typeOfExpr`) and declared with the frame - so
  `attributes[i].size()` is three instructions (`IndexAddr`, `GetField`, `Call`) and
  three lines of C++, never one expression. A container built from values is one
  instruction too (`Pack`: `listOf<Str>(a, b, c)`, and a call whose last parameter is a
  `List<T>`/`*List<T>` packs its trailing arguments into one - `specs/functions.md`),
  and a call argument whose handle is inferred (a `*T` parameter taking a value's
  address - the *place's* address, so a write through the pointer reaches the caller's
  element or field; a `*T`/`&T` argument read through for a by-value parameter) is one
  more instruction between the two, never a change of the callee. The one shape the
  checker refuses is a raw pointer where a counted reference is wanted
  (`specs/functions.md`, "Handles at a call").
  What a backend still folds is a slot whose
  type the rules cannot name (a `for`'s machine slot, mostly a bare `null`), which is the one
  shape that prints where it is read.
  `LinearForm.kt` is the whole thing mirrored: the model, the signature table, the
  printer, the extractor, and the backend lives in `Codegen.kt`
  (`emitIlBodyText`/`ilEmitOps`/the closure classes), so **both rings emit from the IL**.
  `Yield.{h,cpp,simse}` is the one
  language feature that is nothing but a lowering: `yield` becomes labels, a branch
  field and a class (`impl_specs/yield.md`), and `for` (`Parser::parseFor`, which
  desugars the two forms to a `while` before anything else sees them) is the second
  (`impl_specs/for.md`). `for` and `yield` are in **both** rings now - parser, sema,
  the machine lowering and the emitter's `emitYieldable`/`emitMachine`, with a
  *generic* machine a class template and an extension function's receiver a field of it
  (`stress/yield`, `stress/generic-yield`). Iteration is a convention: `for (x in c)` is
  `c.smToYield()`, the prelude writes one per container in Simse (`List`, `Array`,
  `Span`), a machine is
  its own identity, and a prelude body is emitted only when the program reaches it -
  by name *and* by the receiver's type, since a machine's class is named after its
  receiver - so a program that iterates a list carries no array machine
  (`stress/for-container`, `stress/for-array`, `stress/diagnostic-not-iterable`).
  `tools/_ring/` is the two-ring probe.
- `Compiler.{h,cpp}` — the shared transpile core; `cppsrc/codegen/TranspileMain.cpp`
  — the `simse_transpile` CLI, the C++ compiler driver (the Simse mirror of it
  is `cppsrc/compiler/Driver.kt`).
- `cppsrc/native/` — hand-written C++ for `native(...)` symbols
  (e.g. `simse_native_readFile`).
- `tests/` - fixtures, goldens (`<fixture>.kt.{tokens,ast,astxml,sema,cpp}.expected`),
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
- `benchmarks/` - published measurements; `benchmarks/onebrc/` is the naive 1BRC
  in Simse (`src/main.kt`, each line parsed in place through `StrView`) with the
  C++ STL baseline, the Bun generator/reference (`onebrc.mjs`), the measured
  write-up (`benchmark.md`) and the run commands (`README.md`; data and binaries
  are git-ignored).

## 4. Architecture

Two "rings" that must stay in lockstep:

1. **Bootstrap ring (C++)**: the hand-written compiler — scanner, parser, sema,
   codegen, driver — plus the RTL.
2. **Self-host ring (`.kt`)**: the same compiler, ported, in `cppsrc/**/*.kt`.

`simse_transpile` (C++, bootstrap) transpiles the self-host ring into one
`simse_out.cpp`; that file (kept as `simse_out1.cpp`) is compiled into
`simse_stage1`, which transpiles the same sources into a fresh `simse_out.cpp`
that must be byte-identical (the fixed point).

Pipeline (per `impl_specs/transpilation.md`): discover sources -> scan -> parse
(AST) -> resolve names/types -> reify generics -> lower each body to linear form,
in a loop: lower control flow to labels/gotos -> simplify the linear form -> lower
nested expressions to temporaries -> (round repeats while anything changed) ->
fold nested blocks into their parents -> give the lowered declarations their types
-> move the lowering's own slots to the top of the body, which lets the folding
fold the rest -> lower to C++ -> amalgamate. The emitters only know the linear
statement forms (`Stmt.Label`/`Goto`/`IfTrue`/`IfFalse`/`Block`), expressions no
deeper than one operation, and declarations that carry a type; structured
`if`/`while` never reach emission (`for` and `when` were desugared while parsing).
The two phases are `linear::lowerForEmission` and `linear::finishForEmission`, with
`sema::inferTypes` between them (`impl_specs/linear-lowering.md`, "The pipeline").

Key design points:

- **AST carrier is `AstXmlNode`** (see `impl_specs/ast-xmlnode.md`): one uniform
  node; `name` is an `AstNodeKind` (the structural role), `kind` an
  `AstNodeCategory` (the schema's category), an attribute key an
  `AstNodeAttributeKind` - all enums, in `cppsrc/rtl/astxml.kt` - so every test
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
  templates (`listops.hpp`, `strops.hpp`, `dictops.hpp`, `span.hpp`, `fs.hpp`).
- **Prelude**: `cppsrc/rtl/*.kt` is implicitly in scope everywhere; its
  method bodies are NOT emitted (behavior lives in the RTL C++ headers).
- **Modules/packages** (see `specs/modules.md`): a **module is a directory**, a
  **package is a namespace** declared by a mandatory `package a.b.c` as each
  file's first declaration. Imports are style (A): the compiler scans module
  roots and includes every `.kt`; `import pkg` only makes `pkg` visible
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

## 5. Invariants and how they are verified

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

## 6. Change protocol (read before editing)

- **Two rings**: any compiler behavior change must be made in BOTH the C++
  implementation and the matching `.kt` mirror (scanner, parser, sema,
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
- **Prefer the container `for` to an index walk.** `for (*x in xs)` binds a *pointer* to
each element - no copy per iteration, and a write through `x` reaches the element - while
`for (x in xs)` binds a copy; the indexed form `for ((*x, i) in xs)` adds a counter that
counts iterations (a `continue` still advances it). Convert a `while (i < xs.size())`
walk only when `i` is used *only* to index `xs`, `xs` is not mutated in the body, and the
element is used as a *place* (an assignment target, a member access, or an argument where
a `*T` is wanted) - a pointer where a value was meant is the one bug this rewrite
produces. Keep `while` + `Span<T>` for the rest (the index used elsewhere, the container
re-read or mutated, an element read as a value).
- **Prefer `when` to a chain of `if`s on one local** (`when` is the language's only
selection statement and it evaluates its subject once - so the subject must be a plain
local no arm assigns to), and a **`listOf<T>(a, b, c)` literal to a run of `append`s**
(one `Pack` instruction, inline up to four elements; `List<T>(n)` is the RTL's *count*
construction and stays that). A parameter may be a copy (`List<T>`) or a borrow
(`*List<T>`) without the callers caring: a call argument's handle is inferred when the
types match (`specs/functions.md`, "Handles at a call"), so `f(xs)` is `f(&xs)` for a
borrow parameter and a read-through for a by-value one.
- **Iterate a container of aggregates with the pointer form** - `for (*x in xs)` /
  `for ((*x, i) in xs)` - not `for (x in xs)`: the value form binds a *copy* of each
  element, the pointer form binds its place (`*T`, through the container's prelude
  `smToYieldPtr`) and costs what the hand-written `while` + `*xs[i]` costs, measured to
  the millisecond (`impl_specs/for.md`). The compiler's own statement/child walks use
  it.
- **Borrow AST-carrying structs; don't copy them.** A `val x: T = list[i]` where
  `T` holds an `XmlNode` (`CgFn`, `CgNativeExt`, `CgInput`, `SemaInput`, ...)
  deep-copies the subtree. In read-only loops use a pointer into the owner
  (`val fn: *CgFn = *this.functions[i]`, `val decl: *XmlNode = *fn.decl`) and let
  every function that only reads take `*T`; a by-value copy *per lookup* makes
  emission quadratic in the function/AST count (the `CgFn` copies did: the Debug
  `stage1_check` cost 6.6-8.7 s before they were removed, 2.1 s after).
- **Don't spell `copy(...)`; the compiler converts where the destination says so.**
  A `val x: Str = h` where `h: *Str` reads through by itself, a call argument converts
  against its parameter, and a construction's arguments convert against the class's
  fields - so a `copy` is at best the identity (`copy(v)` of a value, which the emitter
  prints as `(v)`) and otherwise the conversion the extractor would have inserted. The
  compiler's own ring spells none of them. The `*` is written for a *binding* that
  outlives its expression (`val p: *T = x`) and for `for (*x in xs)`, and nowhere else.
- **Build a fixed shape with `fmtStr`, a run of appends with `reserve`.**
  `fmtStr("|::|", a, b)` writes one buffer where `a + "::" + b` builds three
  (`specs/built-in-types.md`); its trailing arguments pack into the `*List<Str>` like any
  pack-taking call, so there is no `listOf` to write. For a join, `out.reserve(len)` then
  `out.appendStrPtr(part)` per part - `out = out + part` rebuilds the buffer per part
  (measured 88x on a 200 KB result).
- Every `.kt` file must start with a mandatory `package`; update `import`
  lines to package names when adding files.
- **User-visible changes update the docs.** `README.md` (the status paragraph),
  `docs/state-of-the-field.md` (what works, what is rough, the numbers) and
  `docs/language-tour.md` (syntax) carry claims a reader will check; a change that
  makes one of them false is not finished until it is updated.
- **Do not commit** unless the user explicitly asks.

## 7. Language features currently implemented

Scalars (`Int8..64`, `Float32/64`, `Char`, `Bool`), `Str` (with a method library:
`find`, `substr`, `startsWith`, `endsWith`, `replace`, `toInt`, `toFloat`,
`charAt`, `trim`, `split`, `toUpper`, `toLower`, `isEmpty`, `indexOf`,
`lastIndexOf`); `List<T>` (with `append`, `removeAt`, `removeRange`, `insert`,
`clear`, `contains`, `sort`, the literal `listOf<T>(a, b, c)` - one `Pack`
instruction, inline up to four elements - the count constructions `List<T>(n)` /
`List<T>(n, value)`, and a call whose last parameter is a `List<T>`/`*List<T>` packing
its trailing arguments, `specs/functions.md`); `Dictionary<K,V>` (`get`/`has`/`insert`/`remove`/
`keys`/`values`/`size`/`clear`); `Opt<T>`, `Res<T>` (with `Res<T>.ok/.err`,
`Opt<T>.some/.none`); `Span<T>` (a borrowed view: pointer + length); `XmlNode`/`Attribute`;
`data class` (with methods), `enum class` (with `toInt`/`fromInt`), `typealias`
(incl. generic and function types); functions incl. extension functions and
`native fun`; `val`/`var` (locals, and at file level **static storage** -
`specs/statics.md`); `if`/`else`, `when`, `while`,
`break`/`continue`, `return`; the bitwise operators `& | ^ << >>` (precedence: bitwise
binds *tighter* than a comparison, Python's order, `specs/built-in-types.md`);
compound assignment (`+= -= *= /= %= &= |= ^= <<= >>=`) and the step
statements (`i++`, `i--`), which update a place in place - the place is located
once and nothing is copied (`specs/memory-model.md`); `null`; memory operators `&T`/`*T`/`copy`;
lambdas with by-value capture; generics reified via C++ templates; modules and
packages. `yield` and `for` are implemented in **both rings**: both scan `..`/`yield`,
parse `..T`, `yield e` and every `for` form (all desugared in the parser), both report a
`for` over a non-machine (`stress/diagnostic-for-not-a-machine`), both lower the
machine (`linear/Yield.cpp` / `Yield.kt`) and both emit it
(`emitYieldable`/`emitMachine` in `Codegen.cpp` and `Codegen.kt`); `stress/yield`
runs the whole thing through the self-hosted compiler. Two things stay out of
`cppsrc/**` and `tests/fixtures` on purpose: `yield` needs a machine whose body lives
in a method, and the vocabulary is `..T`, `yield e`, `for (v in m)` /
`for ((v, i) in m)` and their pointer forms `for (*v in m)` / `for ((*v, i) in m)`
(the second wrap, `smToYieldPtr`, hands out `*T` places - no copy per iteration;
`specs/functions.md`, `impl_specs/yield.md`, `impl_specs/for.md`, `stress/for-pointer`);
`tools/_ring/` is the probe a ring comparison runs on.

## 8. TODOs / deferred

**The agreed order for the next work** (the user's plan, recorded here so it survives a
session):

1. ~~**`linear/LinearForm.kt`**~~ **done** - the IL in the Simse ring (model,
   signature table, printer, extractor) plus the backend in `Codegen.kt`
   (`emitIlBodyText`, `ilEmitOps`, the closure classes). The oracle held:
   `--showLinearRepresentation` over `cppsrc` reports the same counts in both rings
   (502 bodies, 0 not expressible), and their IL-emitted outputs are byte-identical.
2. ~~**IL codegen as the default in BOTH rings at once**~~ **done**: the IL's text is
   what codegen emits (a lambda is a closure class now, not `[=]`). The statement
   emitters and the two flags that reached them (`--statementsCodegen`,
   `--linearCodegen`) have since been deleted - the IL is the only codegen, and a body
   it cannot spell is a hard error.
3. ~~**`yield` in the Simse ring**~~ **done**: `Codegen.kt`'s
   `emitYieldable`/`emitMachine`/`ilMachineMethod`, and the builder contract they needed
   (`linCondJump` re-roots a *synthesized* condition under `Cond` - the C++ ring holds it
   structurally, so the gap was invisible there). `stress/yield` covers both `for`
   forms, `continue`/`break`, `next()` and `advance(*T)`, through the self-hosted
   compiler.
4. ~~**`smToYield`**~~ **done** (`impl_specs/for.md`): `for (x in c)` becomes
   `c.smToYield()`, the prelude's `List<T>.smToYield(): ..T` is written in Simse, a
   machine is its own identity (the compiler's, since `..T` cannot be a parameter type),
   and this is what made generic machines and the receiver-in-the-machine work land.
   `Array<T>` and `Span<T>` landed the same way, which is where the machine class
   started carrying its receiver's name (`List_smToYield_yieldable`) and the
   prelude-body rule grew its per-receiver half. What is left of the feature is
   `Dictionary` (its element type is the open question) and ranges, `for (i in (2 .. 5))`.

Do these only when asked; roughly prioritized:

1. **Commit the work when asked.** The T35-T40 performance work is committed
   (`d5de0f8`); anything after it is uncommitted as usual - never commit unless the
   user asks.
2. **Stage-2 self-host**: have `simse_stage1` compile itself a second time and
   verify the fixed point again (stronger bootstrap proof). Also broaden
   `stage1_check` to sweep more fixtures.
3. **Deferred language features** (spec'd or implied, not implemented):
   multiple `package` declarations per file (file-split shape); external-module
   manifests/versions/transitive resolution; range/`foreach` iteration over
   containers (`for` exists, but only over a machine - `impl_specs/for.md`);
   reference captures
   and explicit capture lists; `when` pattern labels (`is Type`, `in 1..5`); string
   interpolation;
   interfaces/virtual dispatch; method overriding; default parameter values;
   `unsafe` blocks / raw-pointer escape rules; **static storage** - file-level
   `var`/`val` (done, `impl_specs/statics.md` slice 1) and `object` declarations
   (generic-capable, slices 2-5), specified in `specs/statics.md`; the `object`
   slices are the piece the RTL needs to move per-type statics such as
   `arrayEmpty<T>()`'s empty block out of hand-written C++.
   The user-facing ordering of these gaps - what a program author is blocked on,
   what gates each phase, and the features not in this list yet (`for`,
   interpolation, closed unions + exhaustive `when`, static protocols, `Set`, byte buffers,
   JSON codegen, sockets/HTTP, Linux/macOS, user FFI) - is
   `impl_specs/user-language-roadmap.md`, with its own non-goals and open
   questions.
4. **RTL spec convergence**: the RTL is a shim (`Str` is the spec-shaped inline
   `SmString`, `List` is `SmallVector<T, 4>`, `Dictionary` is the RTL's own
   `SmDictionary`; no `[refcount][typeId]` header). Divergences are
   documented in `impl_specs/rtl-abi.md`; the eventual target must match
   `specs/`.
5. **Sema is quadratic in a single file's declaration count** (T40,
   `impl_specs/capability-matrix.md`): every per-file stage is linear except sema
   (408 -> 6,155 ms when a one-file input grows 3-4x), so a single 30k+ line file
   would take seconds while many small files stay linear. Suspects in
   `cppsrc/sema/Sema.kt`: `collectGlobal`'s get-append-insert copies into
   `globalFunctions`/`packageDecls`, `buildVisible` re-running per file, per-call
   overload scans (`analyzeCall`, `markExtensionUsed`), `lookupValue`'s scope walk.
   Fix in both rings when a real workload needs it.
6. **Ergonomics/robustness**: lambda typing is conservative (a body/return
   mismatch surfaces as a C++ compile error, not a Simse diagnostic); generic
   type aliases aren't expanded when resolving an expected callable type; `Str`
   is byte-oriented (ASCII case mapping); the single ~190 KB amalgamated TU may
   need attention as the compiler grows.
7. **SmDictionary's hit lookups are its weak spot** (T41,
   `impl_specs/capability-matrix.md`): the RTL's dictionary packs the cached hash
   and chain link next to the key and value, so iteration is a pointer walk (~8x),
   deep copies ~5x and miss lookups ~1.6x faster than the `std::unordered_map` it
   replaced, and ~6% faster end to end on the self-transpile; the same indirection
   (bucket-as-row-index, a second dependent load) leaves hit lookups on
   cache-resident tables ~1.8x slower. `SmallVector::operator[]`'s inline/heap
   branch per access and the cached-hash pre-test on hits are the other suspects.
8. **`Dictionary` has no in-place access to a stored value** (T42): `get` copies
   the value out and `insert` writes it back, so the 1BRC aggregation pays two
   lookups per line where the C++ baseline pays one - the last gap to the Bun
   reference (1.6x). A `getPtr`/`withValue`-style native is the
   next library change; it is a library gap, not a language one.
9. **Lambda bodies are the last declarations without a type.** (What the inference
   proves, and why, is in `impl_specs/linear-lowering.md`, "Type inference on the
   lowered body" - not repeated here.) To close it: run `sema::inferTypes` on a
   lambda's body too, with a `sema::Body` built from the lambda's own parameters and
   return type; small, at the two `lambda()` call sites, and it is also the last
   thing keeping a lambda body from flattening (item 10). The rest of the item:
   - **The emitter could consume expression-level types** instead of guessing them
     (`inferType`/`memberCallReturn`/`findNativeExt` shrink to lookups); the
     inference already resolves more than the emitter asks it for.
   - **A call-aware lowering step** (the user's suggestion, now smaller than it
     looked). A value receiver is a raw pointer (T47) and receivers keep the `Path`
     slot, so the case that made this urgent - a path argument whose mutations must
     reach the caller's object - is already handled: the emitter takes the address
     (`simse_addressOf(x)` / `.get()` for a counted reference) and the lowering never
     binds a receiver. What a callee's *signature* could still add is spelling for
     arguments the lowering cannot type on its own (a `null` argument, whose type
     has to come from the parameter) and knowing which parameters are pointers, so
     that a `*T` parameter decides place-vs-value instead of the argument's shape.
     Not blocking anything today.
   - The three recorded gaps (a prelude struct method such as `Span.size()`, a native
     extension called as a plain function, a call through a function-typed local).
10. ~~**A flat body still keeps the program's own scopes**~~ **done**: every declaration
   of a body moves to the top of it (`hoistSlots`), shadowed names are renamed first
   (`renameShadowed`, `_sm_<name>_<n>`), and the body is one scope with no blocks - the
   IL's own frame is flat, and the statement path agrees (515 of 528 bodies over
   `cppsrc` are byte-identical between the two paths; 0 not expressible). What is left:
   - **A declaration the inference cannot spell in full** (an untyped slot, a `*?`
     place) stays where it is, and the block around it with it: that is the only
     reason a body-level block survives (26 in the emitted compiler, against 21 before
     the change - the rest is the statement path's own residue). Closing it means
     typing the three recorded inference gaps (item 9).
   - **The placement in the body is the cost knob**: every slot is live for the whole
     body (no liveness reuse). On the 1BRC the measurement says it costs nothing
     (1323 ms against 1322 ms for the same program emitted before this pass), so the
     knob is not worth turning until a workload says otherwise; the rule for a tighter
     placement is the one `spliceIsSafe` already computes.
   - **Run the inference on lambda bodies** (item 9): besides typing them, that is
     what lets a lambda's slots hoist and fold like any other body's.

## 9. Gotchas

- `LNK1168` on build = a running `simse*.exe` holds the output; kill it first.
- `build.bat` defaults to a debug build (`/MDd`), so the MSVC debug STL asserts are
  live: bad input such as a directory passed where a `.kt` file is expected can
  pop an assert dialog instead of a diagnostic. Use `build.bat --release`
  (`/O2 /DNDEBUG`, release libs) for a build with the asserts compiled out.
- Scanning `.` (no args) walks `tests/fixtures/*`, which intentionally contain
  bad input and will make `simse_transpile` exit non-zero. Pass `--root cppsrc`
  (or another clean module root) instead.
- Prelude `.kt` bodies are not emitted; put behavior in the RTL C++ headers.
- Source-map comments embed the path as given, so absolute and relative runs
  differ — cosmetic.
- Goldens are sensitive to line-number shifts; regenerate with `--update` when
  intentionally changing sources, then confirm check-mode passes.
- **A jump is not always a sibling of its label.** The expression lowering wraps a
  jump in the block that carries its temporaries, and `break`/`continue` jump out of
  the body they are written in, so any pass that asks "is this label used?" must look
  *through* blocks (`jumpsTo`/`linJumpsTo`), and any pass that moves a declaration
  across a level must ask whether a jump at *any* depth now bypasses it - that is what
  `flattenBlocks`' rule and the `pos (J) < pos (D) <= pos (L)` condition are about.
  The symptom of getting the first one wrong is MSVC `C2094: use of undefined label`
  in the *generated* C++, which no differential catches (both rings agree on the
  broken output) - the build's stage-1 compile is what catches it.
- A pass that is `changed`-gated must report honestly: `linear::lowerForEmission`
  loops until a whole round changes nothing, so a stage that always claims a change
  never lets the round end (and one that never claims one stops the pipeline early).
- **A declaration with no initializer is a legal statement now** (`hoistSlots` makes
  one per slot), and the emitter prints it as `T name;`. Nothing else may assume a
  `VarDecl` has an `init`.
- The slot prefix is `_sm_expr` (8 characters), and
  `Str::compare(pos, count, prefix)` takes the *count*: a wrong count compares
  different bytes and returns non-zero with no error anywhere, so a name test written
  by hand can quietly match nothing. (That is exactly what happened once: the C++ ring
  hoisted nothing while the Simse ring hoisted everything, and only T23's two-step
  bootstrap showed it.)
- `&x` on a local **boxes a copy** (mutations through the box are lost); `*x`
  **borrows** and aliases the original. Take `*T` for out/aggregate parameters
  (the 1BRC's `tally` takes `*Dictionary<Str, Stats>`), never `&x`.
- A handle's native operations must be **struct methods**, not free natives: the
  emitter calls `stream.readLine()` on a `*FileStream` as `(*stream).readLine()`.
  Same for `Span`/`XmlNode`.
- After changing **any RTL header**, rebuild the CMake folder you are about to
  build against (`cmake-build-<config>/_msvc_build.bat`) *before* `bun build.js`:
  `build.js` links the prebuilt `simse_lib`/`simse_native`, and a stale library
  built against an older header is not a link error but a silent *layout* mismatch
  (a `Str` laid out with another inline capacity, say) - the program then reads zero
  rows or crashes. `build.js --release` needs the release libs, the stress harness
  the debug ones.
- `bun tools/stress.js` prefers `./simse.exe` (the self-hosted ring) and falls
  back to `cmake-build-*/simse_transpile.exe`; `--simse <path>` with the CMake
  `simse.exe` is not the same CLI (it takes file arguments, not `--root`) and will
  fail the corpus.
- **The Simse ring's nodes carry roles**, and a lookup is by role: a type read out
  of a declaration (`xmlChild(decl, ReturnType)`) comes back rooted as
  `ReturnType`, so putting it where a `Type` child belongs needs a re-root
  (`semReRole`, the counterpart of the emitter's `renameRole`). Symptom when it is
  missing: the pass annotates correctly and the emitter emits `auto` anyway, because
  `xmlChild(stmt, AstNodeKind.Type)` does not see a `ReturnType`-rooted child.
- **A data-class field cannot name another package's type** in the Simse ring: the
  amalgamated file emits the packages in its own order, so the field's type may not
  be declared yet (`'facts': unknown override specifier`). Program-level tables
  passed between stages go as **parameters** (that is why the emitter threads
  `SemFacts` through `emitFunctions`/`emitFunction`).
- **Every size, length and index in the RTL is the language's `Int`** (32-bit
  signed) and `Str::npos` is `-1`; a `std::size_t` appears only where the standard
  library's own signature needs one (allocation, `mem*`, `char_traits::length`), as
  an explicit widening cast. That is what keeps the compiler's own build free of
  C4267 (`size_t` to `Int`) warnings - do not reintroduce an unsigned size type to
  satisfy a std API; cast at that one call instead (`impl_specs/rtl-abi.md`).
- `stress/<case>/expected.cpp` is compared byte for byte but **`--update` never
  rewrites it**: copy `stress/.work/<case>/out.cpp` over it by hand.
- **A value receiver is `T* self`** (T47): method signatures, call sites
  (`ns_f(simse_addressOf(x))`) and bodies (`self->field`, a bare `this` reading as
  `(*self)`) all follow it, while `this: &T` stays `std::shared_ptr<T> self` and
  `this: *T` stays `T* self`. The hand-written differential drivers
  (`tests/*_simse_main.cpp`) call emitted receiver functions directly, so they pass
  `&scanner` - and a *native* extension is the one call the emitter passes the
  receiver expression to unchanged, because the host's C++ signature decides.
- **A borrow of a temporary lasts only for its call.** `*f()` lowers to
  `simse_addressOf(f())`, whose contract is exactly that
  (`cppsrc/rtl/types.hpp`) - so it must stay inline in the expression it is passed
  to. Hoisting it into a variable (which the expression lowering would otherwise do,
  since a value position is one operation deep) leaves a pointer to a dead
  temporary; `exprIsBindable` is the guard that keeps it where it is.
- **`for` and `yield` are in both rings.** `for` is desugared in the
  *parser* (`Parser::parseFor`, reached through `parseStmtInto`, the one statement
  slot that expands to several), so no stage downstream has a `for` statement kind -
  which is also why `sema`'s "a `for` needs a `smToYield`" check keys on the template's
  `_sm_for<n>` name and reads the wrap call underneath it: that prefix is the only marker
  left of the construct. Two
  consequences bite: the template's machine is a *local*, so a machine can never be a
  field, and a `for` inside a body that yields therefore has no field to live in
  (reported, not silently miscompiled); and a machine's C++ class is the creating
  function's, so `..T` stays unspellable - `sema::TypeInfer` carries the machine's two
  methods (`next` -> `Opt<T>`, `advance` -> `Bool`) precisely so a loop variable is a
  typed binding rather than an `auto` the emitter would resolve the wrong native for.
