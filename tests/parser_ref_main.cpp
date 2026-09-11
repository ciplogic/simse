//
// Differential reference driver for the parser. Scans every `*.simse` fixture
// under the given directory with the hand-written lex::Scanner, runs the
// hand-written `parser::parseModule`, then renders `ast::toXmlNode` with
// `ast::dumpXmlNode`. The transpiled parser (tests/parser_simse_main.cpp) prints
// the same, and the build diffs them.
//
// It also checks the reference dump against the `<fixture>.astxml.expected`
// golden for every fixture that has one, so `parser_diff` proves both
// `reference == golden` and `simse == reference`.
//
// Usage: parser_ref <fixtures-dir>
//

#include "test_support.h"
#include "parser_dump.h"
#include "../cppsrc/ast/Ast.h"
#include "../cppsrc/parser/Parser.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>

using namespace common;
using namespace lex;

int main(int argc, char **argv) {
    Str fixturesDir = argc > 1 ? argv[1] : ".";
    Str goldenDir = (std::filesystem::path(fixturesDir).parent_path() / "golden").string();

    List<Str> files = filesInDir(fixturesDir, ".simse");
    std::sort(files.begin(), files.end());

    List<TokenMatcher> rules = getTokenRules();
    Scanner scanner(&rules);
    int goldenFailures = 0;

    for (const Str &file: files) {
        Str name = std::filesystem::path(file).filename().string();
        printf("%s", parserdump::header(name).c_str());

        tests::ScanResult scan = tests::scanFile(&scanner, file);
        if (!scan.ok) {
            printf("%s", parserdump::scanErrorLine(scan.errorMessage).c_str());
            continue;
        }

        List<Token> tokens = scan.tokens;
        Res<ast::Module> parsed = parser::parseModule(tokens, name);
        if (!parsed.isOk()) {
            printf("%s", parserdump::parseErrorLine(parsed.Error).c_str());
            continue;
        }

        Str dump = ast::dumpXmlNode(ast::toXmlNode(parsed.Value));
        printf("%s", dump.c_str());

        Str goldenPath = (std::filesystem::path(goldenDir) / (name + ".astxml.expected")).string();
        if (std::filesystem::exists(goldenPath)) {
            Str expected = common::readFile(goldenPath);
            if (expected != dump) {
                goldenFailures++;
                fprintf(stderr, "parser_ref: %s does not match golden %s\n",
                        name.c_str(), goldenPath.c_str());
            }
        }
    }

    if (goldenFailures > 0) {
        fprintf(stderr, "parser_ref: %d fixture(s) diverged from the .astxml goldens\n",
                goldenFailures);
        return 1;
    }
    return 0;
}
