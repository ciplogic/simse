//
// Golden test runner for the scanner. For every fixture in the fixtures
// directory it scans the file with the real scanner and compares a deterministic
// token dump against a checked-in golden. See tests/README.md.
//

#include "test_support.h"
#include "../cppsrc/common/common.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <filesystem>

#ifndef SIMSE_FIXTURES_DIR
#define SIMSE_FIXTURES_DIR "tests/fixtures"
#endif

using namespace common;
using namespace lex;
using namespace tests;

namespace {
    Str baseName(const Str &path) {
        return std::filesystem::path(path).filename().string();
    }

    Str readFileText(const Str &path) {
        return common::readFile(path);
    }

    bool writeFileText(const Str &path, const Str &text) {
        std::filesystem::path p(path);
        std::filesystem::create_directories(p.parent_path());
        FILE *file = fopen(path.c_str(), "wb");
        if (file == nullptr) {
            return false;
        }
        fwrite(text.data(), 1, text.length(), file);
        fclose(file);
        return true;
    }

    List<Str> splitLines(const Str &text) {
        List<Str> lines;
        Str current;
        for (char ch: text) {
            if (ch == '\n') {
                lines.push_back(current);
                current.clear();
            } else {
                current += ch;
            }
        }
        if (!current.empty()) {
            lines.push_back(current);
        }
        return lines;
    }

    // Prints the differing lines of two dumps, capped so a large mismatch does
    // not flood the terminal.
    void printDiff(const Str &expected, const Str &actual) {
        List<Str> expectedLines = splitLines(expected);
        List<Str> actualLines = splitLines(actual);
        int maxLines = (int) std::max(expectedLines.size(), actualLines.size());
        int shown = 0;
        for (int i = 0; i < maxLines; i++) {
            Str expectedLine = i < (int) expectedLines.size() ? expectedLines[i] : Str("<missing>");
            Str actualLine = i < (int) actualLines.size() ? actualLines[i] : Str("<missing>");
            if (expectedLine == actualLine) {
                continue;
            }
            printf("    line %d:\n      expected: %s\n      actual:   %s\n",
                   i + 1, expectedLine.c_str(), actualLine.c_str());
            shown++;
            if (shown >= 20) {
                printf("    ...\n");
                break;
            }
        }
    }
}

int main(int argc, char **argv) {
    Str fixturesDir = SIMSE_FIXTURES_DIR;
    bool update = false;
    for (int i = 1; i < argc; i++) {
        Str arg = argv[i];
        if (arg == "--update") {
            update = true;
        } else {
            fixturesDir = arg;
        }
    }
    if (const char *env = std::getenv("SIMSE_UPDATE_GOLDENS")) {
        if (Str(env) == "1" || Str(env) == "true") {
            update = true;
        }
    }

    Str goldenDir = (std::filesystem::path(fixturesDir).parent_path() / "golden").string();

    List<TokenMatcher> rules = getTokenRules();
    Scanner scanner(&rules);

    List<Str> fixtures = filesInDir(fixturesDir, ".simse");

    int passed = 0;
    int failed = 0;
    for (const Str &fixture: fixtures) {
        Str name = baseName(fixture);
        Str goldenPath = (std::filesystem::path(goldenDir) / (name + ".tokens.expected")).string();

        ScanResult scan = scanFile(&scanner, fixture);
        Str dumpText = dump(scan);

        Str wrapperIssue = checkWrappers(&scanner, fixture, scan);
        if (!wrapperIssue.empty()) {
            failed++;
            printf("FAIL %s: %s\n", name.c_str(), wrapperIssue.c_str());
            continue;
        }

        if (update) {
            if (!writeFileText(goldenPath, dumpText)) {
                failed++;
                printf("FAIL %s: cannot write golden %s\n", name.c_str(), goldenPath.c_str());
                continue;
            }
            passed++;
            printf("UPDATED %s\n", name.c_str());
            continue;
        }

        if (!std::filesystem::exists(goldenPath)) {
            failed++;
            printf("FAIL %s: missing golden %s (run with --update)\n",
                   name.c_str(), goldenPath.c_str());
            continue;
        }

        Str expected = readFileText(goldenPath);
        if (expected == dumpText) {
            passed++;
            printf("PASS %s\n", name.c_str());
        } else {
            failed++;
            printf("FAIL %s\n", name.c_str());
            printDiff(expected, dumpText);
        }
    }

    printf("%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
