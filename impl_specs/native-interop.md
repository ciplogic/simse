# Native interop (hand-written C++)

Status: decision recorded for T10; the spelling settled in T83.

## Declaration form

```text
@SmGen("cpp") fun readFile(path: Str): Str
@SmGen("cpp", "simse_native_readFile") fun readFile(path: Str): Str
```

- The attribute names the generator that owns the implementation. `cpp` means the
  C++ is hand-written and linked in (a header's), so the declaration has a Simse
  type/signature and **no body**.
- The second argument supplies the C++ symbol when it differs from
  the Simse name. Without it, the C++ symbol defaults to the Simse name.
- In v1 the symbol must be a **plain global identifier**. A namespaced symbol
  such as `FileUtils::readFile` is rejected with a positioned `unsupported`
  diagnostic; expose such an implementation through a thin global wrapper whose
  name is the global identifier.
- `native fun` / `native("Symbol") fun` is **gone from the language (T83)**: it was
  sugar for `@SmGen("cpp", ...)`, and there were then two spellings of one thing
  (`impl_specs/generators.md`). A program that wants a runtime symbol writes the
  attribute (`stress/native-read-file` names `simse_native_readFile`, which no
  prelude declaration reaches - which is why `fileio` is `emit: always`).

## Emission and call

- The declaration contributes a normal signature to resolution; calls type-check
  like any other function (including arity).
- Generated code does **not** emit a body for a native function.
- Generated code emits one C++ declaration of the symbol, once, at the top of the
  amalgamated translation unit, using the mapped Simse parameter/return types.
  Native parameters are borrowed as `const T&`; the hand-written definition must
  use the same signature. Calls lower to a direct call of the symbol:

  ```text
  // native_readfile.kt:1
  Str simse_native_readFile(const Str& path);

  ...
  Str text = simse_native_readFile("tests/fixtures/native_data.txt");
  ```

- Boundary types are the RTL/Simse-visible types (`Str`, `List<T>`, `Res<T>`,
  scalars, and user declarations the native translation unit includes). A native
  implementation that needs a generated user type must also include/duplicate
  that declaration; v1 keeps the boundary to built-ins and RTL types.

## Failure reporting

Native functions report failure through `Res<T>` (an error `Str`), not C++
exceptions. A function that returns `Res<T>` maps its error message into the
`Res` value; the runtime's `isOk()` reads the union's tag, so `Res<T>.err("")` is a
failure like any other (`cppsrc/rtl/variant2.hpp`, `impl_specs/rtl-abi.md`).

## Prelude

A prelude file is parsed into the same module scope as the program's inputs, so
its declarations resolve without an `import`. The default is
`cppsrc/rtl/rtl.kt` (relative to the repository root, baked into the binaries);
`simse_transpile --prelude <file>` overrides it, and a missing default is skipped
silently. Prelude declarations are resolved but **never emitted as Simse code**: a
`native` declaration's body is hand-written C++ pulled in transitively by
`cppsrc/rtl/simse.hpp`, and a `@SmGen("res", section, symbol)` declaration's body
is placed in the program's translation unit from its `cppsrc/rtl/_res.md` section
when the program reaches the declaration or its symbol (a section marked
`emit: always` is placed in every program). This is how the RTL
surface (for example `List<T>.append`) becomes available to programs.

A native extension is a body-less function whose first parameter is `this`; a
member call `recv.name(args)` lowers to `<symbol>(recv, args)` with the receiver
first.

## v1 implementation

`simse_native_readFile` backs `readFile`, declared in `cppsrc/common/common.kt` as
`@SmGen("res", "fileio", "simse_native_readFile")`:

```cpp
Str simse_native_readFile(const Str& path); // reads the whole file as bytes
```

Its prototype and definition are the `fileio` section of `cppsrc/modules/io/_res.md` (the
`io` module's own resource), in the
section's `forward:` and `bodies:` texts. The emitter places the section in the program's own
translation unit, so there is nothing to link.

## Filesystem / IO natives (T23)

The self-hosted driver needs a small filesystem surface. Declared in the prelude
(`cppsrc/modules/io/api.kt`) as `@SmGen("res", "fileio", <symbol>)`, prototyped in the
`forward:` text of the `fileio` section of `cppsrc/modules/io/_res.md` and defined in its
`bodies:` text:

| Simse | C++ symbol | Semantics |
| --- | --- | --- |
| `listFiles(dir, ext)` | `simse_listFiles` | recursive, `ext`-filtered, sorted; empty if not a directory (`common::filesInDir`) |
| `listFilesDirect(dir, ext)` | `simse_listFilesDirect` | non-recursive, sorted; used for `import a.b.c` |
| `writeFile(path, content)` | `simse_writeFile` | binary write; `false` on failure |
| `pathCanonical(path)` | `simse_pathCanonical` | `std::filesystem::weakly_canonical` |
| `pathIsDirectory(path)` | `simse_pathIsDirectory` | |
| `pathExists(path)` | `simse_pathExists` | |
| `eprintln(text)` | `simse_eprintln` | one line to stderr |

These are prelude declarations (available without an import) whose prototypes and
definitions the emitter places in the program's own translation unit. The section
is `emit: always` because a program may also name one of these symbols with a
native declaration of its own, and then no `@SmGen` declaration reaches the
section for it; there is nothing to link.

## The argv entry point

A top-level `fun main(args: List<Str>): Int` lowers to C++
`int main(int argc, char** argv)`, with `args` built from `argv[1..]` (the program
name is excluded). The zero-argument `fun main(): Int` form is unchanged. Both
forms are lowered identically.
