#pragma once

#include <cstring>
#include <fstream>
#include <string>

#include "containers.hpp"
#include "optional.hpp"
#include "strview.hpp"
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
// Three read paths, the same lines (a stream should be read with *one* of them: the
// first leaves the file position after what it read, and the other two share the
// readahead buffer, so mixing them skips bytes):
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
//   readLineView()          the in-place one. The line is not copied at all: the
//                           result is a `StrView` into the same readahead buffer, so
//                           it is valid only until the next read on this stream (a
//                           refill moves the bytes). Parsing straight from it is what
//                           a scanner wants; keep a copy when the line must outlive
//                           the next read.
//
// All three strip a trailing `\r` (files written on Windows) and treat a final line
// without a newline as a line. None decodes anything: the bytes come back as they
// are.
struct FileStream {
    std::ifstream file;      // opened in binary mode; text translation is not wanted
    std::string line;        // recycled by `readLine`
    Str chunk;               // the readahead buffer for `readLineInto`/`readLineView`
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
        Int from = 0;
        Int count = 0;
        if (!nextLineSpan(&from, &count)) return false;
        take(buffer, chunk.data() + from, count);
        return true;
    }

    // The next line as a view into the readahead buffer, or an empty `Opt` at end of
    // file. Nothing is copied; the view is valid until the next read on this stream.
    Opt<StrView> readLineView() {
        Int from = 0;
        Int count = 0;
        if (!nextLineSpan(&from, &count)) return Opt<StrView>::none();
        return Opt<StrView>::some(StrView(&chunk, from, count));
    }

    // The file's size in bytes (0 when it is unknown).
    Int64 fileSize() const { return size; }

    // Releases the handle; the natives cast it back to the stream and delete it.
    void close() {
        file.close();
        delete this;
    }

private:
    // Advances the readahead buffer to the next line's bytes: `*from`/`*count` are the
    // indexes of the line's first byte and its length (the line ending is not part of
    // it), already stripped of a trailing `\r`. `false` at end of file - and then the
    // chunk is left empty, so a view handed out before it stays the last line.
    Bool nextLineSpan(Int* from, Int* count) {
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
            if (tail == (Int) chunk.size()) {
                chunk.resize((std::size_t) (tail * 2));
            }
            file.read(chunk.data() + tail, (std::streamsize) ((Int) chunk.size() - tail));
            const std::streamsize got = file.gcount();
            if (got <= 0) {
                // End of file: whatever the tail holds is the last line (the file did
                // not end with a newline), and the next call reports the end.
                chunkLen = 0;
                chunkAt = 0;
                if (tail == 0) return false;
                Int len = tail;
                if (len > 0 && chunk[(std::size_t) (len - 1)] == '\r') len--;
                *from = 0;
                *count = len;
                return true;
            }
            chunkLen = tail + (Int) got;
            chunkAt = 0;
            at = 0;
        }
    }

    static void take(Str* buffer, const char* text, Int count) {
        buffer->resize((std::size_t) count);
        if (count > 0) std::memcpy(buffer->data(), text, (std::size_t) count);
    }
};

FileStream* simse_fileStream_open(const Str& path);
