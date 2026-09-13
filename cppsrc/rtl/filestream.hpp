#pragma once

#include <cstring>
#include <fstream>
#include <string>

#include "containers.hpp"
#include "optional.hpp"
#include "types.hpp"

// Reading a file line by line. The Simse surface is the prelude file
// cppsrc/rtl/fs.simse; `simse_fileStream_open` is defined in
// cppsrc/native/Native.cpp (linked as simse_native), and the operations below are
// the struct's own methods because the emitter calls a handle's methods as members
// (`stream.readLine()` on a `*FileStream` becomes `(*stream).readLine()`).
//
// The handle is a raw pointer: `openFileStream` creates it (null when the file
// cannot be opened), `close` releases it - there is no destructor to run for a handle
// in a language without exceptions.
//
// Two read paths, same lines:
//
//   readLine()              the convenient one. `std::getline` fills the stream's own
//                           recycled `std::string`, and the caller gets a fresh
//                           `Opt<Str>` - one `Str` per line, which allocates once a
//                           line is longer than `Str`'s inline capacity.
//
//   readLineInto(*buffer)   the fast one. Lines come out of a 256 KiB readahead
//                           buffer (`memchr` finds the newline) and are copied into the
//                           *caller's* `Str`, whose heap block is reused across
//                           calls: after the longest line seen so far there is no
//                           allocation at all, and the per-line cost is one `memcpy`.
//
// Both strip a trailing `\r` (files written on Windows) and treat a final line
// without a newline as a line. Neither decodes anything: the bytes come back as they
// are.
struct FileStream {
    std::ifstream file;      // opened in binary mode; text translation is not wanted
    std::string line;        // recycled by `readLine`
    std::string chunk;       // the readahead buffer for `readLineInto`
    Int chunkLen = 0;        // bytes of `chunk` that hold data
    Int chunkAt = 0;         // next unread byte of `chunk`
    Int64 size = 0;          // the file's size in bytes, for throughput reporting

    // The next line without its line ending, or an empty `Opt` at end of file.
    Opt<Str> readLine() {
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
    Bool readLineInto(Str* buffer) {
        if (buffer == nullptr) return false;
        Int at = chunkAt;
        while (true) {
            if (at < chunkLen) {
                const char* base = chunk.data();
                const void* found = std::memchr(base + at, '\n', (std::size_t) (chunkLen - at));
                if (found != nullptr) {
                    const Int end = (Int) ((const char*) found - base);
                    Int count = end - at;
                    if (count > 0 && base[end - 1] == '\r') count--;
                    take(buffer, base + at, count);
                    chunkAt = end + 1;
                    return true;
                }
            }
            // No newline in what is left of the chunk: keep the tail, refill, retry.
            // When the tail alone fills the buffer, the line does not fit in it yet,
            // so the buffer grows (once per new longest line).
            Int tail = chunkLen - at;
            if (tail > 0 && at > 0) {
                std::memmove(chunk.data(), chunk.data() + at, (std::size_t) tail);
            }
            if (tail == (Int) chunk.size()) {
                chunk.resize(chunk.size() * 2);
            }
            file.read(chunk.data() + tail, (std::streamsize) (chunk.size() - (std::size_t) tail));
            const std::streamsize got = file.gcount();
            if (got <= 0) {
                // End of file: whatever the tail holds is the last line (the file did
                // not end with a newline), and the next call reports the end.
                chunkLen = 0;
                chunkAt = 0;
                if (tail == 0) return false;
                Int count = tail;
                if (count > 0 && chunk[(std::size_t) (count - 1)] == '\r') count--;
                take(buffer, chunk.data(), count);
                return true;
            }
            chunkLen = tail + (Int) got;
            chunkAt = 0;
            at = 0;
        }
    }

    // The file's size in bytes (0 when it is unknown).
    Int64 fileSize() const { return size; }

    // Releases the handle; the natives cast it back to the stream and delete it.
    void close() {
        file.close();
        delete this;
    }

private:
    static void take(Str* buffer, const char* text, Int count) {
        buffer->resize((std::size_t) count);
        if (count > 0) std::memcpy(buffer->data(), text, (std::size_t) count);
    }
};

FileStream* simse_fileStream_open(const Str& path);
