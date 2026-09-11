//
// Differential Simse driver for the sema pass. This translation unit is compiled
// together with the C++ emitted by `simse_transpile cppsrc/sema/Sema.simse`
// (included directly, because the generated file has no header; it also carries
// the transpiled parser and scanner through its imports). It scans and parses
// each fixture with the generated front end, merges the RTL prelude the same way
// the reference does, and prints the generated `analyze` diagnostics, so the
// build can diff them against tests/sema_ref_main.cpp.
//
// Usage: sema_simse <fixtures-dir> <prelude-dir>
//

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "Sema.simse.cpp"

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

    // Every `*.simse` directly or transitively under `dir`, sorted, matching
    // common::filesInDir so the prelude merge order agrees with the reference.
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

    // Scans `path` into `out`, dropping the Eof. Returns false on a scan error.
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

    // Merges prelude modules and the input into one Module node, mirroring
    // tests::combineWithPrelude (prelude imports, input imports, prelude decls,
    // input decls).
    XmlNode combine(const std::vector<XmlNode> &prelude, const XmlNode &input) {
        XmlNode combined;
        combined.name = "Module";
        combined.Children = makeList<XmlNode>();
        for (const XmlNode &module: prelude) {
            for (int i = 0; i < (int) module.Children->size(); i++) {
                if ((*module.Children)[i].name == "Import") {
                    combined.Children->push_back((*module.Children)[i]);
                }
            }
        }
        for (int i = 0; i < (int) input.Children->size(); i++) {
            if ((*input.Children)[i].name == "Import") {
                combined.Children->push_back((*input.Children)[i]);
            }
        }
        for (const XmlNode &module: prelude) {
            for (int i = 0; i < (int) module.Children->size(); i++) {
                if ((*module.Children)[i].name != "Import") {
                    combined.Children->push_back((*module.Children)[i]);
                }
            }
        }
        for (int i = 0; i < (int) input.Children->size(); i++) {
            if ((*input.Children)[i].name != "Import") {
                combined.Children->push_back((*input.Children)[i]);
            }
        }
        return combined;
    }
}

int main(int argc, char **argv) {
    std::string fixturesDir = argc > 1 ? argv[1] : ".";
    std::string preludeDir = argc > 2 ? argv[2] : SIMSE_SEMA_PRELUDE;

    std::vector<std::string> files = simseFiles(fixturesDir);

    std::vector<XmlNode> prelude;
    if (!preludeDir.empty()) {
        for (const std::string &path: simseFiles(preludeDir)) {
            XmlNode module;
            std::string name = std::filesystem::path(path).filename().string();
            if (parseFile(path, name, module)) {
                prelude.push_back(module);
            }
        }
    }

    for (const std::string &file: files) {
        const std::string name = std::filesystem::path(file).filename().string();
        printf("=== %s ===\n", name.c_str());

        XmlNode input;
        if (!parseFile(file, name, input)) {
            continue;
        }
        XmlNode combined = prelude.empty() ? input : combine(prelude, input);
        List<Str> diags = analyze(combined, name);
        for (int i = 0; i < (int) diags.size(); i++) {
            printf("%s\n", diags[i].c_str());
        }
    }
    return 0;
}
