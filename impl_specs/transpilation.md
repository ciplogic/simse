# Transpilation and implementation workflow

Status: implementation baseline.

## Source files

Simse source files use the `.simse` extension. A project may contain one or
more `.simse` files, and the transpiler discovers and processes those files as
the language source set.

For example:

```text
main.simse
cppsrc/lex/Scanner.simse
```

The existing `../cppsrc/main.simse` file is the sample source used to compare Simse
syntax with its current hand-written C++ counterpart, `../cppsrc/main.cpp`.

## Generated C++ output

The final transpilation result is one amalgamated `.cpp` file. It contains the
generated C++ for all compiled `.simse` source files in the project, arranged so
that it can be opened, compiled, stepped through, and debugged as one ordinary
C++ translation unit.

The final `.cpp` file is intentionally a first-class debugging artifact. When
the generated behavior is incorrect, developers can inspect the generated C++
in a debugger such as Visual Studio and compare it with the intended Simse
source and runtime behavior.

The amalgamated file must preserve source mapping information where practical,
for example with comments containing the originating `.simse` path and line
range. Exact debugger mapping directives are an implementation detail, but the
generated file must remain readable enough for manual comparison.

## Staged transpilation

Transpilation is performed in stages. Each stage consumes the representation
from the previous stage and produces the representation required by the next
stage. At minimum, the implementation is expected to separate:

1. source discovery and loading;
2. lexing/parsing;
3. name and type resolution;
4. generic reification and specialization;
5. lowering of Simse constructs to C++ constructs; and
6. C++ amalgamation and final file emission.

The exact internal data structures and number of stages are implementation
details. Stages may use temporary in-memory or on-disk artifacts, but the
externally useful final artifact is the single amalgamated `.cpp` file.

If intermediate files are emitted for debugging, they must be clearly marked as
generated stage output and must not be mistaken for the final output. Their
naming and retention policy are not yet fixed.

## Authority and comparison model

The generated C++ file is the reference implementation for the transpiler's
actual emitted behavior. The compiler may initially contain bugs, and the
generated C++ exists specifically so those bugs can be inspected and compared
in a native C++ debugger.

The `.simse` source remains the language-level intent and the input to
transpilation. Generated C++ is not edited as a way to change the language
program; fixes should be made in the transpiler or Simse source and then
regenerated.

When a behavior is not specified by a language or implementation spec, the
generated C++ output is the authoritative behavior for that build. An explicit
specification is the exception: the specification defines the required
behavior, and generated C++ that contradicts it is a transpiler bug.

This gives the project two useful comparison points:

- `.simse`: intended source program;
- final amalgamated `.cpp`: concrete emitted/reference implementation.

The current `../cppsrc/main.simse` and `../cppsrc/main.cpp` pair is an example of this comparison
workflow, not a requirement that every generated file remain manually edited
side by side forever.

## Runtime and RTL boundary

Files under `cppsrc/rtl` are low-level runtime implementation files. They may
remain hand-written C++ and are not required to be implemented in Simse.
Transpiled Simse code may include and call the RTL, but the RTL itself is an
implementation boundary rather than part of the self-hosted Simse source set.

The RTL may therefore use C++ facilities, compiler intrinsics, platform APIs,
and low-level memory operations that are not yet expressible in Simse. Its
behavior must still satisfy the relevant language specifications when exposed
to generated Simse code.

## Final output requirements

The transpiler must:

- accept `.simse` source files;
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
