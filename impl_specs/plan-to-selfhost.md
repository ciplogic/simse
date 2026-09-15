# Plan to self-host

Status: implementation plan — exploratory and subject to revision.

## Goal

The first implementation will be written entirely in C++. The repository will
then use the compiler to transpile selected implementation files into Simse as
a stress test of the language and transpiler.

The long-term goal is to maintain as much of the compiler and supporting code
as practical in `.kt`, while keeping a small native C++ foundation for
operations that are not yet expressible or practical in Simse.

This is a migration plan, not a requirement that every C++ line eventually be
rewritten. The C++ implementation remains the bootstrap and debugging
reference while self-hosting is developed.

## Migration phases

### Phase 1: complete C++ implementation

Implement the scanner, parser, semantic stages, reifier, C++ lowering, and
amalgamator in C++. Keep the generated C++ readable and debuggable. Use the
`.kt` sources and the `tests/fixtures/` programs as input fixtures and
comparison cases.

The initial compiler may call hand-written C++ runtime code under `cppsrc/rtl`.
That directory is a native implementation boundary and does not need to be
rewritten as part of the first self-hosting attempt.

### Phase 2: Simse implementation candidates

Once the C++ compiler can transpile a useful subset, begin expressing selected
compiler components in `.kt`. Good candidates are deterministic,
allocation-light components whose required behavior is already represented by
the language, such as:

- token and source-position data types;
- parser data structures;
- name/type tables;
- generic-instantiation bookkeeping; and
- C++ text generation.

Each candidate should first have a working C++ implementation or native test
harness. Its `.kt` version is then transpiled and compared against that
baseline.

### Phase 3: dual implementation and differential testing

For a component implemented in both languages:

1. run the C++ implementation;
2. run the transpiled Simse implementation;
3. compare diagnostics, generated output, and observable results; and
4. keep the C++ implementation available until the Simse version is trusted.

The generated amalgamated `.cpp` is the artifact used for native debugging.
Disagreements should be minimized with small fixtures and source-location
comments in generated output.

### Phase 4: self-hosted build

After enough compiler functionality has been ported, use the generated Simse
compiler to transpile the next compiler version. The original C++ compiler
remains available as a bootstrap compiler, recovery path, and reference for
debugging regressions.

The project is self-hosted when the compiler can regenerate its required C++
implementation from its `.kt` sources without relying on a manually edited
implementation of the same compiler logic.

## Native fallback boundary

Some operations will not initially be implementable in Simse. Examples include
file I/O, process/environment access, operating-system integration, debugger
hooks, and target-specific compiler operations. These operations should be
isolated behind small native functions rather than spread through the Simse
implementation.

The preferred future mechanism is a native declaration in Simse paired with a
hand-written C++ definition:

```text
native fun readFile(path: Str): Str
```

The declaration contributes a normal Simse type/signature to name resolution
and type checking. It emits a call to a C++ symbol supplied by the native
implementation. The body is absent from Simse because the implementation is
owned by C++.

Native method implementations are compiled in the Simse runtime context. They
must have access to `cppsrc/rtl/simse.hpp`, either directly or through the
generated amalgamated translation unit. This gives native code access to the
runtime definitions needed to represent Simse values, including `Str`,
`List<T>`, `Dictionary<K, V>`, callable types, and other RTL-provided helpers.

Native implementations must also have declarations for the Simse program types
used by their signatures. At minimum, the generated native boundary exposes the
currently supported built-in types and the generated C++ representations of
user-defined data classes, enums, and reified generic instantiations that cross
the boundary. A native function using `Point` should therefore see the emitted
`Point` declaration, while a function using `List<Point>` should see the
corresponding concrete reified list type.

The native boundary is not required to expose every compiler-internal Simse
type. It exposes only types that occur in native declarations or are required by
the generated call. Native code may use additional C++ implementation types
internally, but those types must not leak into a Simse-visible signature.

An optional explicit symbol/library form may be added later if the source name
and C++ symbol need to differ:

```text
native("FileUtils::readFile") fun readFile(path: Str): Str
```

This syntax is a proposal, not yet a committed language feature. The exact
syntax, symbol naming, linkage, error reporting, and build integration remain
unspecified until native interoperation is needed by the compiler.

## `readFile` as the first fallback example

The existing C++ implementation is:

```cpp
namespace FileUtils {
    // This translation unit includes simse.hpp through FileUtils.h.
    Str readFile(const Str& filePath) {
        // Native filesystem implementation.
    }
}
```

The Simse-facing declaration could eventually be:

```text
native fun readFile(path: Str): Str
```

Simse code would use it normally:

```text
val source: Str = readFile("main.kt")
```

The transpiler would emit a normal C++ call to the native implementation,
without requiring Simse to model `FILE*`, `fseek`, `fread`, namespaces, or C
struct declarations. The C++ implementation remains the low-level fallback;
the Simse declaration remains the type-safe interface visible to the compiler.

The generated C++ context for this call includes the runtime header and the
declarations for `Str` and any other Simse types used by the function. The
native method can therefore use the same runtime representation as generated
Simse code without requiring a parallel C ABI model.

This boundary should prefer language-level types (`Str`, `List<T>`, `Array<T>`,
data classes, and `Res<T>`) rather than exposing C structs. If a native API
needs a representation not available in Simse, add a narrow adapter function in
C++ instead of making that representation part of the language prematurely.

## Fallback design rules

Until native interoperation is formally specified, use these implementation
principles:

- Keep native fallbacks small and deterministic.
- Expose them through typed declarations, not arbitrary embedded C++ text.
- Keep operating-system and ABI details inside C++ adapters or the RTL.
- Compile native implementations with access to `cppsrc/rtl/simse.hpp` and the
  generated declarations for all Simse-visible parameter and return types.
- Avoid passing raw pointers across the Simse/native boundary unless required.
- Prefer copying or ref-counted language values at the boundary.
- Give every native function a Simse-visible signature.
- Keep a native implementation available for every fallback used by the
  self-hosted compiler.
- Test the native and transpiled paths with the same fixtures.

## What is deliberately not specified yet

This plan does not yet define:

- the final `native` declaration syntax;
- whether native functions are declared in `.kt` files or a separate manifest;
- C++ symbol mangling and namespaces;
- static versus dynamic native libraries;
- ownership transfer rules for native pointers;
- exception/error translation from C++;
- automatic generation of C++ declarations; or
- the exact mechanism used to expose generated user-type declarations to native
  translation units; or
- whether native fallbacks can be replaced by Simse implementations without
  changing call sites.

Those decisions should be made when the first actual fallback beyond
`readFile` requires them, rather than adding a broad FFI system prematurely.
