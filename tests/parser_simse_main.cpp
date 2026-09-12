//
// Differential Simse driver for the parser. This translation unit is compiled
// together with the C++ emitted by
// `simse_transpile cppsrc/parser/Parser.simse` (included directly, because the
// generated file has no header). It tokenizes each `*.simse` fixture with the
// generated scanner and runs the generated global `parseModule`, then renders
// the resulting RTL `XmlNode` with the same `ast::dumpXmlNode` the reference
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

        Scanner scanner(getTokenRules(), 0, 1, 1, Str());
        setSource(scanner, content);

        List<Token> tokens = List<Token>();
        std::string error;
        bool ok = true;
        while (true) {
            Res<Token> result = nextToken(scanner);
            if (!result.isOk()) {
                ok = false;
                error = simse_toStdString(result.Error);
                break;
            }
            if (result.Value.kind == TokenKind::Eof) {
                break;
            }
            simse_list_append(tokens, result.Value);
        }
        if (!ok) {
            printf("%s", parserdump::scanErrorLine(error).c_str());
            continue;
        }

        Res<XmlNode> parsed = parseModule(&tokens, name);
        if (!parsed.isOk()) {
            printf("%s", parserdump::parseErrorLine(parsed.Error).c_str());
            continue;
        }
        printf("%s", ast::dumpXmlNode(parsed.Value).c_str());
    }
    return 0;
}
