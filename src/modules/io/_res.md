Generated C++
====
The `io` module's C++ - the declarations are src/modules/io/api.kt. These were sections of
the RTL's `src/rtl/_res.md` until a module came to own its resources; the tree's own `_res.md`
files are the first the generator lookup reads, so a program that names this module finds them
here (specs/resources.md, impl_specs/generators.md). `fileio` is marked always, because a program
may name one of its symbols itself. (`filestream` moved on with the type: `FileStream`, the open
and the reads are the `streams` module's now, src/modules/streams/_res.md.)

!fileio
====
emit: always
forward:
```cpp
#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <system_error>

// The platform's filesystem/IO operations (impl_specs/native-interop.md). They were
// src/rtl/native.cpp - the repository's one hand-written translation unit beside the
// published bootstrap - so building a program meant compiling *and linking* a second file,
// and a build that left it out failed in the linker rather than in the compiler. The bodies
// are this section's now: a program that reaches one of these symbols has the prototype and
// the definition emitted into its own translation unit, which is what makes
// `src/simse_bootstrap.cpp` buildable on its own.
//
// `emit: always`, for the reason `timeops` is: a *program* may name any of these symbols
// with a declaration of its own - `native("simse_native_readFile") fun readFile(...)`, which
// is `native-interop.md`'s example and `stress/native-read-file`'s - and then no `res`
// declaration reaches this section for it to be found by (`sourcegen/ResGen.kt`). The
// platform's C++ is the runtime's FFI, so like a linked runtime it is there for every
// program; what a program pays for it is about 4 KB of text, the same code the linker used
// to place whether or not the program called it.
//
// The Simse surface is the `io` module (src/modules/io/api.kt), plus `readFile` in
// src/common/common.kt (a normal module of the compiler). The prototypes used to live in
// the deleted src/rtl/fs.hpp and in `filestream.hpp` (which itself moved to the `streams`
// module), which is why a section carries its own declaration half in `forward`.

Str simse_native_readFile(const Str& path);
List<Str> simse_listFiles(const Str& dir, const Str& ext);
List<Str> simse_listFilesDirect(const Str& dir, const Str& ext);
Bool simse_writeFile(const Str& path, const Str& content);
Str simse_pathCanonical(const Str& path);
Bool simse_pathIsDirectory(const Str& path);
Bool simse_pathExists(const Str& path);
void simse_eprintln(const Str& text);
```
bodies:
```cpp
// The whole file as bytes; empty when it cannot be read.
Str simse_native_readFile(const Str& path) {
    Str result;
    FILE* file = fopen(path.c_str(), "rb");
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
// matches the same path given explicitly (e.g. `--root src` vs an explicit input
// file): this and the compiler's own dedup key both read these strings.
List<Str> simse_listFiles(const Str& dir, const Str& ext) {
    List<Str> matchingFiles;

    // The standard filesystem API works in std::string; the language works in Str
    // (`simse_toStdString` is a no-op copy when Str is std::string).
    const std::string dirText = simse_toStdString(dir);
    const std::string wantedExt = simse_toStdString(ext);

    if (!std::filesystem::exists(dirText) || !std::filesystem::is_directory(dirText)) {
        return matchingFiles;
    }
    for (const auto& entry: std::filesystem::recursive_directory_iterator(dirText)) {
        if (entry.is_regular_file() && entry.path().extension() == wantedExt) {
            matchingFiles.push_back(simse_fromStdString(entry.path().generic_string()));
        }
    }
    std::sort(matchingFiles.begin(), matchingFiles.end());
    return matchingFiles;
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
```
