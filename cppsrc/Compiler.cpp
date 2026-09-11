#include "Compiler.h"

#include "codegen/Codegen.h"
#include "parser/Parser.h"
#include "sema/Sema.h"

#include <cstdio>
#include <filesystem>
#include <system_error>

#ifdef SIMSE_DEFAULT_PRELUDE
static const char *kDefaultPrelude = SIMSE_DEFAULT_PRELUDE;
#else
static const char *kDefaultPrelude = "";
#endif

using namespace common;

namespace compiler {
    namespace {
        Str normalizePath(const Str &path) {
            std::error_code ec;
            std::filesystem::path canonical =
                std::filesystem::weakly_canonical(std::filesystem::path(path), ec);
            return ec ? path : canonical.string();
        }

        // The prelude declarations first, then the input's, so prelude names
        // resolve for the input while the input's own declarations win on
        // redefinition.
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

    int transpile(const Request &request) {
        const Str &programName = request.programName;
        List<Str> inputs = request.inputs;

        Str resolvedPrelude = request.preludeExplicit ? request.preludePath : Str(kDefaultPrelude);
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

        // Directory mode: expand the raw scan to the ordered, de-duplicated import
        // set, so a file reached through more than one import (or also present in
        // the scan) is compiled exactly once. If expansion fails (a file does not
        // parse, or an import does not resolve), fall through to per-file
        // processing so every error is reported; nothing is emitted in that case.
        bool expandedSet = false;
        if (request.directoryMode) {
            Res<List<Str>> importSet = parser::collectImportSet(inputs, request.root);
            if (importSet.isOk()) {
                inputs = importSet.Value;
                expandedSet = true;
            }
        }

        // Load the prelude set: a directory contributes every `*.simse` in it, a
        // file contributes itself. Missing defaults are skipped silently; an
        // explicit path that is missing is an error.
        codegen::Input preludeInput;
        bool hasPrelude = false;
        List<Str> preludeCanonicals;
        if (!resolvedPrelude.empty()) {
            List<Str> preludeFiles;
            if (std::filesystem::is_directory(resolvedPrelude)) {
                preludeFiles = filesInDir(resolvedPrelude, ".simse");
            } else if (std::filesystem::exists(resolvedPrelude)) {
                preludeFiles.push_back(resolvedPrelude);
            } else if (request.preludeExplicit) {
                fprintf(stderr, "%s: prelude not found: %s\n", programName.c_str(),
                        resolvedPrelude.c_str());
                return 2;
            }

            ast::Module mergedPrelude;
            mergedPrelude.pos = ast::SourcePos{0, 1, 1};
            for (const Str &preludeFile: preludeFiles) {
                Res<ast::Module> parsedPrelude = parser::parseFile(preludeFile);
                if (!parsedPrelude.isOk()) {
                    fprintf(stderr, "%s\n", parsedPrelude.Error.c_str());
                    return 1;
                }
                preludeCanonicals.push_back(normalizePath(preludeFile));
                for (const ast::Import &import: parsedPrelude.Value.imports) {
                    mergedPrelude.imports.push_back(import);
                }
                for (const ast::DeclPtr &decl: parsedPrelude.Value.declarations) {
                    mergedPrelude.declarations.push_back(decl);
                }
            }
            if (!preludeFiles.empty()) {
                preludeInput.fileName = resolvedPrelude;
                preludeInput.module = mergedPrelude;
                preludeInput.prelude = true;
                hasPrelude = true;
            }
        }

        // Directory mode: a prelude file discovered by the scan must not also be
        // compiled as an input (it would collide with the prelude's declarations).
        if (request.excludePreludeFiles && !preludeCanonicals.empty()) {
            List<Str> filtered;
            for (const Str &input: inputs) {
                Str key = normalizePath(input);
                bool isPrelude = false;
                for (const Str &preludeCanonical: preludeCanonicals) {
                    if (key == preludeCanonical) {
                        isPrelude = true;
                        break;
                    }
                }
                if (!isPrelude) {
                    filtered.push_back(input);
                }
            }
            inputs = filtered;
        }

        List<codegen::Input> modules;
        if (hasPrelude) {
            modules.push_back(preludeInput);
        }

        List<Str> errors;
        for (const Str &file: inputs) {
            // Resolve the file's imports (each `import a.b.c` is a directory
            // under the root) and merge their declarations into this module
            // before analysis and emission (specs/functions.md). In directory
            // mode the import set was already expanded, so each file is parsed on
            // its own and emitted once; if expansion failed we still resolve
            // imports per file so resolution errors are reported.
            bool parseOwnModule = request.directoryMode && expandedSet;
            Res<ast::Module> parsed = parseOwnModule
                                          ? parser::parseFile(file)
                                          : parser::parseFileWithImports(file, request.root);
            if (!parsed.isOk()) {
                if (request.collectAllErrors) {
                    errors.push_back(parsed.Error);
                    continue;
                }
                fprintf(stderr, "%s\n", parsed.Error.c_str());
                return 1;
            }

            ast::Module toAnalyze =
                hasPrelude ? combinedModule(preludeInput.module, parsed.Value) : parsed.Value;
            List<Str> diagnostics = sema::analyze(toAnalyze, file);
            if (!diagnostics.empty()) {
                if (request.collectAllErrors) {
                    for (const Str &diagnostic: diagnostics) {
                        errors.push_back(diagnostic);
                    }
                    continue;
                }
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

        if (!errors.empty()) {
            for (const Str &error: errors) {
                fprintf(stderr, "%s\n", error.c_str());
            }
            return 1;
        }

        Res<Str> emitted = codegen::emitProgram(modules);
        if (!emitted.isOk()) {
            fprintf(stderr, "%s\n", emitted.Error.c_str());
            return 1;
        }

        FILE *out = fopen(request.output.c_str(), "wb");
        if (out == nullptr) {
            fprintf(stderr, "%s: cannot write %s\n", programName.c_str(),
                    request.output.c_str());
            return 1;
        }
        fwrite(emitted.Value.data(), 1, emitted.Value.length(), out);
        fclose(out);
        return 0;
    }
}
