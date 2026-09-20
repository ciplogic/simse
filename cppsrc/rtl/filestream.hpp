#pragma once

#include <cstring>
#include <fstream>
#include <string>

#include "containers.hpp"
#include "optional.hpp"
#include "span.hpp"
#include "types.hpp"

// Reading a file line by line. The Simse surface is the prelude file cppsrc/rtl/fs.kt,
// and **this header is the struct alone**: its fields, its method declarations and the
// three reads' contract. The bodies are the `filestream` section of cppsrc/rtl/_res.md -
// the emitter calls a handle's methods as members (`stream->readLine()`), so the class
// declares them here and the section defines them, and a program carries the text only
// when it calls one. `<fstream>`/`<string>` are the fields' includes and `<cstring>` is
// the section's (`memchr`/`memmove`/`memcpy`), since this header is compiled first.
//
// `simse_fileStream_open` is *not* a method: it is a free function in the always-emitted
// `fileio` section, because a program may name it with a declaration of its own.
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
//                           result is a `StrView` (a span of bytes) into the same
//                           readahead buffer, so it is valid only until the next read
//                           on this stream (a refill moves the bytes). Parsing
//                           straight from it is what a scanner wants; keep a copy when
//                           the line must outlive the next read.
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
    Opt<Str> readLine();

    // The next line into the caller's buffer (reused across calls), `false` at end of
    // file.
    Bool readLineInto(Str* buffer);

    // The next line as a view into the readahead buffer, or an empty `Opt` at end of
    // file. Nothing is copied; the span is valid until the next read on this stream.
    Opt<StrView> readLineView();

    // The file's size in bytes (0 when it is unknown).
    Int64 fileSize() const;

    // Releases the handle; the stream was allocated by `simse_fileStream_open`.
    void close();

private:
    // Advances the readahead buffer to the next line's bytes: `*from`/`*count` are the
    // indexes of the line's first byte and its length (the line ending is not part of
    // it), already stripped of a trailing `\r`. `false` at end of file - and then the
    // chunk is left empty, so a view handed out before it stays the last line.
    Bool nextLineSpan(Int* from, Int* count);

    static void take(Str* buffer, const char* text, Int count);
};
