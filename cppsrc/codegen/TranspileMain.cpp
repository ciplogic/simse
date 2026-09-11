//
// simse_transpile: the low-level transpiler CLI. Parses the given .simse inputs
// (or every .simse under the current directory when none are given), runs
// name/type resolution, and writes one amalgamated C++ translation unit.
//
// A prelude file (default cppsrc/rtl, overridable with --prelude) is parsed into
// the same module scope as the inputs so programs can call the RTL surface
// without an import. Prelude declarations resolve but are never emitted
// (impl_specs/native-interop.md).
//
// Usage: simse_transpile <input.simse>... [-o <output.cpp>] [--prelude <file>]
//
// The parse -> sema -> codegen -> write pipeline lives in compiler::transpile
// (cppsrc/Compiler.cpp), shared with the `simse` directory compiler.
//

#include "../Compiler.h"
#include "../common/common.h"

#include <cstdio>

// The repository root used to resolve `import a.b.c` directories. Baked in at
// configure time so the CLI works from any working directory; --root overrides.
#ifdef SIMSE_SOURCE_ROOT
static const char *kSourceRoot = SIMSE_SOURCE_ROOT;
#else
static const char *kSourceRoot = ".";
#endif

using namespace common;

int main(int argc, char **argv) {
    List<Str> inputs;
    Str output;
    Str preludePath;
    Str rootDir = kSourceRoot;
    bool preludeExplicit = false;
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
        } else if (arg == "-h" || arg == "--help") {
            printf("usage: simse_transpile <input.simse>... -o <output.cpp>"
                   " [--prelude <file>] [--root <dir>]\n");
            return 0;
        } else {
            inputs.push_back(arg);
        }
    }

    if (inputs.empty()) {
        inputs = filesInDir(".", ".simse");
    }
    if (inputs.empty()) {
        fprintf(stderr, "simse_transpile: no .simse inputs found\n");
        return 2;
    }
    if (output.empty()) {
        fprintf(stderr, "simse_transpile: missing -o <output.cpp>\n");
        return 2;
    }

    compiler::Request request;
    request.programName = "simse_transpile";
    request.inputs = inputs;
    request.preludePath = preludePath;
    request.preludeExplicit = preludeExplicit;
    request.root = rootDir;
    request.output = output;
    return compiler::transpile(request);
}
