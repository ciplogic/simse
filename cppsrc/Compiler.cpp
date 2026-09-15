#include "Compiler.h"

#include "codegen/Codegen.h"
#include "linear/LinearForm.h"
#include "parser/Parser.h"
#include "sema/Sema.h"

#include <algorithm>
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
            std::filesystem::path canonical = std::filesystem::weakly_canonical(
                    std::filesystem::path(simse_toStdString(path)), ec);
            return ec ? path : simse_fromStdString(canonical.string());
        }
    }

    int transpile(const Request &request) {
        const Str &programName = request.programName;

        // The linear-form dump is a debug view of what the emitter is about to
        // read; it never reaches the emitted file (impl_specs/linear-il.md).
        linear::setShowIl(request.showLinearRepresentation);

        // Load the prelude set: a directory contributes every `*.kt` in it, a
        // file contributes itself. Missing defaults are skipped silently; an
        // explicit path that is missing is an error. Each file is kept separate so
        // sema sees its declared package (`rtl`); the merged module is what codegen
        // consumes as the single never-emitted prelude input.
        Str resolvedPrelude = request.preludeExplicit ? request.preludePath : Str(kDefaultPrelude);
        List<Str> preludeFiles;
        if (!resolvedPrelude.empty()) {
            if (std::filesystem::is_directory(simse_toStdString(resolvedPrelude))) {
                preludeFiles = filesInDir(resolvedPrelude, ".kt");
            } else if (std::filesystem::exists(simse_toStdString(resolvedPrelude))) {
                preludeFiles.push_back(resolvedPrelude);
            } else if (request.preludeExplicit) {
                fprintf(stderr, "%s: prelude not found: %s\n", programName.c_str(),
                        resolvedPrelude.c_str());
                return 2;
            }
        }

        List<Str> preludeNames;
        List<ast::Module> preludeModules;
        ast::Module mergedPrelude;
        mergedPrelude.pos = ast::SourcePos{0, 1, 1};
        Dictionary<Str, bool> preludeCanon;
        for (const Str &preludeFile: preludeFiles) {
            Res<ast::Module> parsedPrelude = parser::parseFile(preludeFile);
            if (!parsedPrelude.isOk()) {
                fprintf(stderr, "%s\n", parsedPrelude.Error.c_str());
                return 1;
            }
            preludeCanon[normalizePath(preludeFile)] = true;
            preludeNames.push_back(preludeFile);
            preludeModules.push_back(parsedPrelude.Value);
            for (const ast::Import &import: parsedPrelude.Value.imports) {
                mergedPrelude.imports.push_back(import);
            }
            for (const ast::DeclPtr &decl: parsedPrelude.Value.declarations) {
                mergedPrelude.declarations.push_back(decl);
            }
        }
        bool hasPrelude = !preludeFiles.empty();

        // Gather the compilation: every `*.kt` under each module root, then the
        // explicit inputs. Files already loaded as prelude are excluded, and each
        // canonical path is included once. The kept files are then sorted by
        // The kept files are then sorted by
        // canonical path so the compilation order depends only on the file set,
        // not on how it was specified: `--root cppsrc` and the explicit
        // `cppsrc/compiler/Driver.kt` input emit identical C++. After dedup
        // the canonical keys are unique, so the sort is total and both compiler
        // rings agree.
        List<Str> candidates;
        for (const Str &root: request.moduleRoots) {
            for (const Str &file: filesInDir(root, ".kt")) {
                candidates.push_back(file);
            }
        }
        for (const Str &file: request.inputs) {
            candidates.push_back(file);
        }

        List<Str> chosen;
        Dictionary<Str, bool> seen;
        for (const Str &display: candidates) {
            Str canon = normalizePath(display);
            if (preludeCanon.count(canon) > 0) continue;
            if (seen.count(canon) > 0) continue;
            seen[canon] = true;
            chosen.push_back(display);
        }
        std::sort(chosen.begin(), chosen.end(),
                  [](const Str &a, const Str &b) { return normalizePath(a) < normalizePath(b); });

        List<Str> fileNames;
        List<ast::Module> modules;
        List<Str> errors;
        for (const Str &display: chosen) {
            Res<ast::Module> parsed = parser::parseFile(display);
            if (!parsed.isOk()) {
                if (request.collectAllErrors) {
                    errors.push_back(parsed.Error);
                    continue;
                }
                fprintf(stderr, "%s\n", parsed.Error.c_str());
                return 1;
            }
            fileNames.push_back(display);
            modules.push_back(parsed.Value);
        }
        if (!errors.empty()) {
            for (const Str &error: errors) {
                fprintf(stderr, "%s\n", error.c_str());
            }
            return 1;
        }

        // Compilation-wide name/type resolution over the prelude and every module
        // (specs/modules.md): declarations are grouped by package, imports are
        // validated against the scanned packages, and `rtl` is implicitly in scope.
        List<sema::Input> semaInputs;
        for (int i = 0; i < (int) preludeModules.size(); i++) {
            sema::Input input;
            input.fileName = preludeNames[i];
            input.module = &preludeModules[i];
            semaInputs.push_back(input);
        }
        for (int i = 0; i < (int) modules.size(); i++) {
            sema::Input input;
            input.fileName = fileNames[i];
            input.module = &modules[i];
            semaInputs.push_back(input);
        }
        List<Str> diagnostics = sema::analyze(semaInputs);
        if (!diagnostics.empty()) {
            for (const Str &diagnostic: diagnostics) {
                fprintf(stderr, "%s\n", diagnostic.c_str());
            }
            return 1;
        }

        List<codegen::Input> cgInputs;
        if (hasPrelude) {
            codegen::Input preludeInput;
            preludeInput.fileName = resolvedPrelude;
            preludeInput.module = mergedPrelude;
            preludeInput.prelude = true;
            cgInputs.push_back(preludeInput);
        }
        for (int i = 0; i < (int) modules.size(); i++) {
            codegen::Input input;
            input.fileName = fileNames[i];
            input.module = modules[i];
            cgInputs.push_back(input);
        }

        Res<Str> emitted = codegen::emitProgram(cgInputs);
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
