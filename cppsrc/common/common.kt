// common.kt
//
// The compiler's own file utility. The C++ is generated from the `fileio` section of
// cppsrc/rtl/_res.md (like the prelude's own filesystem operations, cppsrc/rtl/fs.kt), so
// nothing is linked in beside the program's own translation unit.

package common

@SmGen("res", "fileio", "simse_native_readFile")
fun readFile(filePath: Str): Str

// A position in a source file. `offset` is the 0-based byte offset of the
// token's first character; `line` and `column` are 1-based. Tabs count as a
// single column. A newline is '\n', or '\r' not immediately followed by
// '\n', so CRLF advances the line exactly once.
data class SourcePos(var offset: Int, var line: Int, var column: Int)
