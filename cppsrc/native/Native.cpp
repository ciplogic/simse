#include "Native.h"

#include "../common/common.h"
#include "../rtl/filestream.hpp"
#include "../rtl/fs.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
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
    if (!std::filesystem::is_directory(simse_toStdString(dir), ec)) {
        return files;
    }
    for (const auto& entry: std::filesystem::directory_iterator(simse_toStdString(dir), ec)) {
        if (entry.is_regular_file() && entry.path().extension() == simse_toStdString(ext)) {
            files.push_back(simse_fromStdString(entry.path().generic_string()));
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
    std::filesystem::path canonical = std::filesystem::weakly_canonical(
            std::filesystem::path(simse_toStdString(path)), ec);
    return ec ? path : simse_fromStdString(canonical.string());
}

Bool simse_pathIsDirectory(const Str& path) {
    std::error_code ec;
    return std::filesystem::is_directory(simse_toStdString(path), ec);
}

Bool simse_pathExists(const Str& path) {
    std::error_code ec;
    return std::filesystem::exists(simse_toStdString(path), ec);
}

void simse_eprintln(const Str& text) {
    fprintf(stderr, "%s\n", text.c_str());
}

// ---- reading a file line by line -------------------------------------------
//
// The stream's own operations (`readLine`, `readLineInto`, `fileSize`, `close`) are
// methods of `FileStream` in cppsrc/rtl/filestream.hpp: the emitter calls a handle's
// methods as members. Only the constructor-like `open` lives here, as a free function.

FileStream* simse_fileStream_open(const Str& path) {
    auto* stream = new FileStream();
    stream->file.open(simse_toStdString(path), std::ios::binary);
    if (!stream->file.is_open()) {
        delete stream;
        return nullptr;
    }
    stream->chunk.resize(256 * 1024);
    std::error_code ec;
    const std::uintmax_t size = std::filesystem::file_size(simse_toStdString(path), ec);
    stream->size = ec ? 0 : (Int64) size;
    return stream;
}

// ---- time ------------------------------------------------------------------

Int64 simse_nowMillis() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return (Int64) std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
}
