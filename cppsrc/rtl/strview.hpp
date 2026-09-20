#pragma once

#include <cstring>

#include "span.hpp"
#include "types.hpp"

// StrView is the view a string's bytes are read through, and it *is* a `Span<Char>`: the
// alias below is the whole declaration, so a `Span<Char>` a program holds is a `StrView`
// and the other way round - one type with two names and no wrapper to unpack. What the
// second name buys is the byte-level operations a `Span<T>` stays too uniform to carry.
//
// Those operations are **not here**. This header holds the type and the interop, and the
// operations are the `strview` section of cppsrc/rtl/_res.md - the prelude declares each
// one (`@SmGen("res", "strview", "simse_strView_...")`, cppsrc/rtl/StrView.kt) and a
// program carries the text only when it calls one (`impl_specs/generators.md`). The
// *interop* cannot be reached that way: `operator==`, `<`, `+`, `<<` and
// `simse_strView_of` are found by the C++ compiler's overload resolution at a string
// literal's site, not by a declaration, so no declaration could name them and they stay
// in the header - where they cost a program nothing until they are used.
//
// It owns nothing and copies nothing, so it is valid only while the bytes it points at are
// alive and unmodified - what `FileStream.readLineView()` hands back is valid until the
// next read on that stream (a refill moves the buffer).
//
// `Span<Char>` is packed like the RTL's other value containers (span.hpp), so a view is 12
// bytes where the host's alignment would make it 16, and it follows the 4-byte rule of
// specs/memory-model.md. Bounds are unchecked, matching the RTL's no-exceptions policy.
using StrView = Span<Char>;

// ---- the view/`Str` boundary ---------------------------------------------------
//
// The interop the *literal sites* need (impl_specs/rtl-abi.md, "String literals"): an
// entry of the program's string table is a `StrView`, and a site may compare it,
// concatenate it or hand it to anything that takes an owned `Str`. Comparisons and `+`
// are *direct* overloads on purpose - that is the point, they must not build a `Str` -
// while every other position reaches the converting constructor below and materializes
// exactly what it materialized before the table became a pool of views.
//
// The `Str` operands are covered as well as the view ones: `Str` converts to `StrView`
// through `simse_strView_of`, and without the pair of mixed overloads `str == view`
// would be ambiguous (one candidate would convert the left, another the right).
//
// The one cast here is `data()`'s constness, against the language's mutable `Char*` -
// the same one-place cast the pool's decoder makes (`strtable` in _res.md). Nothing
// writes through a view made here.
inline StrView simse_strView_of(const Str& text) {
    return StrView(const_cast<Char*>(reinterpret_cast<const Char*>(text.data())), (Int) text.size());
}

// Three-way compare, the rule `SmString::compareBytes` uses: the common prefix decides,
// then the shorter text is the smaller one.
inline Int simse_strView_compare(StrView left, StrView right) {
    const Int mine = left.len;
    const Int theirs = right.len;
    const Int common = mine < theirs ? mine : theirs;
    if (common > 0) {
        const Int diff = std::memcmp(left.ptr, right.ptr, (std::size_t) common);
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
    result.reserve(left.len + right.len);
    result.append(reinterpret_cast<const char*>(left.ptr), left.len);
    result.append(reinterpret_cast<const char*>(right.ptr), right.len);
    return result;
}

inline Str operator+(StrView left, const Str& right) {
    Str result;
    result.reserve(left.len + (Int) right.size());
    result.append(reinterpret_cast<const char*>(left.ptr), left.len);
    result.append(right);
    return result;
}

inline Str operator+(const Str& left, StrView right) {
    Str result;
    result.reserve((Int) left.size() + right.len);
    result.append(left);
    result.append(reinterpret_cast<const char*>(right.ptr), right.len);
    return result;
}

// `println` is `std::cout << value`, so a view needs its own writer to stay unowned.
inline std::ostream& operator<<(std::ostream& out, StrView value) {
    if (value.len > 0) {
        out.write(reinterpret_cast<const char*>(value.ptr), (std::streamsize) value.len);
    }
    return out;
}

// The `StrView -> Str` conversion declared in smstring.hpp: a position that wants an
// owned `Str` where the pool or a view stands. Delegates to the `(text, count)`
// constructor, so the bytes are copied exactly once.
inline SmString::SmString(const Span<Char>& view)
    : SmString(reinterpret_cast<const char*>(view.ptr), view.len) {
}
