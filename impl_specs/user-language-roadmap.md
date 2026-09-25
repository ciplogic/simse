# Roadmap: Simse for the people who *use* it

Status: planning baseline. Companion to `impl_specs/roadmap.md` (compiler internals).
Tracks the language and library work a *user* is blocked on, in the order the blockers stack.
What exists is what `cppsrc/rtl/*.kt` says, not what is merely specified.

## 1. The niche

Simse is **a small, fully static language with no runtime** - no GC, no exceptions, no threads,
no vtables, no arenas - so it transpiles to **one C++ translation unit** any toolchain compiles
and any debugger opens (a lowering: hoisted locals, numbered temporaries, labels/gotos;
`README.md`, "What it compiles to"). That constraint drives the surface: static interfaces
instead of dynamic dispatch (a protocol is satisfied by functions/extensions in scope, checked
at reification, so the emitted call is direct and no vtable exists); value semantics with
explicit `&T` reference-counted sharing; single-threaded flows with (de)serialization *generated*
from the types rather than reflection; and errors as values (`Res<T>`/`Opt<T>` for what can
fail, `panic` for bugs).

Programs that would prove the niche, in order: (1) a transpiler / code generator (this compiler
is the flagship); (2) a small single-threaded JSON HTTP service compiled to a native binary;
(3) a CLI/data-crunching tool that replaces a scripting version and is measurably faster.

Explicitly *not* the niche: dynamic or OO-heavy code, concurrent servers, reflection-driven
frameworks, and (for now) third-party binary packages.

## 2. What a user hits in the first hour

| Area | Today | Gap a user feels immediately |
| --- | --- | --- |
| Loops | `while`, and `for` over a `yield`ing machine | range/`foreach` over `List`/`Str`/ranges |
| Branching | `if`/`else`, `when` | closed unions, exhaustive `when` (no dynamic dispatch) |
| Strings | method library, `+`, `appendStr` | interpolation, `format`, a `toString` protocol for user types |
| Printing | `println`/`print` are *emitter intrinsics* over `std::cout` | a real `Printable` protocol, float formatting with a defined shape |
| Enums | `toInt`/`fromInt` | the member *name*; `println(Color.Red)` prints a number |
| Functions | functions, methods, extensions, lambdas | default parameter values, capture by reference |
| Collections | `List`, `Array`, `Dictionary`, `Span` | `Set`, `map`/`filter`/`reduce`/`join`, `reserve` |
| Errors | `Opt`, `Res`, `isOk`/`value`/`error` | propagation (`?`-like), combinators, `panic`/`assert` |
| Numbers | `Int`..`Int64`, `Float32/64`, `min`/`max` | bit ops, `sqrt`/`pow`/`floor`/`abs`, defined overflow and division-by-zero, shortest-round-trip float printing |
| Runtime services | files, paths, `eprintln`, `main(List<Str>)` | clock, randomness, env vars, stdin, exit codes, logging |
| Bytes | `Str` (bytes, mutable), `Array<T>` | a byte `Buffer` with endian-aware reads/writes, hex/base64, checksums |
| Tooling | `simse_transpile <files>`, `build.js`, MSVC on Windows | `simse init/build/run/test` for a *user* project, Linux/macOS, formatter, LSP with caret diagnostics |

## 3. Keystone: static interfaces (protocols)

**Model.** A protocol is a named set of signatures. A concrete type satisfies it either by
declaring `implements` or - Go-style - by having the required functions/extensions in scope;
a type parameter's requirement (`Dictionary<K: Hashable, V>`, `fun sort<T: Comparable>(...)`)
is checked when the generic is reified, and reification emits a distinct C++ type per
instantiation, so the emitted call is the concrete function.

| Protocol | Signatures | Consumers |
| --- | --- | --- |
| `Hashable` | `equals(a, b): Bool`, `hash(value): Int` | `Dictionary<K, V>`, `Set<T>` |
| `Comparable` | `compare(a, b): Int` (or `less`) | `sort`, `min`/`max`, sorted containers, binary search |
| `Printable` | `toString(value): Str` | `println`/`print`/`format`, logging, error messages |
| `Serializable` | structural, derived | JSON encode/decode (`json.encode`, `json.decode<T>`) |

**`data class` satisfies the first three implicitly** (field order: equality and hash over
all fields, `toString` as `Point(x=1, y=2)`), and each can be replaced by writing the
extension. `Comparable` for a data class stays opt-in (field-wise order is a decision, not a
default). That makes "use my own type as a dictionary key" work with no runtime support.

**Concrete shape:**

- `data class Point` emits `operator==` and a `std::hash<Point>` specialization, so
  the default `std::unordered_map` backing works, *and* the language-level
  `equals`/`hash`/`toString` functions the protocol resolves to; the opt-in
  `SmDictionary` hash hook (`simse_dict_hashKey`) finds them the same way.
- Sema gains the constraint check with a diagnostic at the *instantiation* site:
  `Dictionary<Point, Int>` requires `Hashable`; "`Point` does not satisfy `Hashable`: no
  `hash(Point): Int` in scope; add one, or make it a `data class`".
- `println`/`format` stop being emitter intrinsics and become
  `println<T: Printable>(value: T)` in the prelude.

**Non-goal:** interfaces as *values* (existentials, `List<Printable>`, vtables). Substitutes:
closed unions with `when`, or a hand-written tagged struct.

## 4. The other holes, grouped by what they unblock

### 4.1 Data modelling: closed unions and `when`

Needed: a closed union (`sealed`-like) plus `when` with exhaustive matching, so payloads can
differ per case (`when` on an `Int` is not enough). Unblocks: `JsonValue`, protocol messages,
ASTs, result types richer than `Res`, and the interface-as-value workaround above. Makes the
no-vtable decision comfortable rather than limiting.

### 4.2 Errors, invariants, cleanup

- `Res<T>`/`Opt<T>` exist but are verbose: add propagation (`expr?`, `or(default)`)
  and a few combinators (`map`, `bind`, `unwrapOr`).
- `panic(msg)` and `assert(cond, msg)` for bugs, with a defined abort path
  (nothing unwinds; no destructors run - document that).
- Resource cleanup without exceptions: a `defer`/`use` form, or RAII types with
  `close`. An early `return` after `readFile`/`open` leaks by construction.
- Rule to record in `specs/`: *expected* failures are `Res`; programmer errors `panic`; there
  is no third category.

### 4.3 Text and formatting

String interpolation (`"hi ${name} (${n})"`), a `format` function, `Printable` for user
types, float printing with a defined shortest-round-trip shape (via `std::cout` defaults),
`Str` indexing/slicing by characters, and a UTF-8 story (byte-oriented; case mapping is ASCII
- must be stated).

### 4.4 Bytes and buffers

A `Buffer` type: bytes with bounds-checked reads/writes for `Int8`..`Int64` and
`Float`, explicit endianness, `Str`<->bytes conversion, hex/base64, CRC32/Adler.
`readFile`/`writeFile` in bytes, not only text. Unblocks: HTTP bodies, chunked
transfer, any binary format, and "read the file that isn't UTF-8".

### 4.5 Collections for real programs

`for` over a container (range/`foreach`; the `for` that exists iterates a `yield`ing machine,
`impl_specs/for.md` - biggest daily win); `Set<T: Hashable>`; `sorted` containers or at least
`sort` + binary search; functional helpers (`map`/`filter`/`reduce`/`join`/`slice`)
implemented over the existing extension mechanism; `List.reserve` (performance code cannot
preallocate); `Span`-style slices as the safe way to pass a window of a list.

### 4.6 Numerics, time, randomness

Bit operations, the usual math functions, `Int`/`Float` parse and print rules
(incl. `Int64` and negative modulo), defined integer overflow (wrap) and
division-by-zero (panic), monotonic clock (for logging and benchmarks), `random`
with a seedable generator. Small surface, but a "make my code fast" language
without `sqrt` or a clock is not credible.

### 4.7 JSON as code generation (the Node-shaped enabler)

`json.encode(value)` and `json.decode<T>(text): Res<T>` derived *structurally* from
`data class` types at reification time - no runtime reflection, no map-of-anything in the
middle. Field attributes for rename/skip/default/nullable, `JsonValue` for payloads that are
genuinely dynamic, and decode errors that name the path (`items[3].price: expected number`).
Makes the request->handler->response flow feel like Node while staying statically typed.

### 4.8 Processes, environment, logging

`env(name)`, `stdin` reading, `exit(code)`, stdout/stderr as first-class streams,
log levels with timestamps and per-request ids (all built on 4.6's clock).

### 4.9 The server story (single-threaded by design)

`TcpListener`/`TcpStream` with blocking and non-blocking modes, `poll`/`select` on
a set of sockets, then a minimal HTTP/1.1 layer on top: request line + headers +
`Content-Length` bodies, keep-alive, response writer with chunked encoding, and a
router built from lambdas (`route("/items", (req) -> json.encode(items))`).
Documented as *one core per process*: concurrency comes from running several
processes (and, later, from a fork/spawn helper). TLS and a thread pool are not in
this roadmap - deploy behind a proxy.

### 4.10 Toolchain and developer experience

- a `simse` command with `init`, `build`, `run`, `test`, `fmt` over a project file
  (so nobody needs CMake or `build.js`);
- **Linux and macOS**: the emitted C++ is portable, but the toolchain and the RTL are not
exercised there; a service story without Linux is odd;
- module manifests with versions and transitive resolution (the last big hole in
  `specs/modules.md`);
- diagnostics with source spans and carets (positions today), a formatter, and an LSP;
- in-language tests (`test` blocks, `assert`, golden helpers) and doc comments;
- release/debug builds, a single static binary story, and cross-compilation.

### 4.11 Performance escape hatches (no arena, no threads)

What the user gets instead of an allocator API: `reserve` and buffer reuse on the
data structures that matter, `Array<T>`/`RawArray<T>` for flat blocks,
`SmallVector` inline storage for small collections, `*T` pointers plus `unsafe`
(specified, syntax still open) for the last 5%, monomorphized generics so nothing
is boxed behind an interface, and the generated C++ as the profiling target. Plus
a documented answer for `&T` cycles (reference counts cannot collect them) and for
"why is my program allocating" (the RTL's `shared_ptr` handles and `Str` copies).

### 4.12 Runtime ownership and interop

`object` declarations (`specs/statics.md`, slices 2-5) let per-type statics such as
`arrayEmpty<T>()` move from hand-written C++ into Simse, so the eventual story is "your
program is Simse plus a small prelude", not "plus a C++ runtime". User-visible FFI: a
manifest plus a `.cpp` next to a module, declared with `native fun`, so "make it fast" can
mean "drop to C for one function" (only the RTL can today).

## 5. Sequencing

| Phase | Contents | Gate |
| --- | --- | --- |
| 1. Ergonomics | `for`, interpolation, default args, reference captures, `when` + closed unions, `Printable` + `data class` defaults, `panic`/`assert`, enum names | a 300-line CLI tool reads files, parses text, prints well-formatted output - no `while` boilerplate, no `println` limits |
| 2. Protocols | constraint checking + `Hashable`/`Comparable`, `Set`, `List.reserve`, functional helpers | a `Dictionary<MyKey, V>` over a user `data class`, sorted sets, a word-count program that beats the scripting version |
| 3. Texts and bytes | `format`, UTF-8 rules, `Buffer`, hex/base64/CRC, clock, randomness, env/stdin/logging | a tool that parses a binary file format and logs a report with timings |
| 4. JSON | `json.encode`/`json.decode<T>` derived from data classes, `JsonValue`, error paths | a CLI that converts between JSON and a text format, round-tripping a nested structure |
| 5. Servers | sockets, `poll`, HTTP/1.1 subset, router, request logging | `simse run server` serves JSON from a `Dictionary`, curl-able, one process per core |
| 6. Toolchain | `simse init/build/run/test`, Linux + macOS, manifests/versions, formatter, LSP, in-language tests | a fresh machine: install, `simse init`, `simse run`, `simse test` - no CMake, no VS |
| 7. Ownership | `object` statics slices, rest of the RTL in Simse, user FFI | a user program whose only non-Simse code is the prelude; the compiler itself rebuilt that way |

Phases 1-2 are language work; 3-5 are prelude/RTL surface plus a native layer; 6-7 make it
usable by strangers.

## 6. Non-goals, and what replaces each

| Not doing | Because | Instead |
| --- | --- | --- |
| Exceptions | slow even when unthrown in this model (unwinding, tables, no-zero-cost guarantee in the emitted C++) | `Res<T>` for expected failures, `panic` for bugs |
| Threads, async/await, coroutines | a data-race-free story and a runtime are both out of scope; scheduling costs code size | one core per process, `poll`-based event loop, process-level parallelism later |
| Arenas / custom allocators | they drag in ownership rules the type system does not have | `reserve`, buffer reuse, `Array` blocks, inline `SmallVector` storage |
| Dynamic dispatch, interfaces as values | vtables are what we are avoiding; they also mean dynamic type information in a language that resolves everything statically | static protocols, closed unions + `when` |
| Garbage collection, cycle collection | value semantics + reference counts are predictable and cheap | `&T` handles with a documented "no cycles" rule; `Array` for trees |
| Runtime reflection / metadata | it needs a runtime | reified generics + `object` statics do the same work at compile time (JSON is the proof) |
| A TLS stack in-tree | large, security-sensitive, and orthogonal to the language | terminate TLS in a proxy in front of the process |

## 7. Open questions (need a decision before the related phase)

1. **Protocol syntax**: structural satisfaction only (Go-style, any extension in scope
   counts), an explicit `implements X for T` declaration, or both? Preference: structural
   *and* honour an explicit `implements` for documentation/diagnostics, with the structural
   check as the rule.
2. **`when` before JSON?** `json.encode` works for data classes without it; `json.decode`
   ergonomics and dynamic payloads really want it. Land encode first, or wait and land both?
3. **Server shape**: blocking accept-per-connection (simplest, one request at a time) first
   as a smoke test, or go straight to the non-blocking `poll` loop the niche needs?
4. **Linux/macOS before user-facing features?** Vote: yes for services (a JSON server that
   only builds with MSVC is a demo, not a tool); it can run in parallel with phases 1-2.
5. **Cleanup**: `defer`/`use` in the language, or rely on RAII types with `close`? Without
   one, early returns leak in user code.
6. **Float printing and integer overflow**: shortest-round-trip printing and wrapping
   overflow are the predictable choices; confirm before they are written into `specs/`.
