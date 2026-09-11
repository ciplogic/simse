//
// simse: compile a module (a directory of .simse files) into ONE amalgamated
// C++ file.
//
//   simse [<dir>] [-o <output.cpp>] [--prelude <path>]
//         [--root <dir>] [--module-root <dir>]...
//
// The scan directory is the project root: every `.simse` file under it (and
// under each extra `--module-root`) is part of the compilation. With no
// positional argument and no `--root` it scans the current folder (`.`).
// Discovery is recursive over `*.simse`, deterministic (sorted). The RTL prelude
// is loaded the same way the compiler does, and a prelude file discovered by the
// scan is not compiled twice. `import a.b.c` makes package `a.b.c` visible
// unqualified and never adds files, so resolution does not depend on the current
// working directory.
//
// The amalgamated file is written into the current folder; the default name is
// `simse_out.cpp` (`simse cppsrc` -> `./simse_out.cpp`). `-o` overrides it. On
// any parse or sema error the positioned diagnostics are printed (one per line,
// deterministically ordered) and nothing is written.
//
// The pipeline is shared with `simse_transpile` through compiler::transpile
// (cppsrc/Compiler.cpp).
//

#include "Compiler.h"
#include "common/common.h"

#include <cstdio>

using namespace common;

int main(int argc, char **argv) {
    Str dir = ".";
    bool haveDir = false;
    Str rootDir;
    bool haveRoot = false;
    List<Str> extraRoots;
    Str output;
    Str preludePath;
    bool preludeExplicit = false;

    for (int i = 1; i < argc; i++) {
        Str arg = argv[i];
        if (arg == "-o") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse: -o requires a path\n");
                return 2;
            }
            output = argv[++i];
        } else if (arg == "--prelude") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse: --prelude requires a path\n");
                return 2;
            }
            preludePath = argv[++i];
            preludeExplicit = true;
        } else if (arg == "--root") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse: --root requires a path\n");
                return 2;
            }
            rootDir = argv[++i];
            haveRoot = true;
        } else if (arg == "--module-root") {
            if (i + 1 >= argc) {
                fprintf(stderr, "simse: --module-root requires a path\n");
                return 2;
            }
            extraRoots.push_back(argv[++i]);
        } else if (arg == "-h" || arg == "--help") {
            printf("usage: simse [<dir>] [-o <output.cpp>] [--prelude <path>]"
                   " [--root <dir>] [--module-root <dir>]...\n");
            return 0;
        } else {
            if (haveDir) {
                fprintf(stderr, "simse: unexpected argument '%s'\n", arg.c_str());
                return 2;
            }
            dir = arg;
            haveDir = true;
        }
    }

    Str projectRoot = haveRoot ? rootDir : dir;

    compiler::Request request;
    request.programName = "simse";
    request.moduleRoots.push_back(projectRoot);
    for (const Str &extraRoot: extraRoots) {
        request.moduleRoots.push_back(extraRoot);
    }
    request.preludePath = preludePath;
    request.preludeExplicit = preludeExplicit;
    request.output = output.empty() ? Str("simse_out.cpp") : output;
    request.collectAllErrors = true;

    int status = compiler::transpile(request);
    if (status == 0) {
        printf("%s\n", request.output.c_str());
    }
    return status;
}
