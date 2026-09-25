# Plan to self-host

Status: implementation plan — exploratory and subject to revision.

## Goal

The first implementation is written entirely in C++, and the repository then uses the compiler
to transpile selected implementation files into Simse as a stress test of the language and
transpiler. The long-term goal is to maintain as much of the compiler and supporting code as
practical in `.kt`, with a small native C++ foundation for operations that are not yet
expressible or practical in Simse. This is a migration plan, not a requirement that every C++
line be rewritten; the C++ implementation remains the bootstrap and debugging reference.

## Migration phases

### Phase 1: complete C++ implementation

Implement the scanner, parser, semantic stages, reifier, C++ lowering, and amalgamator in
C++. Keep the generated C++ readable and debuggable. Use the `.kt` sources and the
`tests/fixtures/` programs as input fixtures and comparison cases. The compiler may call
hand-written C++ runtime code under `cppsrc/rtl`, a native boundary that need not be
rewritten for the first self-hosting attempt.

### Phase 2: Simse implementation candidates

Once the C++ compiler can transpile a useful subset, express selected components in `.kt` -
deterministic, allocation-light components whose behavior the language already represents:

- token and source-position data types;
- parser data structures;
- name/type tables;
- generic-instantiation bookkeeping; and
- C++ text generation.

Each candidate first gets a working C++ implementation or native test harness; its `.kt`
version is transpiled and compared against that baseline.

### Phase 3: dual implementation and differential testing

For a component in both languages: run the C++ implementation; run the transpiled Simse
implementation; compare diagnostics, generated output, and observable results; keep the C++
implementation until the Simse version is trusted. The generated amalgamated `.cpp` is the
artifact used for native debugging. Minimize disagreements with small fixtures and
source-location comments in generated output.

### Phase 4: self-hosted build

After enough functionality has been ported, use the generated Simse compiler to transpile the
next compiler version; the original C++ compiler remains a bootstrap compiler, recovery path,
and reference for debugging regressions. The project is self-hosted when the compiler can
regenerate its required C++ implementation from its `.kt` sources without relying on a
manually edited implementation of the same compiler logic.

## Native fallback boundary

Some operations will not initially be implementable in Simse: file I/O, process/environment
access, operating-system integration, debugger hooks, target-specific compiler operations.
Isolate them behind small native functions rather than spreading them through the Simse
implementation.

The preferred mechanism is a native declaration in Simse paired with a hand-written C++
definition:

```text
native fun readFile(path: Str): Str
```

The declaration contributes a normal Simse type/signature to name resolution and type
checking and emits a call to a C++ symbol supplied by the native implementation; the body is
absent from Simse because C++ owns it.

Native method implementations are compiled in the Simse runtime context, with access to
`cppsrc/rtl/simse.hpp` (directly or through the generated amalgamated translation unit), so
native code can represent Simse values: `Str`, `List<T>`, `Dictionary<K, V>`, callable types,
and other RTL-provided helpers.

Native implementations also need declarations for the Simse program types used by their
signatures. At minimum the generated native boundary exposes the supported built-in types and
the generated C++ representations of user-defined data classes, enums, and reified generic
instantiations that cross the boundary. A function using `Point` sees the emitted `Point`
declaration; one using `List<Point>` sees the concrete reified list type. The boundary is not
required to expose every compiler-internal Simse type, only types that occur in native
declarations or are required by the generated call. Native code may use additional C++
implementation types internally, but those types must not leak into a Simse-visible signature.

An optional explicit symbol/library form may be added later if the source name and C++ symbol
need to differ:

```text
native("FileUtils::readFile") fun readFile(path: Str): Str
```

This syntax is a proposal, not a committed language feature.

## `readFile` as the first fallback example

The C++ is a `FileUtils` namespace whose translation unit includes `simse.hpp` through
`FileUtils.h`, with `Str readFile(const Str& filePath)`; its Simse-facing declaration is
`native fun readFile` above, used normally (`val source: Str = readFile("main.kt")`). The
transpiler emits a normal C++ call, without Simse modelling `FILE*`, `fseek`, `fread`,
namespaces, or C struct declarations: the C++ stays the low-level fallback, the Simse
declaration the type-safe interface, and the generated context includes the runtime header and
the declarations for `Str` and any other Simse types used.

Prefer language-level types (`Str`, `List<T>`, `Array<T>`, data classes, `Res<T>`) over C
structs. If a native API needs a representation unavailable in Simse, add a narrow C++
adapter instead of making that representation part of the language prematurely.

## Fallback design rules

Until native interoperation is formally specified:

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

- the final `native` declaration syntax;
- whether native functions are declared in `.kt` files or a separate manifest;
- C++ symbol mangling and namespaces;
- static versus dynamic native libraries;
- ownership transfer rules for native pointers;
- exception/error translation from C++;
- automatic generation of C++ declarations;
- the mechanism exposing generated user-type declarations to native translation units;
- whether native fallbacks can be replaced by Simse implementations without changing call
  sites.

Decide these when the first fallback beyond `readFile` needs them, rather than adding a broad
FFI system prematurely.
