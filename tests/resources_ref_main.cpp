//
// Differential reference driver for the resources parser. Parses every `_res.md`
// fixture with the hand-written `resources::parseText` (cppsrc/resources/Resources.cpp)
// and prints one C++-quoted `key<TAB>value` line per entry, then the joined list the
// compilation would keep (`resources::dedup` of the files in order). The transpiled
// parser (tests/resources_simse_main.cpp) prints the same, and the build diffs them.
//
// Usage: resources_ref <fixtures-dir>
//

#include "../cppsrc/resources/Resources.h"

#include <algorithm>
#include <cstdio>

int main(int argc, char **argv) {
    Str fixturesDir = argc > 1 ? argv[1] : ".";
    List<Str> files = common::filesInDir(fixturesDir, ".md");
    std::sort(files.begin(), files.end());

    List<resources::ResourceEntry> all;
    for (const Str &file: files) {
        printf("=== %s ===\n", common::toPath(file).filename().string().c_str());
        const List<resources::ResourceEntry> entries =
                resources::parseText(common::readFile(file));
        for (const resources::ResourceEntry &entry: entries) {
            printf("%s\t%s\n", resources::quoteLiteral(entry.key).c_str(),
                   resources::quoteLiteral(entry.value).c_str());
            all.push_back(entry);
        }
    }

    printf("=== joined ===\n");
    const List<resources::ResourceEntry> joined = resources::dedup(all);
    for (const resources::ResourceEntry &entry: joined) {
        printf("%s\t%s\n", resources::quoteLiteral(entry.key).c_str(),
               resources::quoteLiteral(entry.value).c_str());
    }
    return 0;
}
