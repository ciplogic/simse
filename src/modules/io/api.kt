package io

// The `io` module: the program-facing file and directory operations. Each declaration names its
// symbol in the `fileio` section of this module's `_res.md`, so a program that imports this
// module (and names it - `--module src/modules/io`) stays a single translation unit
// (impl_specs/native-interop.md).
//
// The line reader is *not* here: `FileStream`, `openFileStream` and the three reads are the
// `streams` module (src/modules/streams/fs.kt), which is where a program that reads a file
// names them; this module stays the path and whole-file operations.

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
