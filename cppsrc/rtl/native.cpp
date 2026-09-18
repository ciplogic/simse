// native.cpp
//
// The hand-written natives every generated program may call (impl_specs/native-interop.md):
// the FFI boundary the compiler itself uses to read its inputs, list a directory, write its
// output, and take a clock reading. This is the *only* hand-written C++ translation unit in
// the repository besides `cppsrc/simse_bootstrap.cpp` - everything above it is Simse, and
// everything beside it (cppsrc/rtl/*.hpp) is the runtime those natives and the generated
// code are written against.
//
// A native's Simse declaration is what gives it a signature; the emitter writes a prototype
// for a non-prelude native and a call to this file's symbol. The prelude natives' prototypes
// live in the RTL headers (fs.hpp, filestream.hpp, timeops.hpp), because generated code that
// includes `simse.hpp` has to see them; `simse_native_readFile` is declared in
// cppsrc/common/common.kt (a normal module, not the prelude), so the emitter emits its
// prototype at the top of the amalgamation.
//
// The two filesystem helpers below were `common::readFile` / `common::filesInDir` in the
// hand-written compiler ring. They are file-local now: nothing but this file needs them, and
// their Simse-side twins are `readFile` (cppsrc/common/common.kt) and `listFiles`
// (cppsrc/rtl/fs.kt), whose semantics they have to match.

#include "simse.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <system_error>

namespace {
    // The whole file as bytes; empty when it cannot be read.
    Str readWholeFile(const Str& filePath) {
        Str result;
        FILE* file = fopen(filePath.c_str(), "rb");
        if (file == nullptr) return result;
        fseek(file, 0, SEEK_END);
        const int fileSize = ftell(file);
        fseek(file, 0, SEEK_SET);
        result.resize(fileSize);
        fread(result.data(), 1, fileSize, file);
        fclose(file);
        return result;
    }

    // Every `ext`-suffixed file under `dir`, recursively, sorted; empty when `dir` is not a
    // directory. The one place the "generic separators" rule is applied, so a scanned path
    // matches the same path given explicitly (e.g. `--root cppsrc` vs an explicit input
    // file): `simse_listFiles` and the compiler's own dedup key both read these strings.
    List<Str> filesInDir(const Str& dirPath, const Str& ext) {
        List<Str> matchingFiles;

        // The standard filesystem API works in std::string; the language works in Str
        // (`simse_toStdString` is a no-op copy when Str is std::string).
        const std::string dir = simse_toStdString(dirPath);
        const std::string wantedExt = simse_toStdString(ext);

        if (!std::filesystem::exists(dir) || !std::filesystem::is_directory(dir)) {
            return matchingFiles;
        }
        for (const auto& entry: std::filesystem::recursive_directory_iterator(dir)) {
            if (entry.is_regular_file() && entry.path().extension() == wantedExt) {
                matchingFiles.push_back(simse_fromStdString(entry.path().generic_string()));
            }
        }
        std::sort(matchingFiles.begin(), matchingFiles.end());
        return matchingFiles;
    }
}

// ---- filesystem / IO -------------------------------------------------------

Str simse_native_readFile(const Str& path) {
    return readWholeFile(path);
}

// Declared in the prelude (cppsrc/rtl/fs.kt), prototype in cppsrc/rtl/fs.hpp.
List<Str> simse_listFiles(const Str& dir, const Str& ext) {
    return filesInDir(dir, ext);
}

// The non-recursive form, for `import a.b.c` directory resolution.
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
// The stream's own operations (`readLine`, `readLineInto`, `fileSize`, `close`) are methods
// of `FileStream` in cppsrc/rtl/filestream.hpp: the emitter calls a handle's methods as
// members. Only the constructor-like `open` lives here, as a free function.

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

Int64 simse_nowMicros() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return (Int64) std::chrono::duration_cast<std::chrono::microseconds>(now).count();
}
