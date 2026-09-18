#pragma once

#include <cstring>

#include "span.hpp"
#include "types.hpp"

// StrView is the view a string's bytes are read through: a `Span<Char>` plus the
// operations text needs. It owns nothing and copies nothing, so it is valid only while
// the bytes it points at are alive and unmodified - what `FileStream.readLineView()`
// hands back is valid until the next read on that stream (a refill moves the buffer).
//
// The span is embedded rather than aliased (`typealias StrView = Span<Char>`) for two
// reasons: an alias does not survive the emitter's receiver-type lookup, and with a
// real name the operations can be declared as the prelude's natives - which is what
// gives the emitter their *return types*, so `view.slice(0, n).toString()` and
// `"x" + view.toString()` are emitted correctly.
//
// The Simse surface is the prelude file cppsrc/rtl/StrView.simse: `StrView` maps onto
// this struct and every operation is a native with the symbol below. Bounds are
// unchecked, matching the RTL's no-exceptions policy.
//
// Packed like `Span` (span.hpp): a view is a value the language hands around, so it
// follows the 4-byte rule of specs/memory-model.md - 12 bytes, where the host's
// alignment would make it 16.
SIMSE_PACK_PUSH
struct StrView {
    // The bytes this view covers.
    Span<Char> bytes;

    StrView() = default;
    StrView(Span<Char> span) : bytes(span) {}
    StrView(Char* data, Int count) : bytes(data, count) {}

    // `view[i]`: the byte at `index` (unchecked), so it is also assignable.
    Char& operator[](Int index) const { return bytes[index]; }

    // The owned copy of the whole view, as a `Str` (the language's `toString()`
    // convention). A *member* because the emitter writes `.toString()` on an entry of
    // the program's string-literal table, which is an array of these
    // (impl_specs/rtl-abi.md, "String literals"); the prelude declares the same
    // operation as the free function below (`simse_strView_toString`).
    Str toString() const;
};
SIMSE_PACK_POP

// ---- the operations the prelude declares (StrView.simse) -------------------

inline Int simse_strView_size(StrView self) {
    return self.bytes.len;
}

inline Bool simse_strView_isEmpty(StrView self) {
    return self.bytes.len <= 0;
}

inline Char& simse_strView_at(StrView self, Int index) {
    return self.bytes[index];
}

// `view.slice(start)`: from `start` to the end (C# `Slice(int)`).
inline StrView simse_strView_slice(StrView self, Int start) {
    return StrView(self.bytes.slice(start));
}

// `view.slice(start, count)`: `count` bytes from `start` (C# `Slice(int, int)`).
inline StrView simse_strView_slice(StrView self, Int start, Int count) {
    return StrView(self.bytes.slice(start, count));
}

inline Char simse_strView_charAt(StrView self, Int index) {
    return self.bytes[index];
}

// True when the view begins with `text`.
inline Bool simse_strView_startsWith(StrView self, const Str& text) {
    const Int count = text.size();
    if (count > self.bytes.len) return false;
    for (Int i = 0; i < count; i++) {
        if ((char) self.bytes[i] != text[i]) return false;
    }
    return true;
}

// `startsWithPtr(text, length)`: the same comparison against text this view does not
// own, reached by raw pointer and with its length already known. `startsWith` would
// copy the `Str` first, which is what a table lookup cannot afford; the first byte is
// the caller's cheap test, this does the rest.
inline Bool simse_strView_startsWithPtr(StrView self, const Str* text, Int length) {
    if (length > self.bytes.len) return false;
    for (Int i = 1; i < length; i++) {
        if ((char) self.bytes[i] != (*text)[i]) return false;
    }
    return true;
}

// `find(sub)`: the index of the first occurrence of `sub` in the bytes, or -1. The
// bytes are compared in place: nothing is copied.
inline Int simse_strView_find(StrView self, const Str& sub) {
    const Int needle = sub.size();
    if (needle == 0) return 0;
    if (needle > self.bytes.len) return -1;
    for (Int i = 0; i + needle <= self.bytes.len; i++) {
        Int j = 0;
        while (j < needle && (char) self.bytes[i + j] == sub[j]) j++;
        if (j == needle) return i;
    }
    return -1;
}

// `indexOf` is the other spelling of `find`.
inline Int simse_strView_indexOf(StrView self, const Str& sub) {
    return simse_strView_find(self, sub);
}

// The owned copy of `count` bytes from `from`, with `from` clamped to [0, size] and
// `count` allowed to run to the end, like `Str.substr`.
inline Str simse_strView_substr(StrView self, Int from, Int count) {
    const Int len = self.bytes.len;
    Int begin = from < 0 ? 0 : from;
    if (begin > len) begin = len;
    Int end = count < 0 ? begin : begin + count;
    if (end > len) end = len;
    Str result;
    if (end > begin) {
        result.resize(end - begin);
        std::memcpy(result.data(), self.bytes.ptr + begin, (std::size_t) (end - begin));
    }
    return result;
}

// The owned copy of the whole view, as a `Str` (the language's `toString()`
// convention, like `Int.toString()`).
inline Str simse_strView_toString(StrView self) {
    return simse_strView_substr(self, 0, self.bytes.len);
}

inline Str StrView::toString() const {
    return simse_strView_toString(*this);
}

// `spanOfStr(text)`: a view over a string's bytes. It borrows the string - the string
// has to outlive the view - and does not copy it (`&text` would box a copy instead).
// `Str` is a `char` buffer on the C++ side and the language's `Char` is a signed byte,
// hence the cast.
inline StrView simse_spanOfStr(Str* text) {
    return StrView(reinterpret_cast<Char*>(text->data()), text->size());
}

// ---- the view/`Str` boundary ---------------------------------------------------

// The interop the *literal sites* need (impl_specs/rtl-abi.md, "String literals"): an
// entry of the program's string table is a `StrView`, and a site may compare it,
// concatenate it or hand it to anything that takes an owned `Str`. Comparisons and `+`
// are *direct* overloads on purpose - that is the point, they must not build a `Str` -
// while every other position reaches the converting constructor above and materializes
// exactly what it materialized before the table became a pool of views.
//
// The `Str` operands are covered as well as the view ones: `Str` converts to `StrView`
// through `simse_strView_of`, and without the pair of mixed overloads `str == view`
// would be ambiguous (one candidate would convert the left, another the right).
//
// The one cast here is `data()`'s constness, against the language's mutable `Char*` -
// the same one-place cast `strtable.hpp` makes for the pool. Nothing writes through a
// view made here.
inline StrView simse_strView_of(const Str& text) {
    return StrView(const_cast<Char*>(reinterpret_cast<const Char*>(text.data())), (Int) text.size());
}

// Three-way compare, the rule `SmString::compareBytes` uses: the common prefix decides,
// then the shorter text is the smaller one.
inline Int simse_strView_compare(StrView left, StrView right) {
    const Int mine = left.bytes.len;
    const Int theirs = right.bytes.len;
    const Int common = mine < theirs ? mine : theirs;
    if (common > 0) {
        const Int diff = std::memcmp(left.bytes.ptr, right.bytes.ptr, (std::size_t) common);
        if (diff != 0) return diff < 0 ? -1 : 1;
    }
    if (mine == theirs) return 0;
    return mine < theirs ? -1 : 1;
}

inline Bool operator==(StrView left, StrView right) { return simse_strView_compare(left, right) == 0; }
inline Bool operator!=(StrView left, StrView right) { return simse_strView_compare(left, right) != 0; }
inline Bool operator<(StrView left, StrView right) { return simse_strView_compare(left, right) < 0; }
inline Bool operator<=(StrView left, StrView right) { return simse_strView_compare(left, right) <= 0; }
inline Bool operator>(StrView left, StrView right) { return simse_strView_compare(left, right) > 0; }
inline Bool operator>=(StrView left, StrView right) { return simse_strView_compare(left, right) >= 0; }

inline Bool operator==(StrView left, const Str& right) { return simse_strView_compare(left, simse_strView_of(right)) == 0; }
inline Bool operator!=(StrView left, const Str& right) { return simse_strView_compare(left, simse_strView_of(right)) != 0; }
inline Bool operator<(StrView left, const Str& right) { return simse_strView_compare(left, simse_strView_of(right)) < 0; }
inline Bool operator<=(StrView left, const Str& right) { return simse_strView_compare(left, simse_strView_of(right)) <= 0; }
inline Bool operator>(StrView left, const Str& right) { return simse_strView_compare(left, simse_strView_of(right)) > 0; }
inline Bool operator>=(StrView left, const Str& right) { return simse_strView_compare(left, simse_strView_of(right)) >= 0; }

inline Bool operator==(const Str& left, StrView right) { return simse_strView_compare(simse_strView_of(left), right) == 0; }
inline Bool operator!=(const Str& left, StrView right) { return simse_strView_compare(simse_strView_of(left), right) != 0; }
inline Bool operator<(const Str& left, StrView right) { return simse_strView_compare(simse_strView_of(left), right) < 0; }
inline Bool operator<=(const Str& left, StrView right) { return simse_strView_compare(simse_strView_of(left), right) <= 0; }
inline Bool operator>(const Str& left, StrView right) { return simse_strView_compare(simse_strView_of(left), right) > 0; }
inline Bool operator>=(const Str& left, StrView right) { return simse_strView_compare(simse_strView_of(left), right) >= 0; }

inline Str operator+(StrView left, StrView right) {
    Str result;
    result.reserve(left.bytes.len + right.bytes.len);
    result.append(reinterpret_cast<const char*>(left.bytes.ptr), (Int) left.bytes.len);
    result.append(reinterpret_cast<const char*>(right.bytes.ptr), (Int) right.bytes.len);
    return result;
}

inline Str operator+(StrView left, const Str& right) {
    Str result;
    result.reserve(left.bytes.len + (Int) right.size());
    result.append(reinterpret_cast<const char*>(left.bytes.ptr), (Int) left.bytes.len);
    result.append(right);
    return result;
}

inline Str operator+(const Str& left, StrView right) {
    Str result;
    result.reserve((Int) left.size() + right.bytes.len);
    result.append(left);
    result.append(reinterpret_cast<const char*>(right.bytes.ptr), (Int) right.bytes.len);
    return result;
}

// `println` is `std::cout << value`, so a view needs its own writer to stay unowned.
inline std::ostream& operator<<(std::ostream& out, StrView value) {
    if (value.bytes.len > 0) {
        out.write(reinterpret_cast<const char*>(value.bytes.ptr), (std::streamsize) value.bytes.len);
    }
    return out;
}

// The `StrView -> Str` conversion declared in smstring.hpp. Delegates to the
// `(text, count)` constructor, so the pool's bytes are copied exactly once.
inline SmString::SmString(const StrView& view)
    : SmString(reinterpret_cast<const char*>(view.bytes.ptr), (Int) view.bytes.len) {
}
