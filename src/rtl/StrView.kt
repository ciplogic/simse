// StrView.kt
//
// `StrView` is a `Span<Char>` under a second name: one type, and the text operations below
// are what the name adds (specs/built-in-types.md). The alias is a language-level name - the
// emitted C++ spells `Span<Char>` everywhere (the emitter resolves a non-generic typealias),
// and types.hpp keeps a `using StrView = Span<Char>;` for the hand-written C++ that prefers
// the view's spelling. It owns nothing, valid only while the
// bytes it points at are alive; `spanOfStr(text)` borrows its source, which must outlive the
// view. Only what reaches a `Str`'s internals or a `Span` member stays in C++, in the
// `strview`/`strconv` sections of src/rtl/_res.md; the rest is Simse over the generated span
// (`src/rtl/Span.kt`) and the `setBytes` intrinsic for the owned copy (`intrinsics.kt`). A
// comparison is the `memCompare` intrinsic (`std::memcmp` behind it, `intrinsics.hpp`), so
// it compares in word loads and the scanner's per-entry lookups stay cheap.

package rtl

typealias StrView = Span<Char>

// The span's own members under the view's names (generated from `src/rtl/Span.kt`):
// `size`, `isEmpty`, `slice`, `at` and `atPtr` are reached through the alias, with their
// Simse bodies in `Span.kt` and their C++ the free functions the emitter writes for a
// generated class (`view.slice(1)` is `slice(&view, 1)` in C++). No passthrough
// declaration is needed - the alias resolves to the class's method.

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

// The owned copy of `count` bytes from `from`, clamped like `Str.substr` (`rtl.kt`):
// `from` is clamped to [0, size], and a negative count takes the rest.
fun StrView.substr(from: Int, count: Int): Str {
    val len = this.size()
    var begin = from
    if (begin < 0) {
        begin = 0
    }
    if (begin > len) {
        begin = len
    }
    var take = len - begin
    if (count >= 0 && count < take) {
        take = count
    }
    var out: Str
    if (take > 0) {
        out.setBytes(this.ptr, begin, take)
    }
    return out
}

fun StrView.toString(): Str {
    return this.substr(0, this.size())
}

// `a < b` and its family: the byte compare lives here now, not in the C++ header
// (specs/functions.md, "Operator functions"). The rule is the one `SmString::compareBytes`
// uses - the common prefix decides, then the shorter text is the smaller one - and
// `memCompare` is the primitive (`std::memcmp` behind it). A `Str` operand is read as a
// view of itself at the call site, so this only ever sees views.
operator fun StrView.compareTo(other: StrView): Int {
    if (this.len <= other.len) {
        val diff = memCompare(this.ptr, 0, other.ptr, 0, this.len)
        if (diff != 0) {
            return diff
        }
        if (this.len == other.len) {
            return 0
        }
        return -1
    }
    val diff = memCompare(this.ptr, 0, other.ptr, 0, other.len)
    if (diff != 0) {
        return diff
    }
    return 1
}

// `a == b`: the lengths must agree and then the bytes (`count == 0` compares nothing,
// which is what makes two empty views equal). Never compares past the shorter text.
operator fun StrView.equals(other: StrView): Bool {
    if (this.len != other.len) {
        return false
    }
    return memCompare(this.ptr, 0, other.ptr, 0, this.len) == 0
}

// `a + b`: one owned `Str` of both views, a block copy each - the first is placed by
// `setBytes`, the second is appended into the tail `resize` made. The result type is what
// an unannotated local infers (`val joined = view + "!"`).
operator fun StrView.plus(other: StrView): Str {
    var result: Str
    result.setBytes(this.ptr, 0, this.len)
    if (other.len > 0) {
        val at = result.size()
        result.resize(at + other.len)
        memCopy(strBytes(*result), at, other.ptr, 0, other.len)
    }
    return result
}

// A view over a string's bytes, borrowing the string (which must outlive the view).
@SmGen("res", "strview", "simse_spanOfStr")
data fun spanOfStr(text: *Str): StrView
