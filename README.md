# Simse

Simse is a small, statically typed language that transpiles to **one C++ translation
unit**. It is aimed at the kind of program you would otherwise write in Node or Python
and then wish were faster, or in C++ and then wish were simpler: transpilers, code
generators, CLI tools, hot loops, small single-threaded services.

Simse sources use the **`.kt` extension** - Kotlin's - because Simse is a
Kotlin-flavored dialect: your editor's Kotlin mode highlights it, and the language is
still Simse.

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
        println(keys[k] + " = " + counts.get(keys[k]).value().toString())
        k = k + 1
    }
    return 0
}
```

## What writing Simse feels like

**Values, and nothing hidden behind them.** Every type is a value type: assignment
copies, a `List` is a list, a `data class` is its fields. There is no object header, no
identity to leak, and no garbage collector deciding when your data goes away.

```simse
data class Point(var x: Int, var y: Int) {
    fun movedBy(dx: Int, dy: Int): Point {
        return Point(this.x + dx, this.y + dy)
    }
}

enum class Color {
    Red,
    Green,
    Blue
}
```

`data class` gets you a constructor, field access and a copy-on-assign value;
`enum class` gets you `toInt()` and `fromInt()` for free. There are no base classes and
no `interface`: code is reused by *composition* and by *extension*, and dispatch is
resolved at compile time (a `Printable`-style protocol is on the roadmap, and it will be
structural, not a vtable).

**Functions are the unit of behavior, and extensions are how you add to a type.**

```simse
fun Str.shout(): Str {
    return this.toUpper()
}

fun <T> firstOr(items: *List<T>, fallback: T): T {
    if (items.size() == 0) {
        return fallback
    }
    return items[0]
}
```

`fun Str.shout()` is an extension: the receiver is the first parameter, written as
`this`, and `"hi".shout()` is the call. Generic functions are **reified** - every
concrete instantiation becomes its own C++ type - so `List<Int>` and `List<Str>` are
different types all the way down, with no boxing and no type erasure.

**Calls pack their trailing arguments.** A function whose last parameter is a `List`
can be called with the elements written out, which is what makes `listOf`, `println`
style helpers and variadic-looking APIs pleasant:

```simse
fun addAll(start: Int, rest: *List<Int>): Int { ... }

val total: Int = addAll(10, 1, 2, 3)   // rest is the list [1, 2, 3]
val justTen: Int = addAll(10)          // rest is empty
```

**Absence and failure are values, not control flow.** There are no exceptions: a lookup
that can miss returns `Opt<T>`, and an operation that can fail returns `Res<T>`. Both are
ordinary values you can pass around, and both say what they are at the call site.

```simse
val found: Opt<Int> = counts.get("two")
if (found.hasValue()) {
    println(found.value().toString())
}

val parsed: Res<Int> = parseConfig(text)
if (!parsed.isOk()) {
    eprintln(parsed.Error)
    return 1
}
println(parsed.Value.toString())
```

**Handles are explicit.** `&T` is a reference-counted handle (single-threaded counting,
one word of count next to the value), `*T` is a raw pointer, and `T` is a value. Boxing
is spelled `&value`, and reading through a handle happens on its own where the type is
known:

```simse
val counter: &Counter = &Counter(1)
counter.bump()          // a call through the handle
val here: Int = counter.value
```

**Iteration is a machine you can see through.** `for` works over containers, and over
anything that provides a state machine; `yield` lets you *write* such a machine without
turning your function inside out.

```simse
for (line in lines) {
    println(line)
}

fun Int.naturals(upTo: Int): Int {
    var i: Int = 0
    while (i <= upTo) {
        yield(i)
        i = i + 1
    }
}
```

**Modules are directories, packages are namespaces.** A module is a directory of `.kt`
files; a package is a name (`package a.b.c`, mandatory, one per file) and `import` only
decides which packages are visible by their simple names - it never adds files. The
compiler scans module roots, so where the files live is a project decision rather than a
resolution rule. A project can carry a `simse.md` naming its modules, and a module its
own `simse.md`; `rtl` is imported implicitly and holds the built-in types.

**The language can carry its own extensions.** This is the part that makes Simse
different from a transpiler with a fixed library: a declaration can say that its
implementation is *somewhere else*, and the compiler will go and get it.

```simse
// The body is hand-written C++ that is linked in.
@SmGen("cpp", "simse_str_trim")
fun trimmed(text: Str): Str

// The body is C++ that lives in a resource section, emitted into the program that uses it.
@SmGen("res", "listops", "simse_list_append")
fun append<T>(this: *List<T>, value: T): Unit

// The body is *Simse source*, from a resource section, compiled with the program.
@SmGen("kt", "greet")
fun greeting(name: Str): Str
```

A resource is a Markdown-shaped `_res.md` file: sections, `key: value` entries, fenced
blocks for the text itself. It is how the runtime's own C++ is written (no header per
feature), how a program can supply generated code for itself, and how a library can ship
a *source generator* that extends the compiler. Sections and single entries can be marked
`!` (read by the compiler, not carried by the program) and `*` (the value is hex that
stands for bytes), which is what keeps a `.md` file from storing code twice.

**Nothing magic.** No macros, no operator overloading, no exceptions, no reflection, no
implicit threading, no runtime library to ship. When you want to see what your program
became, it is one file, and the source-map comments point back at your `.kt` lines.

## What it compiles to

One `.cpp` file, and it is a **lowering, not prose**. Bodies are single scopes with their
locals hoisted to the top, nested expressions become numbered temporaries
(`_sm_expr1`), control flow is labels and `goto`, and library operations are calls to
package-qualified RTL functions. It is mechanical on purpose: it will keep getting less
readable as the language grows, and it is not meant to be extended by hand. What it buys
you is a profiler that names your functions, a debugger that steps your `.kt` lines, and
no interpreter, no VM, no metadata format, no runtime to install.

```cpp
int main() {
    Str text;
    Dictionary<Str, Int> counts;
    List<Str> words;
    Int i;
    Int _sm_expr1;
    Bool _sm_expr2;
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

There is no interpreter, no VM and no runtime library to ship: a Simse program is C++
with a small prelude of types (`Str`, `List`, `Dictionary`, `Opt`, `Res`, `Array`,
`Span`) and operations that are themselves written in Simse or live in resource sections
(`cppsrc/rtl/`). The language's design follows from one constraint - **no runtime**:

- **No garbage collector.** Values have value semantics; `&T` is an explicit
  reference-counted handle, `*T` a raw pointer (`specs/memory-model.md`).
- **No exceptions.** Expected failures are values (`Opt<T>`, `Res<T>`); a bug is a
  crash you get to see.
- **No threads.** A program is single-threaded; more cores means more processes.
- **No vtables.** Dispatch is static: generics are reified per instantiation, and
  protocols (planned) resolve at compile time.

The result is a language that reads like Kotlin/.NET and builds like C.

## The compiler is written in Simse

The compiler *is* its Simse sources (`cppsrc/**/*.kt`): scanner, parser, semantic
pass, control-flow lowering, and the C++ emitter. The only hand-written C++ is the
runtime's headers plus the C++ that lives in resource sections (`cppsrc/rtl/_res.md`:
file I/O, the clocks, the prelude's primitives) - so a program, and the compiler
itself, is one translation unit.

That leaves the bootstrap question - how do you build a compiler written in its own
language, on a machine that has no Simse? The answer is **`cppsrc/simse_bootstrap.cpp`:
the transpiled compiler, checked in**. It is the *output* of the compiler, published; a
fresh checkout compiles it with `cl.exe` alone and gets a working transpiler, which then
transpiles the tree again.

Because the file is checked in, it is also a **proof**: the compiler must reproduce it
byte for byte. `bun tools/bootstrap.js` rebuilds the published file, runs both compilers
over the sources, and compares - so a change that made the output depend on which
compiler emitted it fails the build rather than drifting. The corpus
(`stress/`, one folder per program with its expected output and, where it matters, its
expected `.cpp`) keeps the rest honest.

## Quick start

Requirements: Windows with Visual Studio (C++ workload) and [bun](https://bun.sh).

```bat
:: 1. build the compiler from its sources -> .\simse.exe
::    a fresh checkout first compiles the published bootstrap for this step
build.bat --release

:: 2. compile and run an example with it
simse.exe --root docs/examples/hello -o hello.cpp
build.bat --cpp hello.cpp --exe hello.exe
hello.exe
```

`build.bat` is a thin wrapper over `build.js` (`build.bat --help` lists the options: `--release`,
`--no-lto`, `--pdb`, `--define <name>` for the RTL's configuration switches, `--arch`). Once
`./simse.exe` exists, transpiling the whole tree takes about a second; the rest of a build is
`cl.exe` optimizing the emitted C++. A full walkthrough - prerequisites, the stress corpus, the
bootstrap check, troubleshooting - is in
[docs/getting-started.md](docs/getting-started.md).

## Status

Working today: data classes, enums, generics, extension functions, lambdas, statics,
`List`/`Array`/`Dictionary`/`Span`/`Opt`/`Res`/`Str`, attributes and source generators
(`cppsrc/sourcegen/`, `_res.md` resources, `native` declarations), list literals and
trailing-argument packing, `for`/`yield` state machines, file I/O, the `main(args)` form,
packages and modules, and a project file (`simse.md`). The compiler is self-hosted and
reproduces the published bootstrap byte for byte, and **61 end-to-end stress programs**
run in the corpus.

On speed (`bun tools/bootstrap.js`, release, this machine - the range is machine load,
best of a few runs while idle):

| | |
| --- | --- |
| the compiler transpiling its own source tree | **19,600 lines of Simse in ~0.85 s** (48,800 lines of C++ out, ~24k lines/s) |
| compiling the published `cppsrc/simse_bootstrap.cpp` with `cl.exe` | ~16 s release (`/O2 /Ob3` with LTO) |
| **from the published file to a compiler that reproduces it** | **~17 s**, then under a second per self-transpile |

Not there yet, in rough order of how soon a user would miss it: `for` over a
`Dictionary` and ranges, string interpolation, closed unions with exhaustive `when`, a
`Printable` protocol (so `println` works for your own types), `Set`, byte buffers, JSON
encode/decode generated from data classes, sockets and HTTP, and a Linux/macOS
toolchain. `docs/state-of-the-field.md` is explicit about each of these, and
`impl_specs/user-language-roadmap.md` phases them.

## Repository layout

| Path | Contents |
| --- | --- |
| `cppsrc/` | the compiler in Simse (`lex/`, `parser/`, `sema/`, `linear/`, `codegen/`, `compiler/`, `sourcegen/`), plus `cppsrc/rtl/` (the prelude `.kt` files, the runtime headers, and the resource file `_res.md`, which holds the runtime's C++) and `cppsrc/simse_bootstrap.cpp` - the published transpiled compiler, the output proof |
| `specs/` | the language specification (normative): types, declarations, functions, memory model, generics, containers, modules, statics, resources |
| `impl_specs/` | implementation plans and records: the capability matrix, the RTL ABI, generators, the user-facing roadmap |
| `stress/` | one folder per end-to-end program: source, arguments, expected output, and where the emitted text is the point, an `expected.cpp` golden |
| `build.js`, `build.bat`, `stress.bat` | the build and harness entry points: transpile the source tree, compile it with `cl.exe`, run the corpus |
| `simse.vcxproj`, `simse.slnx` | the Visual Studio profiling project: the published bootstrap (the runtime is generated into it), with the debugger already set to run the compiler over its own tree |
| `tools/` | the JavaScript harness: the stress runner (`stress.js`), the bootstrap fixed-point check (`bootstrap.js`), the Visual Studio project check (`vscheck.mjs`), `msvc.mjs` |
| `docs/` | this documentation |
| `guide4ai.md` | orientation for a fresh contributor or AI session: the build, the invariants, the change protocol, the gotchas |

## Design principles

- **The output is the artifact.** One `.cpp` file, faithfully lowered, with source-map
  comments back to the `.kt` lines; no metadata to interpret and nothing to install. It
  is not meant to be hand-edited or to read like prose.
- **Static everything.** Types, dispatch and generics are resolved at compile time;
  there is no reflection and no runtime type information.
- **Deterministic.** The same inputs produce byte-identical output; nothing depends on a
  randomized hash or on uninitialized state, and where iteration order could matter,
  callers sort or the backing documents its order.
- **Small surface, no magic.** No macros, no operator overloading, no exceptions, no
  implicit threading.
- **One implementation, a fixed point.** The compiler is its Simse sources; the
  published bootstrap is its own output, and the build proves the two agree byte for
  byte.

## Documentation

| Document | What is in it |
| --- | --- |
| [docs/getting-started.md](docs/getting-started.md) | prerequisites, building the compiler, compiling your first program, the corpus, troubleshooting |
| [docs/language-tour.md](docs/language-tour.md) | the language itself, with runnable fragments: values, control flow, data classes, enums, generics, collections, memory, modules |
| [docs/how-it-works.md](docs/how-it-works.md) | the pipeline, the bootstrap fixed point, the emitted C++, the runtime, and how the build verifies itself |
| [docs/state-of-the-field.md](docs/state-of-the-field.md) | honest status: what works, what is rough, what is missing, and how it compares to the alternatives |
| [docs/examples/](docs/examples/) | the example programs used in the docs (`hello`, `tour`, `wordcount`) |
| [specs/](specs/) | the normative language specification |
| [impl_specs/user-language-roadmap.md](impl_specs/user-language-roadmap.md) | where the language is going, phased, with the non-goals |
| [impl_specs/generators.md](impl_specs/generators.md) | `@SmGen`, the source-generator registry, the `Sections` sink, and the bootstrap path for new syntax |
| [guide4ai.md](guide4ai.md) | orientation for a contributor session: build, invariants, change protocol, gotchas |

## License

Apache 2.0 - see [LICENSE](LICENSE).
