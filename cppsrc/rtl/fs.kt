// fs.kt
//
// The prelude's `FileStream` type: the file and directory *operations* live in the `io` module
// (`cppsrc/modules/io/api.kt`), while the type stays here and names its C++ with the
// materialization marker - `@SmGen("cpp")` keeps the RTL's `filestream.hpp` struct instead of
// generating one (specs/attributes.md).

package rtl

// An open file read one line at a time. The handle is a raw pointer: `io.openFileStream`
// creates it (null when it cannot be opened), `close` releases it.
//
// Read with ONE of the three reads (all in `io`): `readLine` leaves the position after what it
// read, while the other two share a readahead buffer, so mixing them skips bytes.
@SmGen("cpp")
data class FileStream()
