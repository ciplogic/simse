#pragma once

#include <cstddef>
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
struct StrView {
    // The bytes this view covers.
    Span<Char> bytes;

    StrView() = default;
    StrView(Span<Char> span) : bytes(span) {}
    StrView(Char* data, Int count) : bytes(data, count) {}

    // `view[i]`: the byte at `index` (unchecked), so it is also assignable.
    Char& operator[](std::size_t index) const { return bytes[index]; }
};

// ---- the operations the prelude declares (StrView.simse) -------------------

inline Int simse_strView_size(StrView self) {
    return self.bytes.len;
}

inline Bool simse_strView_isEmpty(StrView self) {
    return self.bytes.len <= 0;
}

inline Char& simse_strView_at(StrView self, Int index) {
    return self.bytes[(std::size_t) index];
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
    return self.bytes[(std::size_t) index];
}

// True when the view begins with `text`.
inline Bool simse_strView_startsWith(StrView self, const Str& text) {
    const Int count = (Int) text.size();
    if (count > self.bytes.len) return false;
    for (Int i = 0; i < count; i++) {
        if ((char) self.bytes[(std::size_t) i] != text[(std::size_t) i]) return false;
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
        if ((char) self.bytes[(std::size_t) i] != (*text)[(std::size_t) i]) return false;
    }
    return true;
}

// `find(sub)`: the index of the first occurrence of `sub` in the bytes, or -1. The
// bytes are compared in place: nothing is copied.
inline Int simse_strView_find(StrView self, const Str& sub) {
    const Int needle = (Int) sub.size();
    if (needle == 0) return 0;
    if (needle > self.bytes.len) return -1;
    for (Int i = 0; i + needle <= self.bytes.len; i++) {
        Int j = 0;
        while (j < needle && (char) self.bytes[(std::size_t) (i + j)] == sub[(std::size_t) j]) j++;
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
        result.resize((std::size_t) (end - begin));
        std::memcpy(result.data(), self.bytes.ptr + begin, (std::size_t) (end - begin));
    }
    return result;
}

// The owned copy of the whole view, as a `Str` (the language's `toString()`
// convention, like `Int.toString()`).
inline Str simse_strView_toString(StrView self) {
    return simse_strView_substr(self, 0, self.bytes.len);
}

// `spanOfStr(text)`: a view over a string's bytes. It borrows the string - the string
// has to outlive the view - and does not copy it (`&text` would box a copy instead).
// `Str` is a `char` buffer on the C++ side and the language's `Char` is a signed byte,
// hence the cast.
inline StrView simse_spanOfStr(Str* text) {
    return StrView(reinterpret_cast<Char*>(text->data()), (Int) text->size());
}
