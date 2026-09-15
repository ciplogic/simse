//
// simse_transpile: the low-level transpiler CLI. Parses the given .kt inputs
// (or every .kt under the current directory when none are given), runs
// name/type resolution over the whole compilation, and writes one amalgamated
// C++ translation unit.
//
// The module roots (`--root`, and each repeatable `--module-root`) are scanned
// recursively; every `.kt` file found is part of the compilation, and the
// explicit inputs are added on top. `import a.b.c` makes package `a.b.c` visible
// unqualified and never adds files (specs/modules.md), so pass the module roots
// that hold the imported packages.
//
// A prelude file (default cppsrc/rtl, overridable with --prelude) is parsed into
// the same compilation as the inputs so programs can call the RTL surface
// without an import. Prelude declarations resolve but are never emitted
// (impl_specs/native-interop.md).
//
// Usage: simse_transpile <input.kt>... [-o <output.cpp>] [--prelude <file>]
//                       [--root <dir>] [--module-root <dir>]...
//
// The output file defaults to `simse_out.cpp` in the current folder - the amalgamated
// compiler, the same file the published `cppsrc/simse_bootstrap.cpp` is a copy of
// (docs/getting-started.md, "Building the compiler without a compiler"). Pass `-o` to
// write anywhere else.
//
// The parse -> sema -> codegen -> write pipeline lives in compiler::transpile
// (cppsrc/Compiler.cpp), shared with the `simse` directory compiler.
//

#include "../Compiler.h"
#include "../common/common.h"

#include <cstdio>

using namespace common;

int main(int argc, char **argv) {
    List<Str> inputs;
    Str output;
    Str preludePath;
    Str rootDir;
    bool haveRoot = false;
    List<Str> extraRoots;
    bool preludeExplicit = false;
    bool showIl = false;
    bool linearCodegen = false;
    bool linearCodegenEmit = true;
    for (int i = 1; i < argc; i++) {
        Str arg = argv[i];
        if (arg == "-o") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse_transpile: -o requires a path\n");
                return 2;
            }
            output = argv[++i];
        } else if (arg == "--prelude") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse_transpile: --prelude requires a path\n");
                return 2;
            }
            preludePath = argv[++i];
            preludeExplicit = true;
        } else if (arg == "--root") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse_transpile: --root requires a path\n");
                return 2;
            }
            rootDir = argv[++i];
            haveRoot = true;
        } else if (arg == "--module-root") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse_transpile: --module-root requires a path\n");
                return 2;
            }
            extraRoots.push_back(argv[++i]);
        } else if (arg == "--showLinearRepresentation") {
            showIl = true;
        } else if (arg == "--linearCodegen") {
            linearCodegen = true;
            linearCodegenEmit = true;
        } else if (arg == "--linearCodegenEmit") {
            linearCodegen = true;
            linearCodegenEmit = true;
        } else if (arg == "--statementsCodegen") {
            // The escape hatch: the statement tree emits every body, exactly as before
            // the IL became the source of the output.
            linearCodegenEmit = false;
        } else if (arg == "-h" || arg == "--help") {
            printf("usage: simse_transpile <input.kt>... [-o <output.cpp>]"
                   " [--prelude <file>] [--root <dir>] [--module-root <dir>]..."
                   " [--showLinearRepresentation] [--linearCodegen] [--statementsCodegen]\n");
            return 0;
        } else {
            inputs.push_back(arg);
        }
    }

    if (inputs.empty() && !haveRoot && extraRoots.empty()) {
        rootDir = ".";
        haveRoot = true;
    }
    if (output.empty()) {
        output = "simse_out.cpp";
    }

    compiler::Request request;
    request.programName = "simse_transpile";
    request.inputs = inputs;
    if (haveRoot) {
        request.moduleRoots.push_back(rootDir);
    }
    for (const Str &extraRoot: extraRoots) {
        request.moduleRoots.push_back(extraRoot);
    }
    request.preludePath = preludePath;
    request.preludeExplicit = preludeExplicit;
    request.output = output;
    request.showLinearRepresentation = showIl;
    request.linearCodegen = linearCodegen;
    request.linearCodegenEmit = linearCodegenEmit;
    return compiler::transpile(request);
}
