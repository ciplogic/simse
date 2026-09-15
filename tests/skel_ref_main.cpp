//
// Differential reference driver for the skeleton parser. Scans every `*.kt`
// fixture under the given directory with the hand-written lex::Scanner and runs
// the hand-written `parseSkeleton` (cppsrc/skelparser/SkeletonParser.h). The
// tree is rendered with the shared tests/skel_dump.h format; the transpiled
// parser (tests/skel_simse_main.cpp) prints the same, and the build diffs them.
//
// Usage: skel_ref <fixtures-dir>
//

#include "test_support.h"
#include "skel_dump.h"
#include "../cppsrc/skelparser/SkeletonParser.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>

using namespace common;
using namespace lex;

namespace {
    skeldump::Node toNode(const SkeletonNode &node) {
        skeldump::Node out;
        out.typeOrdinal = (int) node._type;
        out.line = node._token.pos.line;
        out.column = node._token.pos.column;
        out.text = simse_toStdString(node._token.text);
        if (node._children) {
            for (const SkeletonNode &child: *node._children) {
                out.children.push_back(toNode(child));
            }
        }
        return out;
    }
}

int main(int argc, char **argv) {
    Str fixturesDir = argc > 1 ? argv[1] : ".";
    List<Str> files = filesInDir(fixturesDir, ".kt");
    std::sort(files.begin(), files.end());

    List<TokenMatcher> *rules = getTokenRules();
    Scanner scanner(rules);

    for (const Str &file: files) {
        printf("=== %s ===\n", common::toPath(file).filename().string().c_str());

        tests::ScanResult scan = tests::scanFile(&scanner, file);
        if (!scan.ok) {
            printf("%s", skeldump::errorLine(scan.errorMessage).c_str());
            continue;
        }

        List<Token> tokens = scan.tokens;
        Res<SkeletonNode> parsed = parseSkeleton(&tokens);
        if (!parsed.isOk()) {
            printf("%s", skeldump::errorLine(parsed.Error).c_str());
            continue;
        }
        printf("%s", skeldump::dump(toNode(parsed.Value)).c_str());
    }
    return 0;
}
