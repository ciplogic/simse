//
// Differential Simse driver. This translation unit is compiled together with
// the C++ emitted by `simse_transpile cppsrc/lex/Scanner.simse` (included
// directly, because the generated file has no header) plus this hand-written
// driver. It prints the SAME canonical dump as tests/scanner_ref_main.cpp so the
// build can diff the two implementations:
//
//     <KindName>\t<line>:<column>\t<escapedText>
//
// Eof is omitted; a rejected character yields a final `Error\t<escaped message>`
// line. Kind names are indexed by enum ordinal, which is identical between the
// hand-written and generated TokenKind.
//
// Usage: scanner_simse <fixtures-dir>
//

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <system_error>
#include <vector>

#include "Scanner.simse.cpp"

namespace {
    std::string kindName(TokenKind kind) {
        static const char *names[] = {
            "None", "Space", "Comment", "EndOfLine", "Identifier", "ReservedWord",
            "Number", "String", "Character", "Operator", "Eof",
        };
        int index = (int) kind;
        if (index < 0 || index > 10) return "Unknown";
        return names[index];
    }

    std::string escapeText(const std::string &text) {
        std::string escaped;
        for (char ch: text) {
            switch (ch) {
                case '\\': escaped += "\\\\"; break;
                case '\n': escaped += "\\n"; break;
                case '\r': escaped += "\\r"; break;
                case '\t': escaped += "\\t"; break;
                default: escaped += ch; break;
            }
        }
        return escaped;
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
            printf("Error\tcannot read file\n");
            continue;
        }

        Scanner scanner(getTokenRules(), 0, 1, 1, Str());
        setSource(scanner, content);

        while (true) {
            Res<Token> result = nextToken(scanner);
            if (!result.isOk()) {
                printf("Error\t%s\n", escapeText(result.Error).c_str());
                break;
            }
            if (result.Value.kind == TokenKind::Eof) {
                break;
            }
            printf("%s\t%d:%d\t%s\n",
                   kindName(result.Value.kind).c_str(),
                   result.Value.pos.line,
                   result.Value.pos.column,
                   escapeText(result.Value.text).c_str());
        }
    }
    return 0;
}
