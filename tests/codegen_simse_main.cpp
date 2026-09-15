//
// Differential Simse driver for the C++ emitter. This translation unit is
// compiled together with the C++ emitted by
// `simse_transpile cppsrc/codegen/Codegen.kt` (included directly, because the
// generated file has no header; it also carries the transpiled sema, parser, and
// scanner through its imports). It scans and parses each fixture with the
// generated front end and emits C++ with the generated `ns1_emitProgram`, matching
// how the `.cpp.expected` goldens were produced (the RTL prelude merged into one
// input), so the build can diff it against tests/codegen_ref_main.cpp.
//
// Usage: codegen_simse <fixtures-dir> [prelude-dir]
//

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "Codegen.kt.cpp"

// The generated translation unit qualifies every package's declarations with
// `ns<index>_`, numbered in sorted package order (impl_specs/rtl-abi.md):
// `codegen` is 1, `common` 2, `lex` 3, `linear` 4, `parser` 5, `sema` 6, so the
// emitter's API below is `ns1_*`, the scanner surface `ns3_*`, and the parser
// entry point `ns5_parseModule`.

#ifndef SIMSE_SEMA_PRELUDE
#define SIMSE_SEMA_PRELUDE ""
#endif

namespace {
    bool readAllBytes(const std::string &path, std::string &out) {
        std::ifstream in(path, std::ios::binary);
        if (!in) return false;
        out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
        return true;
    }

    std::vector<std::string> simseFiles(const std::string &dir) {
        std::vector<std::string> files;
        std::error_code ec;
        if (!std::filesystem::is_directory(std::filesystem::path(simse_toStdString(dir)), ec)) return files;
        for (const auto &entry: std::filesystem::recursive_directory_iterator(std::filesystem::path(simse_toStdString(dir)), ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".kt") {
                files.push_back(entry.path().string());
            }
        }
        std::sort(files.begin(), files.end());
        return files;
    }

    bool scanTokens(const std::string &path, List<ns3_Token> &out) {
        std::string content;
        if (!readAllBytes(path, content)) return false;
        ns3_Scanner scanner(ns3_getTokenRules(), 0, 1, 1, Str());
        ns3_setSource(&scanner, content);
        while (true) {
            Res<ns3_Token> result = ns3_nextToken(&scanner);
            if (!result.isOk()) return false;
            if (result.Value.kind == ns3_TokenKind::Eof) return true;
            simse_list_append(out, result.Value);
        }
    }

    bool parseFile(const std::string &path, const std::string &displayName, AstXmlNode &out) {
        List<ns3_Token> tokens = List<ns3_Token>();
        if (!scanTokens(path, tokens)) return false;
        Res<AstXmlNode> parsed = ns5_parseModule(&tokens, displayName);
        if (!parsed.isOk()) return false;
        out = parsed.Value;
        return true;
    }

    // The default prelude is a directory: every `*.kt` in it is parsed and its
    // declarations merged into one module, mirroring tests::defaultPrelude.
    AstXmlNode mergePrelude(const std::vector<std::string> &files) {
        AstXmlNode merged;
        merged.name = AstNodeKind::Module;
        List<AstXmlNode> children;
        for (const std::string &path: files) {
            AstXmlNode module;
            std::string name = std::filesystem::path(simse_toStdString(path)).filename().string();
            if (!parseFile(path, name, module)) continue;
            for (int i = 0; i < module.Children.count(); i++) {
                if (module.Children[i].name == AstNodeKind::Import) {
                    children.push_back(module.Children[i]);
                }
            }
        }
        for (const std::string &path: files) {
            AstXmlNode module;
            std::string name = std::filesystem::path(simse_toStdString(path)).filename().string();
            if (!parseFile(path, name, module)) continue;
            for (int i = 0; i < module.Children.count(); i++) {
                if (module.Children[i].name != AstNodeKind::Import) {
                    children.push_back(module.Children[i]);
                }
            }
        }
        merged.Children = simse_list_toArray(children);
        return merged;
    }
}

int main(int argc, char **argv) {
    // The generated component's static storage (the scanner's tables, specs/statics.md)
    // is filled by the pass the emitted file defines; a host that links a component
    // without a `main` of its own has to run it first.
    simse_initStatics();
    std::string fixturesDir = argc > 1 ? argv[1] : ".";
    std::string preludeDir = argc > 2 ? argv[2] : SIMSE_SEMA_PRELUDE;

    std::vector<std::string> files = simseFiles(fixturesDir);
    std::vector<std::string> preludeFiles = simseFiles(preludeDir);

    AstXmlNode prelude;
    bool hasPrelude = false;
    if (!preludeFiles.empty()) {
        prelude = mergePrelude(preludeFiles);
        hasPrelude = true;
    }

    for (const std::string &file: files) {
        const std::string name = std::filesystem::path(simse_toStdString(file)).filename().string();
        printf("=== %s ===\n", name.c_str());

        AstXmlNode input;
        if (!parseFile(file, name, input)) {
            continue;
        }

        List<ns1_CgInput> inputs = List<ns1_CgInput>();
        if (hasPrelude) {
            inputs.push_back(ns1_CgInput(preludeDir, prelude, true));
        }
        inputs.push_back(ns1_CgInput(name, input, false));

        Res<Str> emitted = ns1_emitProgram(inputs);
        if (emitted.isOk()) {
            printf("%s", emitted.Value.c_str());
        } else {
            printf("CodegenError %s\n", emitted.Error.c_str());
        }
    }
    return 0;
}
