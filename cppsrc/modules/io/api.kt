package io

// The `io` module: the program-facing file and directory operations. Each declaration names its
// symbol in the `fileio`/`filestream` sections of `cppsrc/rtl/_res.md`, so a program that
// imports this module (and names it - `--module cppsrc/modules/io`) stays a single translation
// unit (impl_specs/native-interop.md).
//
// The one type the operations hang on, `FileStream`, is *not* here (it is the prelude's,
// `cppsrc/rtl/fs.kt`): a type cannot name its C++ with `@SmGen` in this language - attributes are
// methods-only - so a `data class` in a program module would be emitted as a struct of its own,
// clashing with `filestream.hpp`. The operations became a module's; the type stayed.

// Every `ext`-suffixed file under `dir`, recursively, sorted; empty if `dir` is not a directory.
@SmGen("res", "fileio", "simse_listFiles")
fun listFiles(dir: Str, ext: Str): List<Str>

// Every `ext`-suffixed file directly under `dir`, sorted (import resolution).
@SmGen("res", "fileio", "simse_listFilesDirect")
fun listFilesDirect(dir: Str, ext: Str): List<Str>

// The whole file as bytes; `false` when it cannot be written.
@SmGen("res", "fileio", "simse_writeFile")
fun writeFile(path: Str, content: Str): Bool

// The canonical spelling of `path` (the same absolute path for two names of one file).
@SmGen("res", "fileio", "simse_pathCanonical")
fun pathCanonical(path: Str): Str

@SmGen("res", "fileio", "simse_pathIsDirectory")
fun pathIsDirectory(path: Str): Bool

@SmGen("res", "fileio", "simse_pathExists")
fun pathExists(path: Str): Bool

// One line to standard error, with the newline. The profiler's table and the driver's diagnostics
// both leave through it.
@SmGen("res", "fileio", "simse_eprintln")
fun eprintln(text: Str): Unit

// `openFileStream` creates the handle (null when the file cannot be opened), `close` releases it.
// Read with ONE of the three reads below: `readLine` leaves the position after what it read,
// while the other two share a readahead buffer, so mixing them skips bytes.
@SmGen("res", "fileio", "simse_fileStream_open")
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
