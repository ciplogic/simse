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

Toolchain: MSVC (arm64) + `cl.exe`, C++20, and the `bun` runtime for the
harnesses. There is no build system to configure: the compiler is Simse, and the
only hand-written C++ is the runtime it is compiled against (`cppsrc/rtl`: the
headers plus the one translation unit `cppsrc/rtl/native.cpp`) and the published
bootstrap `cppsrc/simse_bootstrap.cpp`.

```sh
# build (from the repo root): cppsrc -> ./simse_out.cpp -> ./simse.exe
./build.bat                             # debug (/MDd)
./build.bat --release                   # /O2 /Ob3 /DNDEBUG + /GL (LTCG at link; the default)
./build.bat --release --no-lto          # skip whole-program optimization: quicker to build
./build.bat --release --pdb             # + /Zi /DEBUG (a .pdb in build/), for a profiler
./build.bat my_simse.exe                # same, different executable name
./build.bat --cpp other.cpp --exe x.exe # compile an existing amalgamation
./build.bat --help                      # all options (see build.js)

# The transpile step runs ./simse.exe when it exists - the compiler builds itself.
# On a fresh checkout there is no compiler yet, so the build first compiles the
# published bootstrap with cl.exe alone and uses that (see tools/bootstrap.js).

# the published bootstrap: the same amalgamation, checked in at
# cppsrc/simse_bootstrap.cpp so Simse can be built with a C++ compiler alone
# (docs/getting-started.md, "Build the compiler without a compiler"). Refresh it
# after compiler changes (the file is generated, never hand-edited):
bun build.js --release --out cppsrc/simse_bootstrap.cpp   # also builds ./simse.exe

# how fast does it bootstrap, and does the fixed point hold?
bun tools/bootstrap.js                  # compile the bootstrap, transpile, compare the bytes
bun tools/bootstrap.js --debug          # the same with the debug flags

# the end-to-end stress corpus: one folder per program under stress/, each with
# its expected output; the harness transpiles, compiles and runs every one of
# them with the compiler under test (./simse.exe by default)
bun tools/stress.js                     # or ./stress.bat; see stress/README.md
bun tools/stress.js --list              # what the corpus contains
bun tools/stress.js --filter modules --jobs 4

# the compiler by hand (the compiler *is* the CLI; `--prelude` defaults to the
# relative path cppsrc/rtl, so run it from the repo root)
./simse.exe --root cppsrc -o simse_out.cpp            # the whole compiler
./simse.exe --root stress/hello/src -o hello.cpp      # any project, same form

# the linear IL of every emitted body, on stderr (debug view; the C++ output is
# identical with and without it - impl_specs/linear-il.md)
./simse.exe --root cppsrc -o a.cpp --showLinearRepresentation 2> il.txt

# debugging / profiling the compiler:
./simse.exe --root cppsrc -o prof.cpp --profile   # instrumented profiler in the program
./build.bat --release --pdb                       # optimized + symbols, for the VS profiler
```

The verification loop after a compiler change: `./build.bat --release`,
`bun tools/stress.js`, then `bun tools/bootstrap.js` - the two-step property is
that the compiler built from the published bootstrap must reproduce that file
byte for byte, and that check is the one that catches a change whose emitted C++
depends on the compiler that emitted it. `bun tools/smgen.js` joins the loop when a
`native(...)`/`@SmGen(...)` spelling is touched: it checks that the two spellings of one
declaration emit the same C++. When the change *is* visible in the
emitted C++, refresh the published file (`bun build.js --release --out
cppsrc/simse_bootstrap.cpp`) and commit it with the source.

**If `simse.exe` is running, linking it again fails with `LNK1168` - kill it first.**
`--root cppsrc` scans the whole source tree (the prelude under `cppsrc/rtl` is
excluded as prelude), so the amalgamation contains exactly one `main` - the
driver's - and `build.bat` can compile it.

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
  `generators.md` (`@SmGen`, the `Sections` sink, and the `res` generator),
  `ast-xmlnode.md`, `linear-lowering.md`, `linear-il.md` (the flat instruction list
  the backend is meant to consume, with its dump), `yield.md` (`yield` as a pure
  lowering to a state machine), `profiling.md` (the `--profile` instrument: one RAII
  timer per emitted body and the table the program prints), `tasks/.
- `cppsrc/rtl/` — the runtime, and the only hand-written C++ besides the bootstrap:
  the headers (`types.hpp`,
  `containers.hpp`, `smstring.hpp`, `strsmallvector.hpp`, `optional.hpp`,
  `functional.hpp`, `result.hpp`, `xml.hpp`, `span.hpp`,
  | `strview.hpp`, `resources.hpp`,
  `fs.hpp`, `filestream.hpp`, `simse.hpp`), the one implementations file `native.cpp`
  (the `native(...)` symbols: file I/O, directory listing, the clock), the
  `_res.md` file that holds the RTL's *generated* C++ - one section per header it came
  from (`strtable`, `timeops`, `listops`, `dictops`, `strops`, and `spanOf`, plus the
  collision fixture's `spanOfEmpty`) with `symbol:`/`emit: always` deciding how a
  declaration reaches it
  (`impl_specs/generators.md`) - AND the
  **prelude** `.kt` files (`rtl.kt`, `Span.kt`, `StrView.kt`, `xml.kt`,
  `astxml.kt`, `fs.kt`, `resources.kt`) declaring the RTL surface - and, where the only
  thing C++ must supply is a raw pointer, *implementing* it: `resources.kt`'s
  `Resources.entries/get/has/count` are Simse over the `Span<ResourceEntry>`
  `resources.hpp` hands out, and `rtl.kt`'s `append`/`toArray`/`toString`/... reach the
  `listops` section of `_res.md` the way `Span.kt`'s `spanOf` reaches `spanOf`.
  The three headers that text came from (`strtable.hpp`, `timeops.hpp`, `listops.hpp`)
  are gone, and so are their `#include`s.
  `List<T>` is `SmallVector<T, 4>`, `Str` is the inline `SmString`
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
  remains (`SIMSE_STR_INLINE_CAPACITY`, `SIMSE_NO_PACK4`) are passed with `--define`
  and reach the amalgamation and `native.cpp` in the same `cl` invocation, so the two
  can never disagree (`impl_specs/rtl-abi.md`). The language's layout model is
  **4-byte packing** (`specs/memory-model.md`): the emitter brackets every
  generated aggregate in `SIMSE_PACK_PUSH`/`SIMSE_PACK_POP`, and `SIMSE_NO_PACK4`
  reverts to the host's default alignment.
- `cppsrc/common/` — `readFile` (a native declared next to the compiler, not in the
  prelude) and `xmlutil.kt`, the XmlNode helpers every stage shares.
- `cppsrc/resources/` — the `_res.md` reader: the format, the join, the discovery,
  and the C++ literal a resource is pooled as (`specs/resources.md`). The runtime half
  of it is `cppsrc/rtl/resources.{kt,hpp}`.
- `cppsrc/lex/`, `cppsrc/parser/`, `cppsrc/sema/`,
  `cppsrc/linear/`, `cppsrc/codegen/`, `cppsrc/compiler/`, `cppsrc/profiling/` -
  the compiler stages, all Simse: scan, parse (AST), resolve/reify, lower to the
  linear form, emit. `sourcegen/` sits beside them: the source generators, one file per
  generator plus the manager (`SourceGen.kt`) and the `Sections` sink (`Sections.kt`),
  which `codegen` calls but which calls nothing back (`impl_specs/generators.md`).
  `linear` is the post-sema
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
  `LinearForm.kt` is the whole thing: the model, the signature table, the
  printer, the extractor - and the backend lives in `Codegen.kt`
  (`emitIlBodyText`/`ilEmitOps`/the closure classes), so **the emitter emits from the IL
  and from nothing else**. `Yield.kt` is the one
  language feature that is nothing but a lowering: `yield` becomes labels, a branch
  field and a class (`impl_specs/yield.md`), and `for` (`Parser.kt`'s `parseFor`, which
  desugars the two forms to a `while` before anything else sees them) is the second
  (`impl_specs/for.md`). Both are parser..emitter: the machine lowering and
  the emitter's `emitYieldable`/`emitMachine`, with a
  *generic* machine a class template and an extension function's receiver a field of it
  (`stress/yield`, `stress/generic-yield`). Iteration is a convention: `for (x in c)` is
  `c.smToYield()`, the prelude writes one per container in Simse (`List`, `Array`,
  `Span`), a machine is
  its own identity, and a prelude body is emitted only when the program reaches it -
  by name *and* by the receiver's type, since a machine's class is named after its
  receiver - so a program that iterates a list carries no array machine
  (`stress/for-container`, `stress/for-array`, `stress/diagnostic-not-iterable`).
- `cppsrc/compiler/Driver.kt` — the CLI and the shared transpile core: it loads the
  prelude set, scans the module roots, parses and analyzes the compilation, and
  amalgamates the result into one C++ translation unit. The compiler *is* this file plus
  the stages it imports; there is no separate C++ driver. It is also where **generated
  Simse sources** enter (`@SmGen("kt", ...)`): after parsing the modules it reads
  `<section>:source` from the program's resources, joins them into one module and parses
  it with `driverParseSource`, so the front end runs again over text that is not a file
  (`impl_specs/generators.md`).
- `stress/` - the end-to-end stress corpus: one folder per Simse project
  (`src/` + `expected.stdout` + optional `args`/`stdin`/`expected.cpp`/
  `expected.transpile-error`), run by `tools/stress.js` against the compiler under
  test (`stress/README.md`).
- `tools/` - the JavaScript harness: `build` is `build.js` at the root (with
  `build.bat`), and this folder holds `stress.js` (the corpus above),
  `bootstrap.js` (compile the published bootstrap, transpile, compare the bytes),
  `smgen.js` (the `native`/`@SmGen` byte-equality check), `vscheck.mjs` (build the Visual
  Studio profiling project, `simse.vcxproj`), and
  `msvc.mjs` (the Visual Studio environment shared by all of them). The remaining
  `_*.mjs`/probe files are the hand-written ring's scratch (A/B benchmarks and shape
  probes); they are not part of any workflow.
- **Profiling**: `bun build.js --release --profile` builds a compiler (or any program,
  via `--profile` on the CLI) whose every emitted body carries an RAII timer; the table
  of inclusive microsecond totals and call counts prints on stderr when the program
  exits (`cppsrc/profiling/`, `impl_specs/profiling.md`). With the flag off the emitted
  file is byte-identical, so it is a tool and not a mode. `--profile` says **where** -
  inclusive totals plus call counts, which is what a sampling profile cannot tell you -
  and `build.bat --release --pdb` is what feeds the VS sampling profiler.
- `benchmarks/` - published measurements; `benchmarks/onebrc/` is the naive 1BRC
  in Simse (`src/main.kt`, each line parsed in place through `StrView`) with the
  C++ STL baseline, the Bun generator/reference (`onebrc.mjs`), the measured
  write-up (`benchmark.md`) and the run commands (`README.md`; data and binaries
  are git-ignored).

## 4. Architecture

**The compiler is Simse.** `cppsrc/**/*.kt` is the whole compiler: scanner, parser,
sema/reification, the linear lowering, the emitter, and the driver. What is
hand-written C++ is only the ground it stands on: the runtime (`cppsrc/rtl`: the
headers plus `native.cpp`, the FFI the compiler and every program call) and the
**published bootstrap** `cppsrc/simse_bootstrap.cpp` - the amalgamation of the
tree, checked in so the compiler can be built by a C++ compiler alone.

The bootstrap property is the invariant that replaces the old two-ring port:
compile the published file, and the compiler it produces must transpile `cppsrc`
back into **the same bytes**. `bun tools/bootstrap.js` checks it; a change that
breaks it has either moved the emitted C++ or left the published file stale.

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
The two phases are `lowerForEmission` and `finishForEmission`
(`cppsrc/linear/Linear.kt`), with the type pass between them
(`impl_specs/linear-lowering.md`, "The pipeline").

Key design points:

- **AST carrier is `AstXmlNode`** (see `impl_specs/ast-xmlnode.md`): one uniform
  node; `name` is an `AstNodeKind` (the structural role), `kind` an
  `AstNodeCategory` (the schema's category), an attribute key an
  `AstNodeAttributeKind` - all enums, in `cppsrc/rtl/astxml.kt` - so every test
  on a node is an integer compare. Attribute *values* are text, children are an
  `Array<AstXmlNode>` (one counted block, count first, the shared empty array for a
  leaf). The language-level `XmlNode` (`specs/xml-node.md`) stays the general
  tree a program builds.
- **Generics are reified via emitted C++ templates** (see
  `impl_specs/reification.md`): distinct Simse instantiations become distinct
  C++ types; `SmallVector<N,T>` maps to `SmallVector<T,N>`.
- **Source generators** (`@SmGen`, see `impl_specs/generators.md`) live in
  `cppsrc/sourcegen/`, one generator per file, and each **registers itself** with one line at
  the end of its own file - `val cppGenRegistered: Bool = registerSourceGen("cpp", cppGen,
  true, true)` - whose file-level static initializer appends it to `sourceGenTable` (the
  table has no initializer of its own: static initializers run in an unspecified order, and an
  append onto storage that starts empty cannot lose one). A generator is a lambda over
  `*SourceGenContext` and is asked three times - `Declare` (resolve the symbol a call
  reaches), `Reparse` (hand back Simse source) and `Emit` (place text, and once more for the
  program itself). It may read and write the AST nodes, the resources and the `Sections` sink,
  and it calls nothing from the compiler's stages: that boundary is what keeps a program
  author's generator from breaking when a compiler API changes.
- **A project file (`simse.md`)** names the modules a project is built from and says which of
  them ship source generators (`specs/simse-md.md`; the manifest half is implemented, the
  extension is not). A root *with* a manifest is scanned as exactly the modules it names; a
  root *without* one is scanned whole, as `--root cppsrc` does. A module that says
  `sourcegen: true` is a hard error naming it for now.
- **Native boundary** (see `impl_specs/native-interop.md`): `native fun` /
  `native("Symbol") fun` declares a function whose body is hand-written C++. A body that
  is a *resource* is `@SmGen("res", section[, symbol])` instead, which is where the RTL's
  own operations live now (`cppsrc/rtl/_res.md`); what is left as `native` is the
  platform's C++ (`native.cpp`) and the type core.
- **Prelude**: `cppsrc/rtl/*.kt` is implicitly in scope everywhere; its
  method bodies are NOT emitted (behavior lives in the RTL's C++, which is a header or a
  resource section).
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

- **The bootstrap fixed point**: the compiler built from the published
  `cppsrc/simse_bootstrap.cpp` transpiles `cppsrc` back into that same file, byte
  for byte (`bun tools/bootstrap.js`). This is the strongest invariant in the repo:
  it ties the published artifact to the sources, and it is what catches an emitted
  byte that depends on which compiler emitted it.
- **Determinism**: transpiling the same inputs twice is byte-identical.
- **The stress corpus stays green**: `bun tools/stress.js` transpiles, compiles
  and runs every program under `stress/` with the compiler under test and compares
  its output (one folder per program, `stress/README.md`). Cases cover the language
  (containers, generics, `for`/`yield`, text, XML, statics, resources), the CLI
  (`main(args)`, diagnostics), and the runtime (file reads, spans, string escapes).
- **A stale compiler is visible**: `tools/bootstrap.js` compares both the freshly
  compiled bootstrap *and* `./simse.exe` against the published file, so a repo whose
  compiler no longer matches its sources says so by name.
- **One declaration, two spellings**: `native("sym")` and
  `@SmGen("cpp", "sym")` are the same declaration and must emit
  the same C++ - `bun tools/smgen.js` compiles the pair (`stress/smgen-native`,
  `stress/smgen-cpp`) and compares their amalgamations byte for byte, after replacing
  the fixture path.

## 6. Change protocol (read before editing)

- **One ring, and it is Simse.** A compiler behavior change is a change to
  `cppsrc/**/*.kt`. There is no C++ mirror to keep in step any more; the runtime
  (`cppsrc/rtl/*.hpp`, `native.cpp`) and the published bootstrap are the only C++,
  and both are inputs to `cl.exe`, not a second implementation.
- After a change: `./build.bat --release` (or `bun build.js --release`), then
  `bun tools/stress.js`, then `bun tools/bootstrap.js` - the fixed point must still
  hold. When the change is visible in the emitted C++, refresh the published file
  (`bun build.js --release --out cppsrc/simse_bootstrap.cpp`) and say so; the
  refresh is part of the change, not a separate commit.
  For anything visible to a program - the runtime, codegen, the driver - add a case
  under `stress/` for behavior that is not yet covered there.
- **Never weaken the fixed point** (and never hand-edit `cppsrc/simse_bootstrap.cpp`: it
  is generated, and it is the one file that has to keep building the compiler).
- **A surface change the running compiler cannot handle needs two builds.** An attribute
  in the *prelude* (`cppsrc/rtl/*.kt`), or a change to what the parser records, is
  visible to the compiler that is transpiling the tree - so build once with the old
  spelling (which is what a stale `simse.exe` sees), then switch the source and build
  again. The same shape applies to `@` itself: it landed in the scanner/parser while the
  compiler's own sources stayed `@`-free, and only a compiler built *from* those changes
  could parse an `@` in the prelude. The published bootstrap never needs a hand-patch:
  refreshing it (`--out cppsrc/simse_bootstrap.cpp`) carries the new scanner and parser.
- **A `_res.md` file under `cppsrc` is part of the compiler**, not of a program: the `res`
  generator reads it *first* while the tree is being compiled (the tree's own files win over
  the compiler's own resources), and at run time the compiler reads it from disk for every
  other program - the second half of the generator lookup, which is why it is what a program
  with no `_res.md` of its own gets the RTL's C++ from (`impl_specs/generators.md`). Edit one
  and the next build is the one that sees it - and the amalgamation changes, so refresh the
  bootstrap too. Because the tree's own file wins, moving a header's C++ into a `_res.md`
  section needs no staged build (the *source* half - a declaration that changes shape in the
  prelude `.kt` - still does). Its sections are all marked `!`: the text is compiled into the
  compiler, so embedding it in the compiler's own string pool as well was a second copy of it
  (23 KB), and the compiler's run-time `Resources` table is now empty - that type is a
  program-facing API.
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
- **Borrow AST-carrying structs; don't copy them.** A `val x: T = list[i]` where `T`
  holds an `XmlNode` (`CgFn`, `CgNativeExt`, `CgInput`, `SemaInput`, ...)
  deep-copies the subtree. In read-only loops use a pointer into the owner
  (`val fn: *CgFn = *this.functions[i]`, `val decl: *XmlNode = *fn.decl`) and let
  every function that only reads take `*T`; a by-value copy *per lookup* makes
  emission quadratic in the function/AST count (the `CgFn` copies did: the Debug
  `stage1_check` cost 6.6-8.7 s before they were removed, 2.1 s after).
- **Read a node's own attributes once, into the collected struct that everyone scans.**
  The Simse ring's node is a *uniform* attribute list, so `xmlAttr(node, Name)` is a scan;
  the hand-written ring's `ast::Decl` has real fields, so `decl->name` is a load. Porting a
  lookup walk verbatim therefore turns each field read into a scan *per candidate per call
  site*: the emitter's function lookups (`findFunction`, `findReceiverFnByName`,
  `findExtensionFn`, `memberCallReturn`, `functionPackage`, `reachesPreludeBody`) did that
  and a profile put `xmlAttr` at **37%** of the self-transpile, ~7% of it real work
  (T76 in `impl_specs/capability-matrix.md`). The fix is the shape of the collected struct
  (`CgFn.name`/`isNative`/`hasBody`, filled once in `addFunction`), not the lookup loop:
  rewriting `xmlAttr`'s own walk measured *neutral*. The IL backend's own per-declaration
  rescans (`ilJumpCrossing` rebuilt a label map and walked the body per declaration, and
  again per block) were hoisted the same way - one `List<Int>` per body, the crossing kept
  with the block - and measured *neutral* too, with byte-identical output: the count of
  reads, not the shape of one read, is what a profile of this ring is telling you about.
  The remaining lever is therefore a **borrowed `xmlChild`** (`*AstXmlNode`, it returns a
  176-byte copy today) - and even that only where the copy is the *per-read* cost.
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
`native fun`; **attributes** (`@Identifier` + `@SmGen`, one per declaration, methods
only: `specs/attributes.md`, `impl_specs/generators.md`) with the `cpp` (headers), `res`
(C++ from a resource) and `kt` (generated Simse source) generators, and the `Sections`
sink; `val`/`var` (locals, and at file level **static storage** -
`specs/statics.md`); `if`/`else`, `when`, `while`,
`break`/`continue`, `return`; the bitwise operators `& | ^ << >>` (precedence: bitwise
binds *tighter* than a comparison, Python's order, `specs/built-in-types.md`);
compound assignment (`+= -= *= /= %= &= |= ^= <<= >>=`) and the step
statements (`i++`, `i--`), which update a place in place - the place is located
once and nothing is copied (`specs/memory-model.md`); `null`; memory operators `&T`/`*T`/`copy`;
lambdas with by-value capture; generics reified via C++ templates; modules and
packages. `yield` and `for` are implemented end to end: the scanner reads `..`/`yield`, the
parser handles `..T`, `yield e` and every `for` form (all desugared in the parser), a
`for` over a non-machine is a diagnostic (`stress/diagnostic-for-not-a-machine`), the
machine is lowered in `linear/Yield.kt` and emitted by
`emitYieldable`/`emitMachine` in `Codegen.kt`; `stress/yield`
runs the whole thing through the compiler. Two things stay out of
`cppsrc/**` and the corpus on purpose: `yield` needs a machine whose body lives
in a method, and the vocabulary is `..T`, `yield e`, `for (v in m)` /
`for ((v, i) in m)` and their pointer forms `for (*v in m)` / `for ((*v, i) in m)`
(the second wrap, `smToYieldPtr`, hands out `*T` places - no copy per iteration;
`specs/functions.md`, `impl_specs/yield.md`, `impl_specs/for.md`, `stress/for-pointer`).

## 8. TODOs / deferred

**The agreed order for the next work** (the user's plan, recorded here so it survives a
session). The entries below marked done are the *log* of the port to the single Simse
ring: they name C++ files that no longer exist, and their evidence lines are history.

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
   forms, `continue`/`break`, `advance()`/`value()`, through the self-hosted
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

1. **Continue the resource migration** (T29 in `impl_specs/roadmap.md` did `strtable`,
   `timeops`, `listops`, `dictops` and `strops`). What is left is the pair that is
   declarations over `native.cpp` bodies (`fs.hpp`, `filestream.hpp`): the declarations can
   move, the bodies cannot until the language has file/string APIs of its own - and unlike
   the five that moved, `native.cpp`'s own translation unit needs those declarations, so a
   header has to stay for it either way. The type core (`types.hpp`, `containers.hpp`,
   `smstring.hpp`, `smdictionary.hpp`, `span.hpp`, `strview.hpp`, `strsmallvector.hpp`,
   `optional.hpp`, `result.hpp`, `functional.hpp`, `xml.hpp`, `astxml.hpp`) stays: it is
   what the amalgamation is compiled *against*, and some of it needs language features
   that do not exist yet (statics in an object, a ref-counted layout).
2. **Commit the work when asked.** The T35-T40 performance work is committed
   (`d5de0f8`); anything after it is uncommitted as usual - never commit unless the
   user asks.
3. **Stage-2 self-host**: the fixed point runs through the published bootstrap
   (`tools/bootstrap.js`: compile it, transpile `cppsrc`, compare the bytes). A second
   generation (compile the bootstrap's own output, compare again) would strengthen the
   proof; `tools/stress.js` could also grow cases.
4. **Deferred language features** (spec'd or implied, not implemented):
   attributes on types/fields/parameters/statements, stacked attributes, and
   per-instantiation generators (`@Json`, enum-to-string, int-to-enum -
   `impl_specs/generators.md`'s "Deferred" and roadmap T27);
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
5. **RTL spec convergence**: the RTL is a shim (`Str` is the spec-shaped inline
   `SmString`, `List` is `SmallVector<T, 4>`, `Dictionary` is the RTL's own
   `SmDictionary`; no `[refcount][typeId]` header). Divergences are
   documented in `impl_specs/rtl-abi.md`; the eventual target must match
   `specs/`.
6. **Sema is quadratic in a single file's declaration count** (T40,
   `impl_specs/capability-matrix.md`): every per-file stage is linear except sema
   (408 -> 6,155 ms when a one-file input grows 3-4x), so a single 30k+ line file
   would take seconds while many small files stay linear. Suspects in
   `cppsrc/sema/Sema.kt`: `collectGlobal`'s get-append-insert copies into
   `globalFunctions`/`packageDecls`, `buildVisible` re-running per file, per-call
   overload scans (`analyzeCall`, `markExtensionUsed`), `lookupValue`'s scope walk.
   Fix when a real workload needs it.
7. **Ergonomics/robustness**: lambda typing is conservative (a body/return
   mismatch surfaces as a C++ compile error, not a Simse diagnostic); generic
   type aliases aren't expanded when resolving an expected callable type; `Str`
   is byte-oriented (ASCII case mapping); the single ~190 KB amalgamated TU may
   need attention as the compiler grows.
8. **SmDictionary's hit lookups are its weak spot** (T41,
   `impl_specs/capability-matrix.md`): the RTL's dictionary packs the cached hash
   and chain link next to the key and value, so iteration is a pointer walk (~8x),
   deep copies ~5x and miss lookups ~1.6x faster than the `std::unordered_map` it
   replaced, and ~6% faster end to end on the self-transpile; the same indirection
   (bucket-as-row-index, a second dependent load) leaves hit lookups on
   cache-resident tables ~1.8x slower. `SmallVector::operator[]`'s inline/heap
   branch per access and the cached-hash pre-test on hits are the other suspects.
9. **`Dictionary` has no in-place access to a stored value** (T42): `get` copies
   the value out and `insert` writes it back, so the 1BRC aggregation pays two
   lookups per line where the C++ baseline pays one - the last gap to the Bun
   reference (1.6x). A `getPtr`/`withValue`-style native is the
   next library change; it is a library gap, not a language one.
10. **Lambda bodies are the last declarations without a type.** (What the inference
   proves, and why, is in `impl_specs/linear-lowering.md`, "Type inference on the
   lowered body" - not repeated here.) To close it: run `sema::inferTypes` on a
   lambda's body too, with a `sema::Body` built from the lambda's own parameters and
   return type; small, at the two `lambda()` call sites, and it is also the last
   thing keeping a lambda body from flattening (item 11). The rest of the item:
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
11. ~~**A flat body still keeps the program's own scopes**~~ **done**: every declaration
   of a body moves to the top of it (`hoistSlots`), shadowed names are renamed first
   (`renameShadowed`, `_sm_<name>_<n>`), and the body is one scope with no blocks - the
   IL's own frame is flat, and the statement path agrees (515 of 528 bodies over
   `cppsrc` are byte-identical between the two paths; 0 not expressible). What is left:
   - **A declaration the inference cannot spell in full** (an untyped slot, a `*?`
     place) stays where it is, and the block around it with it: that is the only
     reason a body-level block survives (26 in the emitted compiler, against 21 before
     the change - the rest is the statement path's own residue). Closing it means
     typing the three recorded inference gaps (item 10).
   - **The placement in the body is the cost knob**: every slot is live for the whole
     body (no liveness reuse). On the 1BRC the measurement says it costs nothing
     (1323 ms against 1322 ms for the same program emitted before this pass), so the
     knob is not worth turning until a workload says otherwise; the rule for a tighter
     placement is the one `spliceIsSafe` already computes.
   - **Run the inference on lambda bodies** (item 10): besides typing them, that is
     what lets a lambda's slots hoist and fold like any other body's.

## 9. Gotchas

- **A resource section marked `!` is read and not carried, and one marked `*` is binary**
  (`specs/resources.md`, "Markers"): `!` means the compiler reads the entries and the program
  does not store them - what a `.md` file holding *code* wants, since the code is compiled in
  and the text would otherwise be a second copy of it - and `*` means the value as written is
  **lower-case hex that stands for the bytes**, decoded on the way in, so what downstream sees
  is an ordinary `Str` that may hold a `\0` in the middle (the emitter escapes every
  non-printable byte as three-digit octal, and `cgLiteralByteLength` counts that as one byte).
  Both may be written on a **section title or on one entry's key**, together and in either
  order (`!*Hidden`), and the marker run is consumed with the whitespace around it - so a
  lookup by spelling cannot tell whether anything was marked, and there is no way to take a
  marker back (a marked section marks every entry under it).
- **An XML comment cannot contain `--`**, so `simse.vcxproj`'s comments cannot spell a
  command-line flag: the debugger argument it sets (`LocalDebuggerCommandArguments`, the
  `root` option over `cppsrc`) has to be described in words there. It is worth knowing before
  editing that file - MSBuild fails the whole load with `MSB4025` otherwise. `bun
  tools/vscheck.mjs [Debug|Release]` builds the project from the command line, which is how a
  change to the RTL's includes or to the amalgamation's shape can be caught without opening
  the IDE.
- **The emitter's type table is flat by name**, so a prelude type and a program type of
  the same name collide: `cppsrc/resources/Resources.kt`'s reader pair had to be renamed
  `ResourceItem` when the RTL's `ResourceEntry` arrived (the symptom is the prelude's own
  code emitted against `nsN_ResourceEntry`). Keep prelude/RTL names unique project-wide.
- **A *static* call's result has no inferred type** (`Resources.entries()` is emitted
  fine, but a member chained straight onto it, `Resources.entries().size()`, resolves
  against nothing and picks the wrong overload). Bind it to a typed local first - that is
  what `resourcesCount` does.
- **A `@SmGen("kt", ...)` declaration must not live in package `rtl`**, which is where
  the driver puts the generated module (a bare name there is the symbol a call reaches,
  so the generated function can carry the declaration's own name). Same package means two
  declarations of one name. A receiver form (`this`/`fun T.f`) is not supported for `kt`
  yet either.
- **A Kotlin formatter is active for `.kt` files in this editor.** It re-indents and, at
  its own width, breaks a long line - which can split `@SmGen(...) fun f(...)` onto two
  lines and shuffle the lines after it. The parser accepts both forms, so a *program* is
  unaffected; a fixture whose `expected.cpp` pins source line numbers (or a file written
  from outside, like a generated one) must keep its lines short, and a file written in
  one go is worth re-reading before it is used.
- **A generated section's items render at the end of their section**, so a
  resource-backed `bodies` text (a definition) lands after every emitted body. The
  matching `forward` declaration is what makes the call sites valid; a generator that
  needs its text *earlier* must name an earlier section (or `forward`). The emitter
  writes nothing into `support` or `forward` itself - they exist for generated text, and
  `support` is what the *preamble* needs (the string table's decoder), so it renders
  before it.
- `LNK1168` on build = a running `simse.exe` holds the output; kill it first.
- `build.bat` defaults to a debug build (`/MDd`), so the MSVC debug STL asserts are
  live: bad input such as a directory passed where a `.kt` file is expected can
  pop an assert dialog instead of a diagnostic. Use `build.bat --release`
  (`/O2 /Ob3 /DNDEBUG`) for a build with the asserts compiled out.
- Running the compiler with no `--root` and no inputs scans `.` - every `.kt` in
  the repository, including the stress cases, which each have their own `main`.
  Pass `--root cppsrc` (or another clean module root) instead.
- Prelude `.kt` bodies are not emitted; put behavior in the RTL's C++ - a header for the
  type core and the platform, a `cppsrc/rtl/_res.md` section for everything else
  (`@SmGen("res", section[, symbol])`).
- **A generator's declaration must carry the symbol the call reaches**, because a pass
  that reads the declaration without the emitter's tables reads it from the *attributes*:
  the parser fills `NativeSymbol`/`HasNativeSymbol` for both `cpp` and `res`, and
  `linear`'s `listOf<T>` list literal is the pass that depends on it (`ilIsListOf`). A
  `@SmGen("res", "listops")` declaration whose symbol is *not* in the attribute - the
  2-argument form, which relies on the section's `symbol:` - is a real call and not a
  `Pack`, so the emitter then has no type for it (`unsupported type 'T'` in the IL).
  `listOf` names its symbol explicitly for that reason.
- **A prelude generator is emitted only when reached** - by the name a call spells or by
  the symbol a call reaches - so a call the *emitter itself* spells has to record its
  reach (`emitFunctions` does it for the entry point's `simse_list_append`), and the
  generator pass runs *after* every body for the same reason. Symptom when it is missed:
  `identifier not found` in the generated C++ for a symbol nothing declared.
- **Every block of the amalgamation starts with a blank line** (`Sections.appendBlock`),
  so a generated text reads as its own block; a writer that already ends its text with a
  blank line is not doubled.
- **`*p` where `p: *Sections` (or any pointer) *reads through***: `sourceGenEmit(*this.sections,
  ...)` materialized a *copy* of the assembly, so the generators filled the copy while
  `render()` read the real sink and *every* program came out with no generated text at all
  (`simse_dict_keys: identifier not found`, the compiler itself unable to build). A `*T`
  parameter wants the pointer - `this.sections` - never `*this.sections`. The rule of thumb:
  `val p: *T = x` is for a binding that must outlive its expression; passing a pointer that
  is already one is just passing it.
- **A generator lives in `cppsrc/sourcegen/` and calls nothing from the compiler's stages**
  (`impl_specs/generators.md`, "The generator table"): it reads and writes AST nodes,
  resources and the `Sections` sink, and answers a `SourceGenTransform`. That is what keeps
  a generator from breaking when a compiler API changes - `Sections` moved out of `codegen`
  for exactly that reason, so do not reach back into the emitter from a generator.
- **The prelude is read from disk at run time**, so a compiler older than
  `cppsrc/rtl/*.kt` sees a declaration it does not know how to emit (a new
  `native(...)` on a new type is the shape that bites: it falls back to the
  symbol the declaration names). `build.js` warns when `./simse.exe` is older than
  the prelude; rebuilding is the fix.
- Source-map comments embed the path as given, so absolute and relative runs
  differ — cosmetic, but they are *in* the amalgamation: the bootstrap refresh
  changes with them.
- **The emitted C++ embeds line numbers as comments**, so adding lines to a source
  file changes the amalgamation. That is why `cppsrc/simse_bootstrap.cpp` is
  refreshed (and re-checked) as part of a change rather than at release time.
- **A jump is not always a sibling of its label.** The expression lowering wraps a
  jump in the block that carries its temporaries, and `break`/`continue` jump out of
  the body they are written in, so any pass that asks "is this label used?" must look
  *through* blocks (`jumpsTo`/`linJumpsTo`), and any pass that moves a declaration
  across a level must ask whether a jump at *any* depth now bypasses it - that is what
  `flattenBlocks`' rule and the `pos (J) < pos (D) <= pos (L)` condition are about.
  The symptom of getting the first one wrong is MSVC `C2094: use of undefined label`
  in the *generated* C++, which only shows up when that output is compiled - the
  stress corpus and the bootstrap's own compile are what catch it.
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
- After changing **any RTL header**, the objects in `build/` are stale (the harness
  caches them per flag set and rebuilds when a header is newer, but a manual `cl`
  invocation is the caller's business). `build.js` compiles the amalgamation and
  `native.cpp` in one `cl` invocation, so the two can never disagree about layout -
  the old risk was linking a prebuilt library built against an older header.
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
- **Within one package the *file order* is the emitted order**, so the same rule
  applies to a field whose type is declared in a sibling file: the driver scans a
  module root by path, and the definition comes out where the file sorted
  (`cppsrc/codegen/CgStringTable.kt` before `Codegen.kt`, because `Emitter` embeds a
  `StringTable` by value). A by-value field needs the *complete* type, so a forward
  declaration is not enough - name the file so it sorts first, or pass the value as
  a parameter.
- **Spell a char literal whose value is `"` as `'\"'`, not `'"'`** (three sites did
  the latter): Kotlin's highlighter reads `'"'` as the start of a string and colours
  the rest of the line as text. The emitted C++ keeps the *source spelling* of a char
  literal, so `'\"'` reaches the generated file too - the same value, and the
  bootstrap refresh carries the difference.
- **An undefined value name is not diagnosed** (both rings): `text.find(D)` with no
  `D` in scope transpiles clean (exit 0) and emits `simse_str_find(text, D)`, so the
  user sees `'D': undeclared identifier` from C++ instead of a positioned Simse
  error. `sema` reports an unknown *type*, not an unknown *value*; the emitter's
  `ExprName` arm falls through to the raw name. Found while T71/T72 were worked (a
  repro is in the capability matrix), still open.
- **Every size, length and index in the RTL is the language's `Int`** (32-bit
  signed) and `Str::npos` is `-1`; a `std::size_t` appears only where the standard
  library's own signature needs one (allocation, `mem*`, `char_traits::length`), as
  an explicit widening cast. That is what keeps the compiler's own build free of
  C4267 (`size_t` to `Int`) warnings - do not reintroduce an unsigned size type to
  satisfy a std API; cast at that one call instead (`impl_specs/rtl-abi.md`).
- **A string literal at a site is a `StrView`**, not a `Str` (`__sm_stringTable[k]`, a view
  into the program's literal pool): comparisons and `+` have direct `StrView` overloads so
  they build nothing, and every other position goes through the converting
  `SmString(const StrView&)` and materializes the copy it always did. The consequence for
  RTL work: **a new operator that takes two `Str`s needs its `StrView` overload too, or a
  literal argument makes the call ambiguous**, because `const char* -> Str` and
  `StrView -> Str` both exist and each candidate then converts a different operand. The
  operators and the conversion live at the end of `cppsrc/rtl/strview.hpp` (the constructor
  is *declared* in `smstring.hpp`, which cannot see `StrView`).
- **A Simse identifier that is a C++ keyword is emitted verbatim** and breaks the
  generated file, not the Simse program: `val long: Str = "..."` transpiles clean and
  produces `Str long = ...;` (`C2628`). Nothing maps `long`/`class`/`template`/... out of
  the way, and - like an undefined value name - the failure is at C++ compile time, with no
  positioned Simse error. `stress/string-escapes` hit it while it was being written.
- `stress/<case>/expected.cpp` is compared byte for byte but **`--update` never
  rewrites it**: copy `stress/.work/<case>/out.cpp` over it by hand.
- **A value receiver is `T* self`** (T47): method signatures, call sites
  (`ns_f(simse_addressOf(x))`) and bodies (`self->field`, a bare `this` reading as
  `(*self)`) all follow it, while `this: &T` stays `std::shared_ptr<T> self` and
  `this: *T` stays `T* self`. A call on the bare `this` is the one receiver that
  needs no address taken - the emitted receiver already is one - so it is
  `ns_f(self)` (C++'s `this` in a closure class), and `*this` is `self` too. The
  C++ drivers of the deleted differential harness called emitted receiver functions
  directly and had to pass `&scanner`; a *native* extension is the one call the
  emitter passes the receiver expression to unchanged, because the host's C++
  signature decides.
- **A borrow of a temporary lasts only for its call.** `*f()` lowers to
  `simse_addressOf(f())`, whose contract is exactly that
  (`cppsrc/rtl/types.hpp`) - so it must stay inline in the expression it is passed
  to. Hoisting it into a variable (which the expression lowering would otherwise do,
  since a value position is one operation deep) leaves a pointer to a dead
  temporary; `exprIsBindable` is the guard that keeps it where it is.
- **`for` and `yield` are pure lowerings.** `for` is desugared in the
  *parser* (`parseFor` in `Parser.kt`, reached through `parseStmtInto`, the one
  statement slot that expands to several), so no stage downstream has a `for`
  statement kind -
  which is also why `sema`'s "a `for` needs a `smToYield`" check keys on the template's
  `_sm_for<n>` name and reads the wrap call underneath it: that prefix is the only marker
  left of the construct. Two
  consequences bite: the template's machine is a *local*, so a machine can never be a
  field, and a `for` inside a body that yields therefore has no field to live in
  (reported, not silently miscompiled); and a machine's C++ class is the creating
  function's, so `..T` stays unspellable - `sema::TypeInfer` carries the machine's two
  methods (`next` -> `Opt<T>`, `advance` -> `Bool`) precisely so a loop variable is a
  typed binding rather than an `auto` the emitter would resolve the wrong native for.
