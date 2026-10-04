// fs.kt
//
// The `streams` module: `FileStream`, the handle that reads one file line at a time, and its
// operations. The type's layout only C++ can express (`std::ifstream`, a recycled
// `std::string`, the readahead `Str`), so the declaration carries the materialization marker -
// `@SmGen("cpp")` keeps the module's hand-written `filestream.hpp` struct instead of
// generating one (specs/attributes.md) - and the method bodies are the `filestream` section of
// the module's `_res.md`. The file and directory *operations* are the `io` module's
// (src/modules/io/api.kt); a program that reads lines names both modules.

package streams

// An open file read one line at a time. The handle is a raw pointer: `openFileStream`
// creates it (null when it cannot be opened), `close` releases it.
@SmGen("cpp")
data class FileStream()

// `openFileStream` creates the handle (null when the file cannot be opened), `close` releases it.
// Read with ONE of the three reads below: `readLine` leaves the position after what it read,
// while the other two share a readahead buffer, so mixing them skips bytes.
@SmGen("res", "filestream", "simse_fileStream_open")
fun openFileStream(path: Str): *FileStream

// The next line without its line ending, or an empty `Opt` at end of file. Allocates a fresh
// `Str` per call; a hot loop wants `readLineInto`.
@SmGen("res", "filestream", "simse_fileStream_readLine")
fun readLine(this: *FileStream): Opt<Str>

// The next line into `buffer` (reused across calls), `false` at end of file.
@SmGen("res", "filestream", "simse_fileStream_readLineInto")
fun readLineInto(this: *FileStream, buffer: *Str): Bool

// The next line as a `StrView` into the stream's readahead buffer, or an empty `Opt` at end of
// file. Valid only until the next read on this stream; copy with `toString()`.
@SmGen("res", "filestream", "simse_fileStream_readLineView")
fun readLineView(this: *FileStream): Opt<StrView>

@SmGen("res", "filestream", "simse_fileStream_close")
fun close(this: *FileStream): Unit

// The file's size in bytes (0 when unknown), for throughput reporting.
@SmGen("res", "filestream", "simse_fileStream_bytes")
fun fileSize(this: *FileStream): Int64
