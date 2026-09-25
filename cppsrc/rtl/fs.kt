// fs.kt
//
// The prelude's `FileStream` type. The file and directory *operations* moved to the `io` module
// (`cppsrc/modules/io/api.kt`); this type stays in the prelude, and the reason is a language
// limit rather than taste: a type cannot name its C++ with `@SmGen` (attributes are
// methods-only), so a `data class` declared in a program module would be emitted as a struct of
// its own and clash with the `FileStream` the RTL's `filestream.hpp` provides.

package rtl

// `FileStream` is an open file read one line at a time. The handle is a raw pointer:
// `io.openFileStream` creates it (null when the file cannot be opened), `close` releases it.
//
// Read with ONE of the three reads (all in `io`): `readLine` leaves the position after what it
// read, while the other two share a readahead buffer, so mixing them skips bytes.
data class FileStream()
