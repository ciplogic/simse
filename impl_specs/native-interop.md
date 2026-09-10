# Native interop (`native fun`)

Status: decision recorded for T10.

This document fixes the v1 boundary for calling hand-written C++ from Simse.

## Declaration form

```text
native fun readFile(path: Str): Str
native("simse_native_readFile") fun readFile(path: Str): Str
```

- `native fun name(params): Ret` introduces a function with a Simse
  type/signature and **no body**.
- `native("Symbol") fun name(...)` supplies the C++ symbol when it differs from
  the Simse name. Without it, the C++ symbol defaults to the Simse name.
- In v1 the symbol must be a **plain global identifier**. A namespaced symbol
  such as `FileUtils::readFile` is rejected with a positioned `unsupported`
  diagnostic; expose such an implementation through a thin global wrapper whose
  name is the global identifier.

## Emission and call

- The declaration contributes a normal signature to resolution; calls type-check
  like any other function (including arity).
- Generated code does **not** emit a body for a native function.
- Generated code emits one C++ declaration of the symbol, once, at the top of the
  amalgamated translation unit, using the mapped Simse parameter/return types.
  Native parameters are borrowed as `const T&`; the hand-written definition must
  use the same signature. Calls lower to a direct call of the symbol:

  ```text
  // native_readfile.simse:1
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
exceptions. A `native fun` that returns `Res<T>` maps its error message into the
`Res` value; the runtime shim defines `isOk()` as "the error string is empty".

## Prelude

A prelude file is parsed into the same module scope as the program's inputs, so
its declarations resolve without an `import`. The default is
`cppsrc/rtl/rtl.simse` (relative to the repository root, baked into the binaries);
`simse_transpile --prelude <file>` overrides it, and a missing default is skipped
silently. Prelude declarations are resolved but **never emitted**: their bodies
are hand-written C++ pulled in transitively by `cppsrc/rtl/simse.hpp`. This is how
the RTL surface (for example `List<T>.append`) becomes available to programs.

A native extension is a `native` function whose first parameter is `this`; a
member call `recv.name(args)` lowers to `<symbol>(recv, args)` with the receiver
first.

## v1 implementation

`cppsrc/native/Native.h` / `Native.cpp` provide the first native symbol:

```cpp
Str simse_native_readFile(const Str& path); // forwards to common::readFile
```

They build into the `simse_native` static library. Generated executables that
use native functions link `simse_native` (which links the rest of the RTL).
