Generated C++
====
The `io` module's C++ - the declarations are cppsrc/modules/io/api.kt. These were two sections of
the RTL's `cppsrc/rtl/_res.md` until a module came to own its resources; the tree's own `_res.md`
files are the first the generator lookup reads, so a program that names this module finds them
here (specs/resources.md, impl_specs/generators.md). `fileio` is marked always, because a program
may name one of its symbols itself, and `filestream` is marked reached, so the stream reads land
only when a program reads a line.

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
// cppsrc/rtl/native.cpp - the repository's one hand-written translation unit beside the
// published bootstrap - so building a program meant compiling *and linking* a second file,
// and a build that left it out failed in the linker rather than in the compiler. The bodies
// are this section's now: a program that reaches one of these symbols has the prototype and
// the definition emitted into its own translation unit, which is what makes
// `cppsrc/simse_bootstrap.cpp` buildable on its own.
//
// `emit: always`, for the reason `timeops` is: a *program* may name any of these symbols
// with a declaration of its own - `native("simse_native_readFile") fun readFile(...)`, which
// is `native-interop.md`'s example and `stress/native-read-file`'s - and then no `res`
// declaration reaches this section for it to be found by (`sourcegen/ResGen.kt`). The
// platform's C++ is the runtime's FFI, so like a linked runtime it is there for every
// program; what a program pays for it is about 4 KB of text, the same code the linker used
// to place whether or not the program called it.
//
// The Simse surface is the `io` module (cppsrc/modules/io/api.kt), plus `readFile` in
// cppsrc/common/common.kt (a normal module of the compiler). The prototypes used to live in
// cppsrc/rtl/fs.hpp and cppsrc/rtl/filestream.hpp, which is why those headers are gone - a
// section carries its own declaration half in `forward`.

Str simse_native_readFile(const Str& path);
List<Str> simse_listFiles(const Str& dir, const Str& ext);
List<Str> simse_listFilesDirect(const Str& dir, const Str& ext);
Bool simse_writeFile(const Str& path, const Str& content);
Str simse_pathCanonical(const Str& path);
Bool simse_pathIsDirectory(const Str& path);
Bool simse_pathExists(const Str& path);
void simse_eprintln(const Str& text);
FileStream* simse_fileStream_open(const Str& path);
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
// matches the same path given explicitly (e.g. `--root cppsrc` vs an explicit input
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

// The stream's own operations (`readLine`, `readLineInto`, `readLineView`, `fileSize`,
// `close`) are the `filestream` section below: the emitter calls a handle's methods as
// members, and the struct that declares them is cppsrc/rtl/filestream.hpp. Only the
// constructor-like `open` lives here, as a free function.
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
```


!filestream
====
emit: reached
bodies:
```cpp
// `FileStream`'s operations. The struct - its fields and the declarations of these
// methods - is cppsrc/rtl/filestream.hpp, and the emitter calls them as *members*
// (`stream->readLine()`), which is why this section needs no `forward` text: the class
// already declares each one. What a program carries is this text, and only when it calls
// one of them - `cppsrc/modules/io/api.kt` names the section, and `emit: reached` says the
// section's own text is reach-gated even though the declaration is a program module's.

// The next line without its line ending, or an empty `Opt` at end of file.
Opt<Str> FileStream::readLine() {
    if (!std::getline(file, line)) {
        return Opt<Str>::none();
    }
    if (!line.empty() && line.back() == '\r') {
        line.pop_back();
    }
    return Opt<Str>::some(simse_fromStdString(line));
}

// The next line into the caller's buffer (reused across calls), `false` at end of
// file.
Bool FileStream::readLineInto(Str* buffer) {
    if (buffer == nullptr) return false;
    Int from = 0;
    Int count = 0;
    if (!nextLineSpan(&from, &count)) return false;
    take(buffer, chunk.data() + from, count);
    return true;
}

// The next line as a view into the readahead buffer, or an empty `Opt` at end of
// file. Nothing is copied; the span is valid until the next read on this stream.
// The one cast `simse_spanOfStr` makes is spelled here too, so that a program reading
// views does not also have to carry the `strview` section: `StrView` is a `Span<Char>`
// and this view's bytes are the readahead buffer's, which nothing writes through.
Opt<StrView> FileStream::readLineView() {
    Int from = 0;
    Int count = 0;
    if (!nextLineSpan(&from, &count)) return Opt<StrView>::none();
    Char* base = const_cast<Char*>(reinterpret_cast<const Char*>(chunk.data()));
    return Opt<StrView>::some(StrView(base + from, count));
}

// The file's size in bytes (0 when it is unknown).
Int64 FileStream::fileSize() const {
    return size;
}

// Releases the handle: the stream was allocated by `simse_fileStream_open`.
void FileStream::close() {
    file.close();
    delete this;
}

// Advances the readahead buffer to the next line's bytes: `*from`/`*count` are the
// indexes of the line's first byte and its length (the line ending is not part of
// it), already stripped of a trailing `\r`. `false` at end of file - and then the
// chunk is left empty, so a view handed out before it stays the last line.
Bool FileStream::nextLineSpan(Int* from, Int* count) {
    Int at = chunkAt;
    while (true) {
        const Int limit = chunkLen;
        if (at < limit) {
            const char* base = chunk.data();
            const void* found = std::memchr(base + at, '\n', (std::size_t) (limit - at));
            if (found != nullptr) {
                const Int end = (Int) ((const char*) found - base);
                Int len = end - at;
                if (len > 0 && base[end - 1] == '\r') len--;
                *from = at;
                *count = len;
                chunkAt = end + 1;
                return true;
            }
        }
        // No newline in what is left of the chunk: keep the tail, refill, retry.
        // When the tail alone fills the buffer, the line does not fit in it yet,
        // so the buffer grows (once per new longest line).
        Int tail = limit - at;
        if (tail > 0 && at > 0) {
            std::memmove(chunk.data(), chunk.data() + at, (std::size_t) tail);
        }
        if (tail == chunk.size()) {
            chunk.resize(tail * 2);
        }
        file.read(chunk.data() + tail, (std::streamsize) (chunk.size() - tail));
        const std::streamsize got = file.gcount();
        if (got <= 0) {
            // End of file: whatever the tail holds is the last line (the file did
            // not end with a newline), and the next call reports the end.
            chunkLen = 0;
            chunkAt = 0;
            if (tail == 0) return false;
            Int len = tail;
            if (len > 0 && chunk[len - 1] == '\r') len--;
            *from = 0;
            *count = len;
            return true;
        }
        chunkLen = tail + (Int) got;
        chunkAt = 0;
        at = 0;
    }
}

void FileStream::take(Str* buffer, const char* text, Int count) {
    buffer->resize(count);
    if (count > 0) std::memcpy(buffer->data(), text, (std::size_t) count);
}
```

