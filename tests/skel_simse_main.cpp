//
// Differential Simse driver for the skeleton parser. This translation unit is
// compiled together with the C++ emitted by
// `simse_transpile cppsrc/skelparser/SkeletonParser.simse` (included directly,
// because the generated file has no header). It tokenizes each `*.simse` fixture
// with the generated scanner and runs the generated global `ns3_parseSkeleton`,
// rendering the tree with the shared tests/skel_dump.h format so the build can
// diff it against tests/skel_ref_main.cpp.
//
// Usage: skel_simse <fixtures-dir>
//

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <system_error>
#include <vector>

#include "SkeletonParser.simse.cpp"

// The generated translation unit qualifies every package's declarations with
// `ns<index>_`, numbered in sorted package order (impl_specs/rtl-abi.md):
// `common` is 1, `lex` is 2, `skelparser` is 3, so the scanner surface below is
// `ns2_*` and the skeleton parser's is `ns3_*`.
#include "skel_dump.h"

namespace {
    skeldump::Node toNode(const ns3_SkeletonNode &node) {
        skeldump::Node out;
        out.typeOrdinal = (int) node.type;
        out.line = node.token.pos.line;
        out.column = node.token.pos.column;
        out.text = simse_toStdString(node.token.text);
        if (node.children) {
            for (const ns3_SkeletonNode &child: *node.children) {
                out.children.push_back(toNode(child));
            }
        }
        return out;
    }

    bool readAllBytes(const std::string &path, std::string &out) {
        std::ifstream in(path, std::ios::binary);
        if (!in) return false;
        out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
        return true;
    }
}

int main(int argc, char **argv) {
    // The generated component's static storage (the scanner's tables, specs/statics.md)
    // is filled by the pass the emitted file defines; a host that links a component
    // without a `main` of its own has to run it first.
    simse_initStatics();
    std::string fixturesDir = argc > 1 ? argv[1] : ".";

    std::vector<std::string> files;
    std::error_code ec;
    for (const auto &entry: std::filesystem::directory_iterator(std::filesystem::path(simse_toStdString(fixturesDir)), ec)) {
        if (entry.is_regular_file() && entry.path().extension() == ".simse") {
            files.push_back(entry.path().string());
        }
    }
    std::sort(files.begin(), files.end());

    for (const std::string &file: files) {
        printf("=== %s ===\n", std::filesystem::path(simse_toStdString(file)).filename().string().c_str());

        std::string content;
        if (!readAllBytes(file, content)) {
            printf("%s", skeldump::errorLine("cannot read file").c_str());
            continue;
        }

        ns2_Scanner scanner(ns2_getTokenRules(), 0, 1, 1, Str());
        ns2_setSource(scanner, content);

        List<ns2_Token> tokens;
        std::string error;
        bool ok = true;
        while (true) {
            Res<ns2_Token> result = ns2_nextToken(scanner);
            if (!result.isOk()) {
                ok = false;
                error = simse_toStdString(result.Error);
                break;
            }
            if (result.Value.kind == ns2_TokenKind::Eof) {
                break;
            }
            tokens.push_back(result.Value);
        }
        if (!ok) {
            printf("%s", skeldump::errorLine(error).c_str());
            continue;
        }

        Res<ns3_SkeletonNode> parsed = ns3_parseSkeleton(&tokens);
        if (!parsed.isOk()) {
            printf("%s", skeldump::errorLine(parsed.Error).c_str());
            continue;
        }
        printf("%s", skeldump::dump(toNode(parsed.Value)).c_str());
    }
    return 0;
}
