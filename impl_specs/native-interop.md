# Native interop (hand-written C++)

Status: decision recorded for T10; the spelling settled in T83; the run-time `native`
generator (`LoadLibraryA`/`GetProcAddress`) is implemented
(`src/compiler/NativeInvokeGen.kt`, `stress/native-invoke`, `examples/sdl2`,
`examples/http`).

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
failure like any other (`src/rtl/optres.kt`, `impl_specs/rtl-abi.md`).

## The `native` generator: P/Invoke

`@SmGen("native", library[, symbol])` binds a declaration to an exported symbol of a native
**shared library**, resolved at run time - the P/Invoke shape, and the C# `[DllImport]`
analogue:

```text
@SmGen("native", "SDL2.dll", "SDL_Init") fun sdlInit(flags: Int): Int
@SmGen("native", "SDL2.dll") fun SDL_Quit(): Unit      // symbol = the declaration's own name
```

The declaration is an ordinary body-less method with a Simse signature, and the *reader writes
the ABI's signature* the way a `[DllImport]` declaration does: `Int` is the native `int`, and a
handle is a `RawPtr`, crossing as the library's own pointer. Nothing is linked and no header is
needed at the call site; what a call reaches is a generated **thunk**, emitted per reached
declaration into `forward` and `bodies`, with one shared loader in `support`:

```cpp
// includes/support, once per program that reaches a `native` declaration
#include <windows.h>
inline FARPROC __sm_nativeResolve(const char* library, const char* symbol) {
    /* LoadLibraryA once, cached; then GetProcAddress */
}

// forward/bodies, per reached declaration; every call reaches this, not the native symbol
Int32 __sm_native_sdlInit(const Int32& flags) {
    using Fn = Int32 (*)(Int32);
    static Fn fn = (Fn) __sm_nativeResolve("SDL2.dll", "SDL_Init");
    if (fn == nullptr) return Int32{0};
    return fn(flags);
}
```

- **Nothing to link.** The library is loaded on the first call (`LoadLibraryA`) and the symbol
  resolved (`GetProcAddress`), so a program stays one translation unit and an import library is
  irrelevant. Only the DLL has to be findable at run time (beside the executable, or on the
  search path). A missing library or symbol answers the declaration's default value (0, an empty
  `Str`, a null pointer) rather than crashing.
- **The cast is the generator's.** The `FARPROC` is cast to the function pointer the
  declaration's signature builds (`(Fn)`), which is where a compatible-but-different ABI type
  (`Uint32` for `Int`, `Uint8` for `Int`) is reconciled - the same latitude P/Invoke takes.
- **Handles are raw pointers.** A `RawPtr` parameter or return is the native pointer (`void*`
  inside the function pointer), cast at the thunk's edge, so an opaque library type
  (`SDL_Window*`) is one. This is also how a union-style API is reached: the bytes of an
  `SDL_Event` are owned by a `res` section that includes the real header and reinterprets them,
  because a `RawPtr` names the pointer and not the pointee - the one reinterpretation that stays
  C++ (`examples/sdl2/wrapper/_res.md`). A `*T` in a declaration is a raw pointer too, and
  `T` may be a layout the language can name.
- **Types.** The generator maps `Int`/`Int32`/`Int8`/`Int16`/`Int64`, `Float32`/`Float64`,
  `Bool`, `Char`, `Str` (a native `const char*`, a returned one copied into an owned `Str`) and
  `*T`; anything else is a diagnostic naming the declaration.
- **Reach-gated.** A declaration nothing calls emits no thunk, so a module's binding costs a
  program only what it uses (the rule `<section>:emit` = `reached` gives a resource), and the
  compiler's own build pays nothing for a module it carries but never calls.
- **One thunk per declaration name.** The generated symbol is `__sm_native_<declaration>`, so two
  packages that both declare, say, `open` share a thunk only when they name the same library and
  symbol; a different binding is a diagnostic rather than a silent merge.
- The loader is Windows' `LoadLibraryA`/`GetProcAddress` and POSIX's `dlopen`/`dlsym`, chosen
  by `#ifdef _WIN32` in the generator's own text (`src/compiler/NativeInvokeGen.kt`); the
  `dlopen`/`dlsym` arm is what makes the same `@SmGen("native", ...)` declaration bind on Linux.

Two worked examples ship with it. `examples/sdl2` is a window bound to `SDL2.dll` (an
opaque `SDL_Window*` as a `RawPtr`, and a `res` section that owns the `SDL_Event` union and casts
its bytes). `examples/http` is the other direction: `sockets`, a minimalist **blocking**
Winsock2 (`ws2_32.dll`) library whose glue is only `SOCKADDR_IN` layout, a byte swap written out
so `htons` need not be linked, and one receive buffer - with an HTTP/1.1 server and a client on
top (`stress/sockets` is the headless loopback check).

## Prelude

A prelude file is parsed into the same module scope as the program's inputs, so
its declarations resolve without an `import`. The default is
`src/rtl/rtl.kt` (relative to the repository root, baked into the binaries);
`simse_transpile --prelude <file>` overrides it, and a missing default is skipped
silently. Prelude declarations are resolved but **never emitted as Simse code**: a
`native` declaration's body is hand-written C++ pulled in transitively by
`src/rtl/simse.hpp`, and a `@SmGen("res", section, symbol)` declaration's body
is placed in the program's translation unit from its `src/rtl/_res.md` section
when the program reaches the declaration or its symbol (a section marked
`emit: always` is placed in every program). This is how the RTL
surface (for example `List<T>.append`) becomes available to programs.

A native extension is a body-less function whose first parameter is `this`; a
member call `recv.name(args)` lowers to `<symbol>(recv, args)` with the receiver
first.

## v1 implementation

`simse_native_readFile` backs `readFile`, declared in `src/common/common.kt` as
`@SmGen("res", "fileio", "simse_native_readFile")`:

```cpp
Str simse_native_readFile(const Str& path); // reads the whole file as bytes
```

Its prototype and definition are the `fileio` section of `src/modules/io/_res.md` (the
`io` module's own resource), in the
section's `forward:` and `bodies:` texts. The emitter places the section in the program's own
translation unit, so there is nothing to link.

## Filesystem / IO natives (T23)

The self-hosted driver needs a small filesystem surface. Declared in the `io` module
(`src/modules/io/api.kt`) as `@SmGen("res", "fileio", <symbol>)`, prototyped in the
`forward:` text of the `fileio` section of `src/modules/io/_res.md` and defined in its
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

These are `io` module declarations (a program reaches them with `--module src/modules/io` and
`import io`) whose prototypes and
definitions the emitter places in the program's own translation unit. The section
is `emit: always` because a program may also name one of these symbols with a
native declaration of its own, and then no `@SmGen` declaration reaches the
section for it; there is nothing to link.

## The argv entry point

A top-level `fun main(args: List<Str>): Int` lowers to C++
`int main(int argc, char** argv)`, with `args` built from `argv[1..]` (the program
name is excluded). The zero-argument `fun main(): Int` form is unchanged. Both
forms are lowered identically.
