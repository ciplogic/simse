// fs.kt
//
// Filesystem/IO prelude declarations for the compiler driver. Each names its symbol in the
// `fileio` section of `cppsrc/rtl/_res.md`, so a program that uses one stays a single
// translation unit (impl_specs/native-interop.md).

package rtl

// Every `ext`-suffixed file under `dir`, recursively, sorted; empty if `dir` is not a directory.
@SmGen("res", "fileio", "simse_listFiles")
fun listFiles(dir: Str, ext: Str): List<Str>

// Every `ext`-suffixed file directly under `dir`, sorted (import resolution).
@SmGen("res", "fileio", "simse_listFilesDirect")
fun listFilesDirect(dir: Str, ext: Str): List<Str>

@SmGen("res", "fileio", "simse_writeFile")
fun writeFile(path: Str, content: Str): Bool

@SmGen("res", "fileio", "simse_pathCanonical")
fun pathCanonical(path: Str): Str

@SmGen("res", "fileio", "simse_pathIsDirectory")
fun pathIsDirectory(path: Str): Bool

@SmGen("res", "fileio", "simse_pathExists")
fun pathExists(path: Str): Bool

@SmGen("res", "fileio", "simse_eprintln")
fun eprintln(text: Str): Unit

// `FileStream` is an open file read one line at a time. The handle is a raw pointer:
// `openFileStream` creates it (null when the file cannot be opened), `close` releases it.
//
// Read with ONE of the three reads below: `readLine` leaves the position after what it
// read, while the other two share a readahead buffer, so mixing them skips bytes.
data class FileStream()

// The next line without its line ending, or an empty `Opt` at end of file. Allocates a
// fresh `Str` per call; a hot loop wants `readLineInto`.
@SmGen("res", "filestream", "simse_fileStream_readLine")
fun readLine(this: *FileStream): Opt<Str>

// The next line into `buffer` (reused across calls), `false` at end of file.
@SmGen("res", "filestream", "simse_fileStream_readLineInto")
fun readLineInto(this: *FileStream, buffer: *Str): Bool

// The next line as a `StrView` into the stream's readahead buffer, or an empty `Opt` at
// end of file. Valid only until the next read on this stream; copy with `toString()`.
@SmGen("res", "filestream", "simse_fileStream_readLineView")
fun readLineView(this: *FileStream): Opt<StrView>

@SmGen("res", "fileio", "simse_fileStream_open")
fun openFileStream(path: Str): *FileStream

@SmGen("res", "filestream", "simse_fileStream_close")
fun close(this: *FileStream): Unit

// The file's size in bytes (0 when unknown), for throughput reporting.
@SmGen("res", "filestream", "simse_fileStream_bytes")
fun fileSize(this: *FileStream): Int64
