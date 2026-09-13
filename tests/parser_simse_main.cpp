//
// Differential Simse driver for the parser. This translation unit is compiled
// together with the C++ emitted by
// `simse_transpile cppsrc/parser/Parser.simse` (included directly, because the
// generated file has no header). It tokenizes each `*.simse` fixture with the
// generated scanner and runs the generated global `ns3_parseModule`, then renders
// the resulting RTL `AstXmlNode` with the same `ast::dumpXmlNode` the reference
// driver uses, so the build can diff the two.
//
// Usage: parser_simse <fixtures-dir>
//

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "Parser.simse.cpp"

// The generated translation unit qualifies every package's declarations with
// `ns<index>_`, numbered in sorted package order (impl_specs/rtl-abi.md):
// `common` is 1, `lex` is 2, `parser` is 3, so the scanner surface below is
// `ns2_*` and the parser entry point is `ns3_parseModule`.
#include "parser_dump.h"
#include "../cppsrc/ast/Ast.h"

namespace {
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
        const std::string name = std::filesystem::path(simse_toStdString(file)).filename().string();
        printf("%s", parserdump::header(name).c_str());

        std::string content;
        if (!readAllBytes(file, content)) {
            printf("%s", parserdump::scanErrorLine("cannot read file").c_str());
            continue;
        }

        ns2_Scanner scanner(ns2_getTokenRules(), 0, 1, 1, Str());
        ns2_setSource(scanner, content);

        List<ns2_Token> tokens = List<ns2_Token>();
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
            simse_list_append(tokens, result.Value);
        }
        if (!ok) {
            printf("%s", parserdump::scanErrorLine(error).c_str());
            continue;
        }

        Res<AstXmlNode> parsed = ns3_parseModule(&tokens, name);
        if (!parsed.isOk()) {
            printf("%s", parserdump::parseErrorLine(parsed.Error).c_str());
            continue;
        }
        printf("%s", ast::dumpXmlNode(parsed.Value).c_str());
    }
    return 0;
}
