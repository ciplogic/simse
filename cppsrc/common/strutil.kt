// strutil.kt
//
// The compiler's own string-list join. The parser (argument/name joins) and the
// emitter's `cgJoin` were the same loop written out; this is the one copy they share.
// A `Str` separator, so `", "`, `","` and a one-byte separator all go through it.

package common

// `parts` joined by `separator`: one buffer, its length summed first, no trailing
// separator; an empty list is "". The guide's rule for a join is why this is not
// `out = out + part` per element (that rebuilds the buffer each time).
fun joinStrs(parts: *List<Str>, separator: *Str): Str {
    val count: Int = parts.size()
    if (count == 0) {
        return ""
    }
    var len: Int = separator.size() * (count - 1)
    for (*part in parts) {
        len += part.size()
    }
    var out: Str = ""
    out.reserve(len)
    var first: Bool = true
    for (*part in parts) {
        if (!first) {
            out.appendStrPtr(separator)
        }
        out.appendStrPtr(part)
        first = false
    }
    return out
}
