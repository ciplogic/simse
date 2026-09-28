// fs.kt
//
// The prelude's `FileStream` type. The file and directory *operations* moved to the `io` module
// (`cppsrc/modules/io/api.kt`); this type stays in the prelude, and now names its C++ with the
// materialization marker: `@SmGen("cpp")` keeps the struct the RTL's `filestream.hpp` provides
// instead of generating one (specs/attributes.md).

package rtl

// `FileStream` is an open file read one line at a time. The handle is a raw pointer:
// `io.openFileStream` creates it (null when the file cannot be opened), `close` releases it.
//
// Read with ONE of the three reads (all in `io`): `readLine` leaves the position after what it
// read, while the other two share a readahead buffer, so mixing them skips bytes.
@SmGen("cpp")
data class FileStream()
