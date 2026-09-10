//
// simse_transpile: the transpiler CLI. Parses the given .simse inputs (or every
// .simse under the current directory when none are given), runs name/type
// resolution, and writes one amalgamated C++ translation unit.
//
// A prelude file (default cppsrc/rtl/rtl.simse, overridable with --prelude) is
// parsed into the same module scope as the inputs so programs can call the RTL
// surface without an import. Prelude declarations resolve but are never emitted
// (impl_specs/native-interop.md).
//
// Usage: simse_transpile <input.simse>... [-o <output.cpp>] [--prelude <file>]
//

#include "Codegen.h"
#include "../common/common.h"
#include "../lex/Scanner.h"
#include "../parser/Parser.h"
#include "../sema/Sema.h"

#include <cstdio>
#include <filesystem>
#include <system_error>

#ifdef SIMSE_DEFAULT_PRELUDE
static const char *kDefaultPrelude = SIMSE_DEFAULT_PRELUDE;
#else
static const char *kDefaultPrelude = "";
#endif

using namespace common;
using namespace lex;

namespace {
    Str normalizePath(const Str &path) {
        std::error_code ec;
        std::filesystem::path canonical =
            std::filesystem::weakly_canonical(std::filesystem::path(path), ec);
        return ec ? path : canonical.string();
    }

    // The prelude declarations first, then the input's, so prelude names resolve
    // for the input while the input's own declarations win on redefinition.
    ast::Module combinedModule(const ast::Module &prelude, const ast::Module &input) {
        ast::Module combined;
        combined.pos = input.pos;
        for (const ast::Import &import: prelude.imports) {
            combined.imports.push_back(import);
        }
        for (const ast::Import &import: input.imports) {
            combined.imports.push_back(import);
        }
        for (const ast::DeclPtr &decl: prelude.declarations) {
            combined.declarations.push_back(decl);
        }
        for (const ast::DeclPtr &decl: input.declarations) {
            combined.declarations.push_back(decl);
        }
        return combined;
    }
}

int main(int argc, char **argv) {
    List<Str> inputs;
    Str output;
    Str preludePath;
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
        } else if (arg == "-h" || arg == "--help") {
            printf("usage: simse_transpile <input.simse>... -o <output.cpp>"
                   " [--prelude <file>]\n");
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

    Str resolvedPrelude = preludeExplicit ? preludePath : Str(kDefaultPrelude);
    if (!resolvedPrelude.empty()) {
        Str preludeKey = normalizePath(resolvedPrelude);
        List<Str> filtered;
        for (const Str &input: inputs) {
            if (normalizePath(input) != preludeKey) {
                filtered.push_back(input);
            }
        }
        inputs = filtered;
    }

    // Load the prelude (missing default is silently skipped; an explicit path
    // that is missing is an error).
    codegen::Input preludeInput;
    bool hasPrelude = false;
    if (!resolvedPrelude.empty()) {
        if (std::filesystem::exists(resolvedPrelude)) {
            Res<ast::Module> parsedPrelude = parser::parseFile(resolvedPrelude);
            if (!parsedPrelude.isOk()) {
                fprintf(stderr, "%s\n", parsedPrelude.Error.c_str());
                return 1;
            }
            preludeInput.fileName = resolvedPrelude;
            preludeInput.module = parsedPrelude.Value;
            preludeInput.prelude = true;
            hasPrelude = true;
        } else if (preludeExplicit) {
            fprintf(stderr, "simse_transpile: prelude not found: %s\n", resolvedPrelude.c_str());
            return 2;
        }
    }

    List<codegen::Input> modules;
    if (hasPrelude) {
        modules.push_back(preludeInput);
    }

    for (const Str &file: inputs) {
        // Resolve the file's imports (each `import a.b.c` is a directory under
        // the repo root) and merge their declarations into this module before
        // analysis and emission (specs/functions.md).
        Res<ast::Module> parsed = parser::parseFileWithImports(file, ".");
        if (!parsed.isOk()) {
            fprintf(stderr, "%s\n", parsed.Error.c_str());
            return 1;
        }

        ast::Module toAnalyze =
            hasPrelude ? combinedModule(preludeInput.module, parsed.Value) : parsed.Value;
        List<Str> diagnostics = sema::analyze(toAnalyze, file);
        if (!diagnostics.empty()) {
            for (const Str &diagnostic: diagnostics) {
                fprintf(stderr, "%s\n", diagnostic.c_str());
            }
            return 1;
        }

        codegen::Input input;
        input.fileName = file;
        input.module = parsed.Value;
        modules.push_back(input);
    }

    Res<Str> emitted = codegen::emitProgram(modules);
    if (!emitted.isOk()) {
        fprintf(stderr, "%s\n", emitted.Error.c_str());
        return 1;
    }

    FILE *out = fopen(output.c_str(), "wb");
    if (out == nullptr) {
        fprintf(stderr, "simse_transpile: cannot write %s\n", output.c_str());
        return 1;
    }
    fwrite(emitted.Value.data(), 1, emitted.Value.length(), out);
    fclose(out);
    return 0;
}
