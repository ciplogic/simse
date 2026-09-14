# State of the field

An honest snapshot: what Simse does today, what it does badly, what it does not
do at all, and where it sits among the alternatives. Numbers come from
[`impl_specs/capability-matrix.md`](../impl_specs/capability-matrix.md) and the
harnesses in `tools/`; the plan for the rest is
[`impl_specs/user-language-roadmap.md`](../impl_specs/user-language-roadmap.md).

## What is real today

**The language.** Scalars (`Int8`..`Int64`, `Float32/64`, `Char`, `Bool`), `Str`
with a string library, `List<T>`, `Array<T>`, `SmallVector<N, T>`,
`Dictionary<K, V>`, `Span<T>`, `Opt<T>`, `Res<T>`, `XmlNode`/`Attribute`,
`data class` (with methods), `enum` (with explicit values and `toInt`/`fromInt`),
`typealias`, functions, methods, extension methods, lambdas (by-value capture),
`val`/`var` locals, file-level `var`/`val` statics, `if`/`else`, `while`,
`switch`/`case`/`default`, `break`/`continue`, `return`, `null` for handles,
memory operators (`&T` handles, `*T` pointers, `copy`), reified generics,
packages and imports, `main()` and `main(args)`, and a `native fun` escape hatch
for C++ symbols.

**The compiler.** Self-hosted to a fixed point: the transpiled compiler
reproduces its own output byte for byte. Two implementations (hand-written C++
and Simse) with five differential stage tests, 49 in-process tests and 23
end-to-end stress programs, all run by the build.

**The performance story.** Transpiling the compiler's own 6,357-line source tree
takes ~62-68 ms (release, ~95k lines/s) with ~16 MB peak working set; the
hand-written C++ ring does the same work in ~36-43 ms, so the self-hosted ring is
**~1.6-1.8x slower** - the price of the uniform AST and value-semantics
containers, not of the language's design. The runtime backings are measurably
comparable: the RTL's own dictionary (`SIMSE_DICT_SM`) is ~6% faster end to end
than `std::unordered_map` on this workload, with iteration ~8x and deep copies
~5x faster.

- **A straight-line program, measured.** `benchmarks/onebrc` has the naive 1 Billion
  Row Challenge - read 10M `station;temperature` lines (127.7 MiB), aggregate per
  station, print the report - written twice for the comparison: in Simse, parsing
  each line in place through `readLineView()`/`StrView`, and in C++ with the STL
  (`getline` + `stod`). A JavaScript aggregate validates both reports, which come
  out byte-identical:

| Implementation | Time | Throughput |
| --- | --- | --- |
| **Simse, parsing each line in place (`readLineView()`)** | **1093 / 1106 ms** | **123 / 121 MB/s** |
| C++ STL baseline (`getline` + `stod`) | 1390 / 1405 ms | 96 MB/s |
| Bun reference aggregate | 712 ms | 188 MB/s |

So the naive Simse program is **1.27x faster** than the naive C++ one on the same
data (the ratio has run 1.26-1.40x across sessions - the C++ baseline varies more
than the Simse program), and 1.54x behind the JS reference. Reading the line into a fresh `Str` per
line (`readLine()`) or into a recycled one (`readLineInto`) costs 116-66 MB/s on
the same program, which is why the in-place reader is the one the benchmark keeps.
The remaining gap to close is in the *library*, not the language: the dictionary
has no in-place access to a stored value, so the Simse program does two lookups per
line where the C++ one does one, and the station name and the temperature still
become `Str` values. `benchmarks/onebrc/benchmark.md` has the method, the numbers
per reader and how to reproduce them.

**The deployment story.** One amalgamated `.cpp` per program, no runtime to ship,
no garbage collector, no reflection metadata. Programs are native binaries built
by a normal C++ toolchain.

## What is rough

Found by writing the docs and the examples for them - each of these is a real
program a user would try to write:

| Rough edge | What happens | Workaround |
| --- | --- | --- |
| Method on a temporary | `"a b".words()` fails to compile: the emitted receiver is a non-const reference | bind it to a `val` first |
| Chained method on a generic call | `dict.get(k).value().toString()` does not resolve: the type is lost through the chain | assign the middle step to a typed `val` |
| Lambda body placement | `(x: Int) ->` followed by a newline is a syntax error | keep the body on the arrow's line, or open a block there |
| Enum printing | `println(Color.Red)` prints an integer; there is no automatic member name | write a `switch`-based `label()` function |
| Float printing | `println` goes through C++'s default formatting | format manually; a defined shortest-round-trip rule is on the roadmap |
| Error messages | position and message, no source excerpt or caret | read the generated C++ next to it |
| Vocabulary | no `for`, no `when`, no interpolation, no default parameter values, no capture-by-reference, no `Set`, no `map`/`filter` | `while` + `Span`, explicit code, `List` helpers |
| Ownership and borrowing | `&x` on a local boxes a *copy*, so a handle does not alias the local; `&T` cycles are not collected | borrow with `*x` (a raw pointer) when you mean "the original"; break cycles by nulling a handle |

## What is missing

In rough order of how soon a user of the language notices (the roadmap phases
these):

1. **`for` loops** - mechanically easy, the biggest daily annoyance.
2. **String interpolation and formatting** - building strings with `+` and
   `toString()` everywhere.
3. **Closed unions + `when`** - the replacement for dynamic dispatch; needed for
   JSON, protocol messages and any "one of these shapes" modelling.
4. **Static interfaces (protocols)** - `Hashable`, `Comparable`, `Printable`
   resolved at reification, so dictionaries, sorting, printing and JSON work for
   *your* types with no runtime support. `data class` should satisfy the first
   three implicitly.
5. **JSON** - `json.encode`/`json.decode<T>` generated from the types, plus a
   `JsonValue` for dynamic payloads. This is the flagship "Node-like" feature.
6. **Bytes and buffers** - endian-aware `Buffer`, hex/base64, checksums; HTTP
   bodies and binary formats need them.
7. **Sockets and HTTP** - a single-threaded `poll`-based service layer with a
   router. No threads, no TLS in-tree by design.
8. **Toolchain** - `simse init/build/run/test` for a user project, **Linux and
   macOS**, module manifests with versions, a formatter, an LSP with caret
   diagnostics, in-language tests.
9. **Runtime ownership** - the rest of the RTL moves from hand-written C++ into
   Simse (`object` declarations, `specs/statics.md` slices 2-5), so a user program
   is Simse plus a small prelude; and user-visible FFI, so performance work can
   drop to C for one function.

Also known, and recorded rather than fixed: a single file with tens of thousands
of declarations makes **sema quadratic** (~25 µs/line at 16k lines, ~99 µs/line
at 64k, one file) although the per-file stages are linear; the amalgamated
translation unit for the compiler is ~1 MB, so compiling the *output* is not free;
and the language is byte-oriented, with ASCII-only case mapping.

The toolchain is **Windows/MSVC only** today. The emitted C++ is portable in
principle; nothing has been exercised on another platform.

## How it compares

| If you reach for | Simse gives you | Simse does not give you |
| --- | --- | --- |
| **Node.js / Python** for a small JSON service or a CLI tool | types, a native binary, no runtime to install, no GC pauses, ~50x less memory than a runtime that carries an interpreter | threads, an ecosystem of packages, a REPL, hand-rolled dynamic dispatch, `JSON.parse` of anything (yet) |
| **Go** | similar simple syntax and static dispatch; smaller surface; a transpiled artifact you can read | goroutines, channels, a GC, a `go build`-sized toolchain, method sets, interfaces as values |
| **Rust** | the same "no GC, no runtime" spirit; far fewer concepts (no lifetimes, no traits as types, no macros, no unsafe blocks to write); output is C++ you can inspect | the borrow checker's safety guarantees, the crate ecosystem, `cargo`, exhaustive pattern matching, zero-cost abstractions at Rust's level |
| **C++** | no UB-by-default, no template metaprogramming, no build system archaeology, one readable output file | the full language, libraries, and tooling; manual control over allocation and layout |
| **Kotlin / C#** | a familiar surface (data classes, extensions, `List`/`Dictionary`/`Opt`/`Res`) with a native, allocation-light backend | interfaces with dynamic dispatch, generics without reification, exceptions, an IDE today |
| **Lua / embedded scripting** | a typed alternative for plugins you would rather compile | interpretation, sandboxing, hot reload |

The honest summary of the niche: **a language for tools and small services whose
author wants static types, static dispatch and a native binary, and is willing to
trade away exceptions, threads, dynamic dispatch and a large standard library to
keep the whole system - compiler, runtime and output - small enough to read.**

## What "good" would look like

The roadmap defines a gate per phase; taken together, the language is "there"
when all of these are true on a fresh machine (Windows *and* Linux):

1. `simse init`, write a program, `simse run` - without knowing what CMake is.
2. A CLI tool with `for` loops, interpolation, protocols and JSON round-tripping
   a nested structure.
3. A single-threaded JSON HTTP service: `simse run server`, curl it, and see
   request logs with timings.
4. The compiler's own source tree still transpiling byte-identically with itself,
   faster than today, with the RTL mostly in Simse rather than C++.
5. Every feature claim in this file either true or clearly marked as a non-goal.
