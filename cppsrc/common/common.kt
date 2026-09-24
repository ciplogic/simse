// common.kt
//
// The compiler's own file utility, its C++ generated from the `fileio` section of
// `cppsrc/rtl/_res.md` (impl_specs/native-interop.md).

package common

@SmGen("res", "fileio", "simse_native_readFile")
fun readFile(filePath: Str): Str

// A position in a source file: `offset` is the 0-based first byte, `line`/`column` are
// 1-based, a tab is one column, and CRLF advances the line exactly once.
data class SourcePos(var offset: Int, var line: Int, var column: Int)
