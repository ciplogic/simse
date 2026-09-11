//
// simse: compile a directory of .simse files into ONE amalgamated C++ file.
//
//   simse [<dir>] [-o <output.cpp>] [--prelude <path>] [--root <dir>]
//
// With no positional argument it scans the current folder (`.`). Discovery is
// recursive over `*.simse`, deterministic (sorted). The RTL prelude is loaded the
// same way the compiler does, and a prelude file discovered by the scan is not
// compiled twice. Imports are merged exactly as `parser::parseFileWithImports`
// does, relative to `--root` (default `.`).
//
// The amalgamated file is written into the current folder; the default name is
// the scanned directory's base name plus `.cpp` (`simse cppsrc` -> `./cppsrc.cpp`,
// `simse` -> `<cwd-base-name>.cpp`). `-o` overrides it. On any parse or sema
// error the positioned diagnostics are printed (one per line, deterministically
// ordered) and nothing is written.
//
// The pipeline is shared with `simse_transpile` through compiler::transpile
// (cppsrc/Compiler.cpp).
//

#include "Compiler.h"
#include "common/common.h"

#include <cstdio>
#include <filesystem>
#include <system_error>

using namespace common;

namespace {
    // The default output base name: the scanned directory's own name. For `.`
    // this is the current folder's name.
    Str dirBaseName(const Str &dir) {
        std::error_code ec;
        std::filesystem::path path = std::filesystem::weakly_canonical(std::filesystem::path(dir), ec);
        if (ec) {
            path = std::filesystem::path(dir);
        }
        Str name = path.filename().string();
        if (name.empty() || name == "." || name == "..") {
            std::error_code cwdError;
            name = std::filesystem::current_path(cwdError).filename().string();
        }
        if (name.empty()) {
            name = "out";
        }
        return name;
    }
}

int main(int argc, char **argv) {
    Str dir = ".";
    bool haveDir = false;
    Str output;
    Str preludePath;
    bool preludeExplicit = false;
    Str rootDir = ".";

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
        } else if (arg == "-h" || arg == "--help") {
            printf("usage: simse [<dir>] [-o <output.cpp>] [--prelude <path>] [--root <dir>]\n");
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

    List<Str> inputs = filesInDir(dir, ".simse");
    if (inputs.empty()) {
        printf("simse: no .simse files found under %s\n", dir.c_str());
        return 0;
    }
    if (output.empty()) {
        output = dirBaseName(dir) + ".cpp";
    }

    compiler::Request request;
    request.programName = "simse";
    request.inputs = inputs;
    request.preludePath = preludePath;
    request.preludeExplicit = preludeExplicit;
    request.root = rootDir;
    request.output = output;
    // Directory mode: expand the scan to the de-duplicated import set, drop
    // prelude files found by the scan, and report every failing file.
    request.directoryMode = true;
    request.excludePreludeFiles = true;
    request.collectAllErrors = true;

    int status = compiler::transpile(request);
    if (status == 0) {
        printf("%s\n", output.c_str());
    }
    return status;
}
