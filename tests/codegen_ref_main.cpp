//
// Differential reference driver for the C++ emitter. Scans every `*.kt`
// fixture, parses it, and emits C++ with the hand-written `codegen::emitProgram`
// (the prelude is combined exactly as the golden harness does). The transpiled
// emitter (tests/codegen_simse_main.cpp) prints the same, and the build diffs
// them.
//
// The driver also checks each fixture's output against the checked-in
// `<fixture>.cpp.expected` golden, so `codegen_diff` proves both
// `reference == golden` and `simse == reference`.
//
// Usage: codegen_ref <fixtures-dir>
//

#include "test_support.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>

using namespace common;
using namespace lex;

int main(int argc, char **argv) {
    Str fixturesDir = argc > 1 ? argv[1] : ".";
    Str goldenDir = (common::toPath(fixturesDir).parent_path() / "golden").string();

    List<Str> files = filesInDir(fixturesDir, ".kt");
    std::sort(files.begin(), files.end());

    List<TokenMatcher> *rules = getTokenRules();
    Scanner scanner(rules);
    int goldenFailures = 0;

    for (const Str &file: files) {
        Str name = common::toPath(file).filename().string();
        printf("=== %s ===\n", name.c_str());

        tests::ScanResult scan = tests::scanFile(&scanner, file);
        tests::AstSemaResult result = tests::runAstSema(scan, name);
        printf("%s", result.cpp.c_str());

        Str goldenPath = (common::toPath(goldenDir) / common::toPath(name + ".cpp.expected")).string();
        if (std::filesystem::exists(common::toPath(goldenPath))) {
            Str expected = common::readFile(goldenPath);
            if (expected != result.cpp) {
                goldenFailures++;
                fprintf(stderr, "codegen_ref: %s does not match golden %s\n",
                        name.c_str(), goldenPath.c_str());
            }
        }
    }

    if (goldenFailures > 0) {
        fprintf(stderr, "codegen_ref: %d fixture(s) diverged from the .cpp goldens\n",
                goldenFailures);
        return 1;
    }
    return 0;
}
