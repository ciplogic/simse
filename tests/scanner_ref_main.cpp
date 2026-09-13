//
// Differential reference driver. Tokenizes every `*.simse` fixture under the
// given directory with the hand-written C++ scanner (lex::Scanner) and prints
// the canonical dump defined in tests/test_support.cpp:
//
//     <KindName>\t<line>:<column>\t<escapedText>
//
// with the Eof token omitted and a final `Error\t<escaped message>` line when
// the scanner rejects a character. tests/scanner_simse_main.cpp prints the same
// format from the transpiled scanner; the two outputs are diffed by the build.
//
// Usage: scanner_ref <fixtures-dir>
//

#include "test_support.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>

using namespace common;
using namespace lex;
using namespace tests;

int main(int argc, char **argv) {
    Str fixturesDir = argc > 1 ? argv[1] : ".";
    List<Str> files = filesInDir(fixturesDir, ".simse");
    std::sort(files.begin(), files.end());

    List<TokenMatcher> *rules = getTokenRules();
    Scanner scanner(rules);

    for (const Str &file: files) {
        printf("=== %s ===\n", common::toPath(file).filename().string().c_str());
        ScanResult scan = scanFile(&scanner, file);
        printf("%s", dump(scan).c_str());
    }
    return 0;
}
