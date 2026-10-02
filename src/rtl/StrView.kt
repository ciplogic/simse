// StrView.kt
//
// `StrView` is a `Span<Char>` under a second name: one type, and the text operations below
// are what the name adds (specs/built-in-types.md). It owns nothing, valid only while the
// bytes it points at are alive; `spanOfStr(text)` borrows its source, which must outlive the
// view. Only what reaches a `Str`'s internals or a `Span` member stays in C++, in the
// `strview` section of src/rtl/_res.md; the rest is Simse over the span (`span.hpp`) and
// the `setBytes` intrinsic for the owned copy (`intrinsics.kt`). A comparison is an inline
// loop, not `memCompare`: the scanner calls `startsWithPtr` per table entry per token, and a
// `memcmp` call for a few bytes is slower than the loop it replaces.

package rtl

typealias StrView = Span<Char>

// The span's own members under the view's names (`src/rtl/span.hpp`): `size`, `isEmpty`,
// `slice`, `at` and `atPtr` are reached through the alias, with their Simse bodies in
// `Span.kt` and their C++ the span's members (specs/built-in-types.md, "Views"). No
// passthrough declaration is needed - the alias resolves to the class's method, which the
// emitter spells as the member call (`view.slice(1)` is `view.slice(1)` in C++ too).

// `charAt` is the view's own spelling of `at` (the byte at `index`, unchecked).
fun StrView.charAt(index: Int): Char {
    return this.at(index)
}

// Compared in place, so nothing is copied.
fun StrView.startsWith(text: Str): Bool {
    val count = text.size()
    if (count > this.size()) {
        return false
    }
    var i = 0
    while (i < count) {
        if (this.ptr[i] != text.charAt(i)) {
            return false
        }
        i = i + 1
    }
    return true
}

// `startsWithPtr(text, length)`: the same comparison against a text this view does not own,
// reached by raw pointer and with its length already known. The first byte is the caller's
// cheap test, so the compare starts at 1; `startsWith` would copy the `Str` first, which a
// table lookup cannot afford.
fun StrView.startsWithPtr(text: *Str, length: Int): Bool {
    if (length > this.size()) {
        return false
    }
    var i = 1
    while (i < length) {
        if (this.ptr[i] != text.charAt(i)) {
            return false
        }
        i = i + 1
    }
    return true
}

// The index of the first occurrence of `sub`, or -1 (compared in place).
fun StrView.find(sub: Str): Int {
    val needle = sub.size()
    if (needle == 0) {
        return 0
    }
    val len = this.size()
    if (needle > len) {
        return -1
    }
    var i = 0
    while (i + needle <= len) {
        var j = 0
        while (j < needle && this.ptr[i + j] == sub.charAt(j)) {
            j = j + 1
        }
        if (j == needle) {
            return i
        }
        i = i + 1
    }
    return -1
}

// `indexOf` is the other spelling of `find`.
fun StrView.indexOf(sub: Str): Int {
    return this.find(sub)
}

// The owned copy of `count` bytes from `from`, clamped like `Str.substr`.
fun StrView.substr(from: Int, count: Int): Str {
    val len = this.size()
    var begin = from
    if (begin < 0) {
        begin = 0
    }
    if (begin > len) {
        begin = len
    }
    var end = begin + count
    if (count < 0) {
        end = begin
    }
    if (end > len) {
        end = len
    }
    var out: Str
    if (end > begin) {
        out.setBytes(this.ptr, begin, end - begin)
    }
    return out
}

fun StrView.toString(): Str {
    return this.substr(0, this.size())
}

// A view over a string's bytes, borrowing the string (which must outlive the view).
@SmGen("res", "strview", "simse_spanOfStr")
data fun spanOfStr(text: *Str): StrView
