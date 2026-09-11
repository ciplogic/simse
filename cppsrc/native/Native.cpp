#include "Native.h"

#include "../common/common.h"
#include "../rtl/fs.hpp"

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <system_error>

Str simse_native_readFile(const Str& path) {
    return common::readFile(path);
}

// ---- filesystem / IO (T23) ------------------------------------------------

List<Str> simse_listFiles(const Str& dir, const Str& ext) {
    return common::filesInDir(dir, ext);
}

List<Str> simse_listFilesDirect(const Str& dir, const Str& ext) {
    List<Str> files;
    std::error_code ec;
    if (!std::filesystem::is_directory(dir, ec)) {
        return files;
    }
    for (const auto& entry: std::filesystem::directory_iterator(dir, ec)) {
        if (entry.is_regular_file() && entry.path().extension() == ext) {
            files.push_back(entry.path().string());
        }
    }
    std::sort(files.begin(), files.end());
    return files;
}

Bool simse_writeFile(const Str& path, const Str& content) {
    FILE* out = fopen(path.c_str(), "wb");
    if (out == nullptr) {
        return false;
    }
    fwrite(content.data(), 1, content.length(), out);
    fclose(out);
    return true;
}

Str simse_pathCanonical(const Str& path) {
    std::error_code ec;
    std::filesystem::path canonical =
        std::filesystem::weakly_canonical(std::filesystem::path(path), ec);
    return ec ? path : canonical.string();
}

Bool simse_pathIsDirectory(const Str& path) {
    std::error_code ec;
    return std::filesystem::is_directory(path, ec);
}

Bool simse_pathExists(const Str& path) {
    std::error_code ec;
    return std::filesystem::exists(path, ec);
}

void simse_eprintln(const Str& text) {
    fprintf(stderr, "%s\n", text.c_str());
}
