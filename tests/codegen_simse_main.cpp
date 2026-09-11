//
// Differential Simse driver for the C++ emitter. This translation unit is
// compiled together with the C++ emitted by
// `simse_transpile cppsrc/codegen/Codegen.simse` (included directly, because the
// generated file has no header; it also carries the transpiled sema, parser, and
// scanner through its imports). It scans and parses each fixture with the
// generated front end and emits C++ with the generated `emitProgram`, matching
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

#include "Codegen.simse.cpp"

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
        if (!std::filesystem::is_directory(dir, ec)) return files;
        for (const auto &entry: std::filesystem::recursive_directory_iterator(dir, ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".simse") {
                files.push_back(entry.path().string());
            }
        }
        std::sort(files.begin(), files.end());
        return files;
    }

    bool scanTokens(const std::string &path, List<Token> &out) {
        std::string content;
        if (!readAllBytes(path, content)) return false;
        Scanner scanner(getTokenRules(), 0, 1, 1, Str());
        setSource(scanner, content);
        while (true) {
            Res<Token> result = nextToken(scanner);
            if (!result.isOk()) return false;
            if (result.Value.kind == TokenKind::Eof) return true;
            simse_list_append(out, result.Value);
        }
    }

    bool parseFile(const std::string &path, const std::string &displayName, XmlNode &out) {
        List<Token> tokens = List<Token>();
        if (!scanTokens(path, tokens)) return false;
        Res<XmlNode> parsed = parseModule(&tokens, displayName);
        if (!parsed.isOk()) return false;
        out = parsed.Value;
        return true;
    }

    // The default prelude is a directory: every `*.simse` in it is parsed and its
    // declarations merged into one module, mirroring tests::defaultPrelude.
    XmlNode mergePrelude(const std::vector<std::string> &files) {
        XmlNode merged;
        merged.name = "Module";
        merged.Children = makeList<XmlNode>();
        for (const std::string &path: files) {
            XmlNode module;
            std::string name = std::filesystem::path(path).filename().string();
            if (!parseFile(path, name, module)) continue;
            for (int i = 0; i < (int) module.Children->size(); i++) {
                if ((*module.Children)[i].name == "Import") {
                    merged.Children->push_back((*module.Children)[i]);
                }
            }
        }
        for (const std::string &path: files) {
            XmlNode module;
            std::string name = std::filesystem::path(path).filename().string();
            if (!parseFile(path, name, module)) continue;
            for (int i = 0; i < (int) module.Children->size(); i++) {
                if ((*module.Children)[i].name != "Import") {
                    merged.Children->push_back((*module.Children)[i]);
                }
            }
        }
        return merged;
    }
}

int main(int argc, char **argv) {
    std::string fixturesDir = argc > 1 ? argv[1] : ".";
    std::string preludeDir = argc > 2 ? argv[2] : SIMSE_SEMA_PRELUDE;

    std::vector<std::string> files = simseFiles(fixturesDir);
    std::vector<std::string> preludeFiles = simseFiles(preludeDir);

    XmlNode prelude;
    bool hasPrelude = false;
    if (!preludeFiles.empty()) {
        prelude = mergePrelude(preludeFiles);
        hasPrelude = true;
    }

    for (const std::string &file: files) {
        const std::string name = std::filesystem::path(file).filename().string();
        printf("=== %s ===\n", name.c_str());

        XmlNode input;
        if (!parseFile(file, name, input)) {
            continue;
        }

        List<CgInput> inputs = List<CgInput>();
        if (hasPrelude) {
            inputs.push_back(CgInput(preludeDir, prelude, true));
        }
        inputs.push_back(CgInput(name, input, false));

        Res<Str> emitted = emitProgram(inputs);
        if (emitted.isOk()) {
            printf("%s", emitted.Value.c_str());
        } else {
            printf("CodegenError %s\n", emitted.Error.c_str());
        }
    }
    return 0;
}
