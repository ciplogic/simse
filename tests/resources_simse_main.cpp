//
// Differential Simse driver for the resources parser. This translation unit is
// compiled together with the C++ emitted by
// `simse_transpile cppsrc/resources/Resources.kt` (included directly, because the
// generated file has no header). It parses each `_res.md` fixture with the generated
// `ns2_resParseText` and prints the same C++-quoted `key<TAB>value` dump as
// tests/resources_ref_main.cpp, so the build can diff the two parsers.
//
// Usage: resources_simse <fixtures-dir>
//

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "Resources.kt.cpp"

// The generated translation unit qualifies every package's declarations with
// `ns<index>_`, numbered in sorted package order (impl_specs/rtl-abi.md): `common` is
// 1 and `resources` 2, so the parser below is `ns2_*`.

namespace {
    bool readAllBytes(const std::string &path, std::string &out) {
        std::ifstream in(path, std::ios::binary);
        if (!in) return false;
        out.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
        return true;
    }

    std::vector<std::string> markdownFiles(const std::string &dir) {
        std::vector<std::string> files;
        std::error_code ec;
        if (!std::filesystem::is_directory(std::filesystem::path(simse_toStdString(dir)), ec)) return files;
        for (const auto &entry: std::filesystem::recursive_directory_iterator(std::filesystem::path(simse_toStdString(dir)), ec)) {
            if (entry.is_regular_file() && entry.path().extension() == ".md") {
                files.push_back(entry.path().generic_string());
            }
        }
        std::sort(files.begin(), files.end());
        return files;
    }
}

int main(int argc, char **argv) {
    std::string fixturesDir = argc > 1 ? argv[1] : ".";

    std::vector<std::string> files = markdownFiles(fixturesDir);

    List<ns2_ResourceEntry> all = List<ns2_ResourceEntry>();
    for (const std::string &file: files) {
        const std::string name = std::filesystem::path(simse_toStdString(file)).filename().string();
        printf("=== %s ===\n", name.c_str());

        std::string content;
        if (!readAllBytes(file, content)) {
            continue;
        }
        const List<ns2_ResourceEntry> entries = ns2_resParseText(Str(content));
        for (int i = 0; i < entries.size(); i++) {
            printf("%s\t%s\n", ns2_resQuoteLiteral(entries[i].key).c_str(),
                   ns2_resQuoteLiteral(entries[i].value).c_str());
            all.push_back(entries[i]);
        }
    }

    printf("=== joined ===\n");
    const List<ns2_ResourceEntry> joined = ns2_resDedup(all);
    for (int i = 0; i < joined.size(); i++) {
        printf("%s\t%s\n", ns2_resQuoteLiteral(joined[i].key).c_str(),
               ns2_resQuoteLiteral(joined[i].value).c_str());
    }
    return 0;
}
