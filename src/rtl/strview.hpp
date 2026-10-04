#pragma once

#include "intrinsics.hpp"
#include "smstring.hpp"

// StrView is the view a string's bytes are read through, and it *is* a `Span<Char>`: the
// alias below is the whole declaration, so a `Span<Char>` a program holds is a `StrView`
// and the other way round - one type with two names and no wrapper to unpack. What the
// second name buys is the byte-level operations a `Span<T>` stays too uniform to carry.
//
// Those operations are **not here**. The view's surface - the span's own members, the text
// operations, and the six comparisons and `+` - is the prelude (src/rtl/Span.kt,
// src/rtl/StrView.kt, and the `strview` section of src/rtl/_res.md): each is a declaration
// with a body, emitted into a program only when reached (`impl_specs/generators.md`).
// `Span` itself is generated from src/rtl/Span.kt now, so only the name needs a header:
// this alias, which an emitted signature or a `_res.md` section spells.
//
// The two `Str`-internals primitives the text operations are written over are here too:
// `simse_str_data` (the bytes, the prelude's `strBytes`) and `simse_str_setBytes` (the
// owned-copy primitive `substr`/`trim`/`plus` build through). Both are declared as
// intrinsics in src/rtl/intrinsics.kt. The `StrView -> Str` converting constructor is
// *declared* in smstring.hpp and *defined* in the `strconv` section of src/rtl/_res.md,
// because only a section placed after the generated types sees the span complete.
//
// Comparisons used to be here - eighteen `operator` overloads and three `operator+`, found
// by the C++ compiler's overload resolution at a literal site, with `simse_strView_compare`
// behind them. They are gone: `StrView` declares `compareTo`/`equals`/`plus` in the prelude
// (specs/functions.md, "Operator functions"), a `Str` operand of those is read as a view of
// itself (`spanOfStr`), and the emitter spells the operator call.
//
// It owns nothing and copies nothing, so it is valid only while the bytes it points at are
// alive and unmodified - what `FileStream.readLineView()` hands back is valid until the
// next read on that stream (a refill moves the buffer).
//
// `Span<Char>` is packed like the RTL's other value containers and the generated aggregates
// (src/rtl/Span.kt, the `SIMSE_PACK_*` rule of specs/memory-model.md), so a view is 12
// bytes where the host's alignment would make it 16. Bounds are unchecked, matching the
// RTL's no-exceptions policy.
using StrView = Span<Char>;

// The bytes of a `Str`, borrowed (the prelude's `strBytes`, src/rtl/intrinsics.kt): the
// one accessor a Simse text operation needs, because `Str` is opaque to the language. Valid
// until the string is changed. The cast is the language's mutable `Char` against the
// standard library's `char`.
inline Char* simse_str_data(Str* text) {
    return reinterpret_cast<Char*>(text->data());
}

// Replaces a string's bytes with a copy of `count` bytes of `src` from `srcIndex`: the
// owned-copy primitive `StrView.substr` builds through. One `resize` and one block copy
// (`simse_mem_copy`), where a per-byte `append` would walk the bytes one at a time.
inline void simse_str_setBytes(Str& out, const Char* src, Int srcIndex, Int count) {
    if (count <= 0) {
        return;
    }
    out.resize((Str::size_type) count);
    simse_mem_copy(reinterpret_cast<Char*>(out.data()), 0, src, srcIndex, count);
}
