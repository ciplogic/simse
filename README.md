# Simse

Simse is a small, statically typed language that compiles to **one readable C++
file**. Its sources use the **`.kt` extension** - Kotlin's - because Simse is a
Kotlin-flavored dialect: your editor's Kotlin mode highlights it, and the language
is still Simse (`specs/`, `guide4ai.md`).

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
debugger, and hand to any C++20 compiler. A body is one scope: every declaration is
at the top (the flat-IL hoisting), and control flow is labels and gotos.

```cpp
int main() {
    Str text;
    Dictionary<Str, Int> counts;
    List<Str> words;
    Int i;
    Int _sm_expr1;
    Bool _sm_expr2;
    Str _sm_expr3;
    Opt<Int> seen;
    text = "one two two three three three";
    counts = simse_dictionaryOf<Str, Int>();
    words = ns1_words(simse_addressOf(text));
    i = 0;
    L1:;
    _sm_expr1 = words.size();
    _sm_expr2 = i < _sm_expr1;
    if (!(_sm_expr2)) goto L2;
    _sm_expr3 = words[i];
    seen = simse_dict_get(counts, _sm_expr3);
    if (!(seen.hasValue())) goto L4;
    simse_dict_insert(counts, words[i], seen.value() + 1);
    goto L5;
    L4:;
    simse_dict_insert(counts, words[i], 1);
    L5:;
    i = i + 1;
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

1. The compiler **scans** and **parses** a module root (a directory of `.kt`
   files, each with a `package` declaration) into an XML-shaped AST.
2. **Sema** resolves names and types; **generics are reified** - each concrete
   instantiation becomes a distinct C++ type, so `List<Int>` and `List<Str>` are
   `List<Int>` and `List<Str>` in the output.
3. Control flow is **lowered to labels and gotos** (`if` and `while` never
   reach the emitter - `for` and `when` are desugared in the parser), then
   optionally simplified.
4. **Codegen** emits C++ for the whole program as one translation unit, with
   package-qualified names (`ns1_words`), source-map comments, and RTL calls
   (`simse_str_split`, `simse_dict_get`, ...) for library operations.
5. The result is compiled by a normal C++ compiler.

The compiler is **self-hosted**: it is written in Simse (`cppsrc/**/*.kt`), and
there is exactly one implementation of it. `cppsrc/simse_bootstrap.cpp` - the
amalgamation of those sources, checked in - is what lets a fresh checkout build
the compiler with a C++ compiler alone. The compiler that comes out of it
transpiles the same sources back into that same file, **byte for byte**;
`bun tools/bootstrap.js` checks that fixed point, and the stress corpus keeps it
honest.

## Quick start

Requirements: Windows with Visual Studio (C++ workload) and
[bun](https://bun.sh).

```bat
:: 1. build the compiler from the Simse sources -> .\simse.exe
::    a fresh checkout first compiles the published bootstrap for this step;
::    add --release for /O2 /Ob3 /DNDEBUG + /GL (--no-lto skips the whole-program step)
build.bat

:: 2. compile and run an example with it
simse.exe --root docs/examples/hello -o hello.cpp
build.bat --cpp hello.cpp --exe hello.exe
hello.exe
```

`build.bat` is a thin wrapper over `build.js` (see `build.bat --help`). On a
fresh checkout there is no `./simse.exe` yet, so `build.js` first compiles the
published `cppsrc/simse_bootstrap.cpp` together with `cppsrc/rtl/native.cpp` and
uses that as the transpiler. Once `./simse.exe` exists it does the transpiling,
and that step takes about a second; the rest of a build is `cl.exe` optimizing
the generated C++. A full walkthrough, including the stress corpus and the
bootstrap fixed-point check, is in
[docs/getting-started.md](docs/getting-started.md).

## Documentation

| Document | What is in it |
| --- | --- |
| [docs/getting-started.md](docs/getting-started.md) | prerequisites, building the compiler, compiling your first program, the stress corpus, troubleshooting |
| [docs/language-tour.md](docs/language-tour.md) | the language itself, with runnable fragments: values, control flow, data classes, enums, generics, collections, memory, modules |
| [docs/how-it-works.md](docs/how-it-works.md) | the pipeline, the bootstrap fixed point, the emitted C++, the RTL, and how the build verifies itself |
| [docs/state-of-the-field.md](docs/state-of-the-field.md) | honest status: what works, what is rough, what is missing, and how it compares to the alternatives |
| [docs/examples/](docs/examples/) | the three example programs used in the docs (`hello`, `tour`, `wordcount`) |
| [impl_specs/user-language-roadmap.md](impl_specs/user-language-roadmap.md) | where the language is going, phased, with the non-goals |
| [impl_specs/generators.md](impl_specs/generators.md) | the `@Identifier` token, `@SmGen` generation, the `Sections` sink, and the bootstrap path for the new syntax |
| [guide4ai.md](guide4ai.md) | orientation for an AI/contributor session: build, invariants, change protocol |

## Status

Working today: the language above (data classes, enums, generics, extensions,
lambdas, `List`/`Array`/`Dictionary`/`Span`/`Opt`/`Res`/`Str`, list literals and calls
that pack their trailing arguments into a last `List` parameter, file I/O, the
`main(args)` form, `yield`, and `for` over anything with a `smToYield` - a container,
or a state machine itself),
a self-hosted compiler that reproduces the published bootstrap byte for byte,
and 45 end-to-end stress programs.

On speed (`bun tools/bootstrap.js`, release, this machine - the range is machine
load, best of 3 runs idle):

| | |
| --- | --- |
| the compiler transpiling its own source tree | **17,905 lines of Simse in ~1.1 s** (45,757 lines of C++ out, ~16.4k lines/s) |
| compiling the published `cppsrc/simse_bootstrap.cpp` with `cl.exe` | ~21 s release (`/O2 /Ob3`) |
| **from the published file to a compiler that reproduces it** | **~22 s**, then ~1.1 s per self-transpile |

Not there yet, in rough order of how soon a user would miss it: `for` over a
`Dictionary` and ranges, string interpolation, closed unions + exhaustive `when`, a
`Printable` protocol (so `println` works for your own types instead of only the
built-ins), `Set`, byte buffers, JSON encode/decode generated from data classes,
sockets and HTTP, and a Linux/macOS toolchain. `docs/state-of-the-field.md` is
explicit about each of these and the roadmap phases them.

## Repository layout

| Path | Contents |
| --- | --- |
| `cppsrc/` | the compiler in Simse (`lex/`, `parser/`, `sema/`, `linear/`, `codegen/`, `compiler/`), plus `cppsrc/rtl/` (the runtime headers, the prelude `.kt` files, and the one hand-written translation unit `native.cpp`) and `cppsrc/simse_bootstrap.cpp` - the amalgamated compiler, checked in so it can be built with a C++ compiler alone |
| `specs/` | the language specification (normative): types, declarations, functions, memory model, generics, containers, modules, statics |
| `impl_specs/` | implementation plans and records: self-hosting plan, capability matrix, RTL ABI, the user-facing roadmap |
| `stress/` | one folder per end-to-end program: source, arguments, expected output |
| `build.js`, `build.bat`, `stress.bat` | the build and harness entry points: transpile the source tree, compile it with `cl.exe`, run the stress corpus |
| `tools/` | the JavaScript harness: the stress runner (`stress.js`), the bootstrap fixed-point check (`bootstrap.js`), the `native`/`@SmGen` byte-equality check (`smgen.js`), `msvc.mjs`, the probes |
| `docs/` | this documentation |

## Design principles

- **The output is the artifact.** One `.cpp` file, readable, debuggable, no
  generated metadata to interpret; the source-map comments point back at the
  `.kt` lines.
- **Static everything.** Types, dispatch, and generics are resolved at compile
  time; there is no reflection and no runtime type information.
- **Deterministic.** The same inputs produce byte-identical output; nothing
  depends on a randomized hash or on uninitialized state, and where collection
  iteration order could matter, callers sort or the order is part of the backing's
  documented behavior.
- **Small surface, no magic.** No macros, no operator overloading, no
  exceptions, no implicit threading.
- **One implementation, a fixed point.** The compiler is its Simse sources; the
  published bootstrap and the compiler it produces must agree byte for byte, and
  the build proves it.

## License

MIT - see [`LICENSE`](LICENSE). Simse is a Kotlin-flavored dialect of its own
making; it is not affiliated with Kotlin or JetBrains.
