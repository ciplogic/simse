# Simse

Simse is a small, statically typed language that compiles to **one readable C++
file**.

```simse
package tour

fun Str.words(): List<Str> {
    return this.split(" ")
}

fun main(): Int {
    val text: Str = "one two two three three three"
    val counts: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    val words: List<Str> = text.words()

    var i: Int = 0
    while (i < words.size()) {
        val seen: Opt<Int> = counts.get(words[i])
        if (seen.hasValue()) {
            counts.insert(words[i], seen.value() + 1)
        } else {
            counts.insert(words[i], 1)
        }
        i = i + 1
    }

    val keys: List<Str> = counts.keys()
    keys.sort((left: Str, right: Str) -> left < right)

    var k: Int = 0
    while (k < keys.size()) {
        val count: Int = counts.get(keys[k]).value()
        println(keys[k] + " = " + count.toString())
        k = k + 1
    }
    return 0
}
```

```console
$ ./tour.exe
one = 1
three = 3
two = 2
```

The whole program becomes a single `.cpp` you can read, step through in a
debugger, and hand to any C++20 compiler:

```cpp
int main() {
    Str text = "one two two three three three";
    Dictionary<Str, Int> counts = simse_dictionaryOf<Str, Int>();
    List<Str> words = ns1_words(text);
    Int i = 0;
    L1:;
    if (!(i < words.size())) goto L2;
    {
        Opt<Int> seen = simse_dict_get(counts, words[i]);
        if (seen.hasValue()) goto L3;
        goto L4;
        L3:;
        simse_dict_insert(counts, words[i], seen.value() + 1);
        goto L5;
        L4:;
        simse_dict_insert(counts, words[i], 1);
        L5:;
        i = i + 1;
    }
    goto L1;
    L2:;
    // ...
}
```

There is no interpreter, no VM and no runtime library to ship: a Simse program
is C++ with a small prelude of helper types (`Str`, `List`, `Dictionary`,
`Opt`, `Res`, `Array`, `Span`) defined in headers.

## What it is for

Simse is aimed at programs that are *tools*: transpilers and code generators,
CLI utilities, hot loops, and small single-threaded JSON services - the kind of
program you would otherwise write in Node or Python and then wish were faster,
or in C++ and then wish were simpler.

The design follows from one constraint: **no runtime**.

- **No garbage collector.** Values have value semantics; `&T` is an explicit
  reference-counted handle (roughly `shared_ptr`), `*T` is a raw pointer.
- **No exceptions.** Expected failures are values (`Opt<T>`, `Res<T>`); bugs
  are `panic` territory. Nothing unwinds, so the happy path is not taxed.
- **No threads.** A program is single-threaded; services are one process per
  core (planned), not a thread pool.
- **No vtables.** Dispatch is static: generic functions are reified per
  instantiation, and protocols (planned) are satisfied structurally and
  resolved at compile time - there is nothing to look up at run time.

The result is a language that reads like Kotlin/.NET and builds like C.

## How it works

1. The compiler **scans** and **parses** a module root (a directory of `.simse`
   files, each with a `package` declaration) into an XML-shaped AST.
2. **Sema** resolves names and types; **generics are reified** - each concrete
   instantiation becomes a distinct C++ type, so `List<Int>` and `List<Str>` are
   `List<Int>` and `List<Str>` in the output.
3. Control flow is **lowered to labels and gotos** (`if`, `while` and `switch`
   never reach the emitter), then optionally simplified.
4. **Codegen** emits C++ for the whole program as one translation unit, with
   package-qualified names (`ns1_words`), source-map comments, and RTL calls
   (`simse_str_split`, `simse_dict_get`, ...) for library operations.
5. The result is compiled by a normal C++ compiler.

The compiler is **self-hosted**: it is written in Simse (`cppsrc/**/*.simse`),
and a hand-written C++ implementation of the same compiler (`cppsrc/**/*.cpp`)
exists alongside it as the bootstrap. The bootstrap compiles the Simse sources;
the resulting binary compiles the same sources again, and the two outputs must be
**byte-identical** (the build fails otherwise). That fixed point, five
differentially-tested stages, and a stress corpus are what keep the two
implementations honest.

## Quick start

Requirements: Windows with Visual Studio (C++ workload), CMake + Ninja, and
[bun](https://bun.sh).

```bat
:: 1. build the bootstrap compiler (once)
cmake -S . -B cmake-build-debug -G Ninja -DCMAKE_BUILD_TYPE=Debug
cmake --build cmake-build-debug

:: 2. build the self-hosted compiler from the Simse sources -> .\simse.exe
build.bat

:: 3. compile and run an example with it
simse.exe --root docs/examples/hello -o hello.cpp
build.bat --cpp hello.cpp --exe hello.exe
hello.exe
```

`build.bat` is a thin wrapper over `build.js` (see `build.bat --help`).
`build.bat --release` (the configuration the numbers in this repository were
measured with) needs a second CMake folder, `cmake-build-release`, configured the
same way with `-DCMAKE_BUILD_TYPE=Release`.
A full walkthrough, including the tests and the stress corpus, is in
[docs/getting-started.md](docs/getting-started.md).

> The build folders checked into this repository (`cmake-build-*/`) are the
> author's, with absolute paths from his machine. Reconfigure your own (step 1)
> or edit the `_msvc_configure.bat` / `_msvc_build.bat` in one of them.

## Documentation

| Document | What is in it |
| --- | --- |
| [docs/getting-started.md](docs/getting-started.md) | prerequisites, building the compiler, compiling your first program, the tests and the stress corpus, troubleshooting |
| [docs/language-tour.md](docs/language-tour.md) | the language itself, with runnable fragments: values, control flow, data classes, enums, generics, collections, memory, modules |
| [docs/how-it-works.md](docs/how-it-works.md) | the pipeline, the two compiler implementations, the emitted C++, the RTL, and how the build verifies itself |
| [docs/state-of-the-field.md](docs/state-of-the-field.md) | honest status: what works, what is rough, what is missing, and how it compares to the alternatives |
| [docs/examples/](docs/examples/) | the three example programs used in the docs (`hello`, `tour`, `wordcount`) |
| [impl_specs/user-language-roadmap.md](impl_specs/user-language-roadmap.md) | where the language is going, phased, with the non-goals |
| [guide4ai.md](guide4ai.md) | orientation for an AI/contributor session: build, invariants, change protocol |

## Status

Working today: the language above (data classes, enums, generics, extensions,
lambdas, `List`/`Array`/`Dictionary`/`Span`/`Opt`/`Res`/`Str`, file I/O, the
`main(args)` form), a self-hosted compiler that reproduces its own output byte
for byte, 49 in-process tests, five differential stage tests and 23 end-to-end
stress programs, and a transpile throughput of roughly **6,400 lines in ~65 ms**
(the compiler compiling its own source tree, release, ~16 MB peak working set).

Not there yet, in rough order of how soon a user would miss it: `for` loops,
string interpolation, closed unions + `when`, a `Printable` protocol (so
`println` works for your own types instead of only the built-ins), `Set`,
byte buffers, JSON encode/decode generated from data classes, sockets and
HTTP, and a Linux/macOS toolchain. `docs/state-of-the-field.md` is explicit
about each of these and the roadmap phases them.

## Repository layout

| Path | Contents |
| --- | --- |
| `cppsrc/` | the compiler twice: hand-written C++ (`*.cpp`, `*.h`) and the Simse mirror (`*.simse`), plus `cppsrc/rtl/` (the runtime headers and the prelude) and `cppsrc/native/` (native symbols) |
| `specs/` | the language specification (normative): types, declarations, functions, memory model, generics, containers, modules, statics |
| `impl_specs/` | implementation plans and records: self-hosting plan, capability matrix, RTL ABI, the user-facing roadmap |
| `stress/` | one folder per end-to-end program: source, arguments, expected output |
| `tests/` | fixtures, goldens, and the differential drivers |
| `tools/` | the JavaScript harness: the stress runner, the A/B benchmarks, the probes |
| `docs/` | this documentation |

## Design principles

- **The output is the artifact.** One `.cpp` file, readable, debuggable, no
  generated metadata to interpret; the source-map comments point back at the
  `.simse` lines.
- **Static everything.** Types, dispatch, and generics are resolved at compile
  time; there is no reflection and no runtime type information.
- **Deterministic.** The same inputs produce byte-identical output; nothing
  depends on a randomized hash or on uninitialized state, and where collection
  iteration order could matter, callers sort or the order is part of the backing's
  documented behavior.
- **Small surface, no magic.** No macros, no operator overloading, no
  exceptions, no implicit threading.
- **Two implementations, one behavior.** Every compiler change lands in both the
  C++ and the Simse source, and the build proves they agree.

## License

No license has been chosen yet; a `LICENSE` file will be added before the first
public release.
