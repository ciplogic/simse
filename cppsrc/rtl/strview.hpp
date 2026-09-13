#pragma once

#include "types.hpp"

// StrView is a borrowed view over a range of a `Str` (specs/built-in-types.md,
// "Views"): a pointer to the source plus a start offset and a length. A view
// copies nothing and owns nothing, so it is only valid while its source is alive
// and unchanged - which is the trade the language makes for parsing a buffer
// without allocating per token.
//
// It is what `FileStream.readLineView()` hands back (a window into the stream's
// readahead buffer, moved by the next read on that stream) and what a parse over
// one buffer wants instead of `substr`, which copies. `slice` stays a view,
// `substr`/`toStr` are the owned copies.
//
// The Simse surface is the prelude file cppsrc/rtl/StrView.simse: the fields and
// methods are the `data class` declared there, and codegen maps member calls to
// the members below. Bounds are unchecked except where noted, matching the RTL's
// no-exceptions policy.
struct StrView {
    // The source text (`*Str`). A default-constructed view has none.
    Str* source = nullptr;
    // Index of the view's first byte inside `source`.
    Int start = 0;
    // Number of bytes the view covers.
    Int len = 0;

    StrView() = default;
    StrView(Str* src, Int s, Int l) : source(src), start(s), len(l) {}

    // The number of bytes in the view.
    Int size() const { return len; }

    // True when the view covers no bytes.
    Bool isEmpty() const { return len <= 0; }

    // The byte at `index` (unchecked).
    Char at(Int index) const { return (*source)[(std::size_t) (start + index)]; }

    // The same byte, spelled like `Str.charAt` (unchecked).
    Char charAt(Int index) const { return at(index); }

    // True when the view begins with `text`.
    Bool startsWith(const Str& text) const {
        const Int count = (Int) text.size();
        if (count > len) return false;
        for (Int i = 0; i < count; i++) {
            if (at(i) != text[(std::size_t) i]) return false;
        }
        return true;
    }

    // The index of the first occurrence of `sub` inside the view, or -1. The
    // bytes are compared in place: nothing is copied.
    Int find(const Str& sub) const {
        const Int needle = (Int) sub.size();
        if (needle == 0) return 0;
        if (needle > len) return -1;
        for (Int i = 0; i + needle <= len; i++) {
            Int j = 0;
            while (j < needle && at(i + j) == sub[(std::size_t) j]) j++;
            if (j == needle) return i;
        }
        return -1;
    }

    // `indexOf` is the other spelling of `find` (both are in the spec).
    Int indexOf(const Str& sub) const { return find(sub); }

    // A view of `count` bytes starting `from` bytes into this view: no copy
    // (unchecked, like the rest of the RTL).
    StrView slice(Int from, Int count) const { return StrView(source, start + from, count); }

    // The owned copy of `count` bytes starting `from` bytes in: `start` is clamped
    // to [0, size] and `count` may run to the end, like `Str.substr`.
    Str substr(Int from, Int count) const {
        Int begin = from < 0 ? 0 : from;
        if (begin > len) begin = len;
        Int end = count < 0 ? begin : begin + count;
        if (end > len) end = len;
        Str result;
        if (end > begin) {
            result.append(source->data() + start + begin, (std::size_t) (end - begin));
        }
        return result;
    }

    // The owned copy of the whole view.
    Str toStr() const { return substr(0, len); }
};
