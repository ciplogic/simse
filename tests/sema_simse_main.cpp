//
// Differential Simse driver for the sema pass. This translation unit is compiled
// together with the C++ emitted by `simse_transpile cppsrc/sema/Sema.simse`
// (included directly, because the generated file has no header; it also carries
// the transpiled parser and scanner through its imports). It scans and parses
// each fixture with the generated front end, passes the RTL prelude and the
// fixture to the generated compilation-wide `ns4_analyze` (one input per file, so
// each keeps its declared package), and prints the diagnostics, so the build can
// diff them against tests/sema_ref_main.cpp.
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

// The generated translation unit qualifies every package's declarations with
// `ns<index>_`, numbered in sorted package order (impl_specs/rtl-abi.md):
// `common` is 1, `lex` is 2, `parser` is 3, `sema` is 4, so the scanner surface
// below is `ns2_*`, the parser entry point `ns3_parseModule`, and sema's own
// types and entry point are `ns4_*`.

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
        if (!std::filesystem::is_directory(std::filesystem::path(simse_toStdString(dir)), ec)) return files;
        for (const auto &entry: std::filesystem::recursive_directory_iterator(std::filesystem::path(simse_toStdString(dir)), ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".simse") {
                files.push_back(entry.path().string());
            }
        }
        std::sort(files.begin(), files.end());
        return files;
    }

    // Scans `path` into `out`, dropping the Eof. Returns false on a scan error.
    bool scanTokens(const std::string &path, List<ns2_Token> &out) {
        std::string content;
        if (!readAllBytes(path, content)) return false;
        ns2_Scanner scanner(ns2_getTokenRules(), 0, 1, 1, Str());
        ns2_setSource(scanner, content);
        while (true) {
            Res<ns2_Token> result = ns2_nextToken(scanner);
            if (!result.isOk()) return false;
            if (result.Value.kind == ns2_TokenKind::Eof) return true;
            simse_list_append(out, result.Value);
        }
    }

    bool parseFile(const std::string &path, const std::string &displayName, AstXmlNode &out) {
        List<ns2_Token> tokens = List<ns2_Token>();
        if (!scanTokens(path, tokens)) return false;
        Res<AstXmlNode> parsed = ns3_parseModule(&tokens, displayName);
        if (!parsed.isOk()) return false;
        out = parsed.Value;
        return true;
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

    // One prelude input per file, so each keeps its declared package (`rtl`),
    // exactly as tests::preludeInputs does for the reference driver.
    std::vector<AstXmlNode> prelude;
    std::vector<std::string> preludeNames;
    if (!preludeDir.empty()) {
        for (const std::string &path: simseFiles(preludeDir)) {
            AstXmlNode module;
            std::string name = std::filesystem::path(simse_toStdString(path)).filename().string();
            if (parseFile(path, name, module)) {
                prelude.push_back(module);
                preludeNames.push_back(name);
            }
        }
    }

    for (const std::string &file: files) {
        const std::string name = std::filesystem::path(simse_toStdString(file)).filename().string();
        printf("=== %s ===\n", name.c_str());

        AstXmlNode input;
        if (!parseFile(file, name, input)) {
            continue;
        }
        List<ns4_SemaInput> inputs = List<ns4_SemaInput>();
        for (int i = 0; i < (int) prelude.size(); i++) {
            inputs.push_back(ns4_SemaInput(preludeNames[i], prelude[i]));
        }
        inputs.push_back(ns4_SemaInput(name, input));

        List<Str> diags = ns4_analyze(inputs);
        for (int i = 0; i < (int) diags.size(); i++) {
            printf("%s\n", diags[i].c_str());
        }
    }
    return 0;
}
