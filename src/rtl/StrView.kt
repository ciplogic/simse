// StrView.kt
//
// `StrView` is a `Span<Char>` under a second name: one type, and the text operations below
// are what the name adds (specs/built-in-types.md). It owns nothing, valid only while the
// bytes it points at are alive; `spanOfStr(text)` borrows its source, which must outlive the
// view. Only what reaches a `Str`'s internals or a `Span` member stays in C++, in the
// `strview` section of src/rtl/_res.md; the rest is Simse over the span (`span.hpp`) and
// the `setBytes` intrinsic for the owned copy (`intrinsics.kt`). A comparison is the
// `memCompare` intrinsic (`std::memcmp` behind it, `intrinsics.hpp`), so it compares in word
// loads and the scanner's per-entry lookups stay cheap.

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

// Read-only, so auto-borrow works the parameter: `text` is a `*Str` in the emitted signature
// and the scanner's table lookup passes a pointer into the table, with nothing copied.
fun StrView.startsWith(text: Str): Bool {
    val count = text.size()
    val thisLen = this.len
    if (count > thisLen) {
        return false
    }
    val compareResult = memCompare(this.ptr, 0, strBytes(text), 0, count)
    return compareResult == 0
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
