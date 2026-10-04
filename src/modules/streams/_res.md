Generated C++
====
The `streams` module's C++ - the declarations are src/modules/streams/fs.kt. `FileStream` is a
*type* whose layout only C++ can express (`std::ifstream`, a recycled `std::string`), so the
struct is hand-written (`filestream.hpp`) and the declaration carries the materialization
marker (`@SmGen("cpp")`, specs/attributes.md): the emitter must not generate one. `filestreamhpp`
places the header for every program that names the module - the marker's contract is that the
header is *already included* whenever the type is named - and `filestream` carries the open and
the method bodies, reached only when a program opens or reads a stream.

!filestreamhpp
====
emit: always
includes:
```cpp
#include "src/modules/streams/filestream.hpp"
```

!filestream
====
emit: reached
forward:
```cpp
// The open body needs the filesystem API and its error codes; the reads' text needs nothing
// beyond the header's own includes (`<fstream>`, `<string>`, `<cstring>`).
#include <filesystem>
#include <system_error>

// The constructor-like open, a free function (the struct has no constructor of its own):
// null when the file cannot be opened.
FileStream* simse_fileStream_open(const Str& path);
```
bodies:
```cpp
// `FileStream`'s open and operations. The struct - its fields and the declarations of the
// methods - is src/modules/streams/filestream.hpp, and the emitter calls them as *members*
// (`stream->readLine()`), which is why this section needs no forward text for them: the class
// already declares each one. What a program carries is this text, and only when it reaches one
// of these declarations - src/modules/streams/fs.kt names the section, and `emit: reached`
// says the section's own text is reach-gated even though the declaration is a program
// module's. The open is a free function (`src/modules/io/_res.md` held it while the type lived
// in the prelude), and its prototype is the `forward` text above.

// Opens `path` in binary mode and sizes the readahead buffer; null when it cannot be opened.
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

// The next line without its line ending, or an empty `Opt` at end of file.
Opt<Str> FileStream::readLine() {
    if (!std::getline(file, line)) {
        return Opt<Str>();
    }
    if (!line.empty() && line.back() == '\r') {
        line.pop_back();
    }
    Opt<Str> result;
    result.setValue(simse_fromStdString(line));
    return result;
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
Opt<Span<Char>> FileStream::readLineView() {
    Int from = 0;
    Int count = 0;
    if (!nextLineSpan(&from, &count)) return Opt<Span<Char>>();
    Char* base = const_cast<Char*>(reinterpret_cast<const Char*>(chunk.data()));
    Opt<Span<Char>> result;
    result.setValue(Span<Char>{base + from, count});
    return result;
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
