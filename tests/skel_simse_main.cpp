//
// Differential Simse driver for the skeleton parser. This translation unit is
// compiled together with the C++ emitted by
// `simse_transpile cppsrc/skelparser/SkeletonParser.simse` (included directly,
// because the generated file has no header). It tokenizes each `*.simse` fixture
// with the generated scanner and runs the generated global `parseSkeleton`,
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
#include "skel_dump.h"

namespace {
    skeldump::Node toNode(const SkeletonNode &node) {
        skeldump::Node out;
        out.typeOrdinal = (int) node.type;
        out.line = node.token.pos.line;
        out.column = node.token.pos.column;
        out.text = node.token.text;
        if (node.children) {
            for (const SkeletonNode &child: *node.children) {
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
    std::string fixturesDir = argc > 1 ? argv[1] : ".";

    std::vector<std::string> files;
    std::error_code ec;
    for (const auto &entry: std::filesystem::directory_iterator(fixturesDir, ec)) {
        if (entry.is_regular_file() && entry.path().extension() == ".simse") {
            files.push_back(entry.path().string());
        }
    }
    std::sort(files.begin(), files.end());

    for (const std::string &file: files) {
        printf("=== %s ===\n", std::filesystem::path(file).filename().string().c_str());

        std::string content;
        if (!readAllBytes(file, content)) {
            printf("%s", skeldump::errorLine("cannot read file").c_str());
            continue;
        }

        Scanner scanner(getTokenRules(), 0, 1, 1, Str());
        setSource(scanner, content);

        std::vector<Token> tokens;
        std::string error;
        bool ok = true;
        while (true) {
            Res<Token> result = nextToken(scanner);
            if (!result.isOk()) {
                ok = false;
                error = result.Error;
                break;
            }
            if (result.Value.kind == TokenKind::Eof) {
                break;
            }
            tokens.push_back(result.Value);
        }
        if (!ok) {
            printf("%s", skeldump::errorLine(error).c_str());
            continue;
        }

        Res<SkeletonNode> parsed = parseSkeleton(&tokens);
        if (!parsed.isOk()) {
            printf("%s", skeldump::errorLine(parsed.Error).c_str());
            continue;
        }
        printf("%s", skeldump::dump(toNode(parsed.Value)).c_str());
    }
    return 0;
}
