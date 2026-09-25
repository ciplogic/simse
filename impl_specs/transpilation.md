# Transpilation and implementation workflow

Status: implementation baseline.

## Source files

Simse source files use the `.kt` extension. A project may contain one or more
`.kt` files, and the transpiler discovers and processes those files as the
language source set, e.g.:

```text
main.kt
cppsrc/lex/Scanner.kt
```

## Generated C++ output

The final transpilation result is one amalgamated `.cpp` file containing the generated C++ for
every compiled `.kt` file, arranged so it can be compiled and debugged as one ordinary C++
translation unit - a first-class debugging artifact: when generated behavior is incorrect,
developers inspect the C++ in a debugger and compare it with the intended Simse source and
runtime behavior.

The file must preserve source mapping where practical, for example with comments containing
the originating `.kt` path and line range. Exact debugger mapping directives are an
implementation detail, but the file must remain readable enough for manual comparison.

## Staged transpilation

Transpilation is staged; each stage consumes the previous stage's representation
and produces the next stage's. At minimum the implementation separates:

1. source discovery and loading;
2. lexing/parsing;
3. name and type resolution;
4. generic reification and specialization;
5. lowering of Simse constructs to C++ constructs (`impl_specs/linear-lowering.md`
   rewrites every body into labels and gotos, then prunes them, before emission,
   so the emitter only handles the linear statement forms); and
6. C++ amalgamation and final file emission.

The exact data structures and number of stages are implementation details.
Stages may use temporary in-memory or on-disk artifacts, but the externally
useful final artifact is the single amalgamated `.cpp` file.

If intermediate files are emitted for debugging, they must be clearly marked as
generated stage output and must not be mistaken for the final output. Their
naming and retention policy are not yet fixed.

## Authority and comparison model

The generated C++ file is the reference implementation for the transpiler's actual emitted
behavior.

The `.kt` source remains the language-level intent and the input to transpilation. Generated
C++ is not edited to change the language program; fixes go in the transpiler or Simse source
and are then regenerated.

When a behavior is not specified by a language or implementation spec, the generated C++
output is authoritative for that build. An explicit specification is the exception: it defines
the required behavior, and generated C++ that contradicts it is a transpiler bug.

The two comparison points:

- `.kt`: intended source program;
- final amalgamated `.cpp`: concrete emitted/reference implementation.

## Runtime and RTL boundary

Files under `cppsrc/rtl` are low-level runtime implementation files. They may
remain hand-written C++ and are not required to be implemented in Simse.
Transpiled Simse code may include and call the RTL, but the RTL itself is an
implementation boundary rather than part of the self-hosted Simse source set.

The RTL may therefore use C++ facilities, compiler intrinsics, platform APIs, and
low-level memory operations that are not yet expressible in Simse. Its behavior
must still satisfy the relevant language specifications when exposed to generated
Simse code.

## Final output requirements

The transpiler must:

- accept `.kt` source files;
- process them through the staged pipeline;
- emit one final amalgamated `.cpp` file;
- retain or include required RTL declarations/definitions through the normal
  C++ build boundary;
- make generated source readable enough for debugger comparison; and
- report the originating Simse file and source location for transpilation
  errors whenever possible.

The exact final output path, command-line interface, intermediate artifact
layout, and build-system integration remain implementation decisions unless
specified by a later implementation spec.
