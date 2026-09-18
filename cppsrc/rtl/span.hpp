#pragma once

#include <cstddef>

#include "types.hpp"

// Span<T> is a borrowed view over a contiguous run of `T`: a pointer and a length,
// nothing else (specs/containers.md, "Span<T>"). It owns nothing and copies nothing,
// so it is valid only while the memory it points at is alive and unmodified - the
// language's spelling of C#'s Span<T>, with the same indexing and `slice`.
//
// A span is how a buffer is walked without allocating: `slice` advances the pointer
// (the two-argument form takes a start and a count, the one-argument form runs to the
// end). The text-specific operations a buffer of bytes needs are `StrView`
// (strview.hpp), which holds a `Span<Char>` - this type stays uniform over `T`.
//
// The Simse surface is the prelude file cppsrc/rtl/Span.simse: it declares the fields
// and the members, and codegen emits member calls onto this struct. Bounds are
// unchecked, matching the RTL's no-exceptions policy.
//
// The 4-byte packing is the language's layout rule (specs/memory-model.md,
// `SIMSE_PACK_PUSH` in types.hpp): a span is a value the language copies around, so it
// follows the rule like the generated aggregates do. Without it the host aligns the
// struct to the pointer's 8 bytes and `sizeof(Span<Char>)` is 16 instead of 12; the
// pointer member itself is still 4-aligned (the same shape `Str`'s buffer has).
SIMSE_PACK_PUSH
template <class T>
struct Span {
    // The first element (`*T`). A default-constructed span has none.
    T* ptr = nullptr;
    // The number of elements.
    Int len = 0;

    Span() = default;
    Span(T* data, Int count) : ptr(data), len(count) {}

    // `span[i]`: the element at `index` (unchecked), so it is also assignable.
    T& operator[](Int index) const { return ptr[index]; }

    // The number of elements.
    Int size() const { return len; }

    // True when the span covers nothing.
    Bool isEmpty() const { return len <= 0; }

    // The element at `index` (unchecked; `span[index]` is the same thing).
    T& at(Int index) const { return ptr[index]; }

    // From `start` to the end (C# `Slice(int)`; unchecked).
    Span<T> slice(Int start) const { return Span<T>(ptr + start, len - start); }

    // `count` elements from `start` (C# `Slice(int, int)`; unchecked).
    Span<T> slice(Int start, Int count) const { return Span<T>(ptr + start, count); }
};
SIMSE_PACK_POP

// `spanOf(items)`: a span over a list's elements. It borrows the list - the list has
// to outlive the span - and does not copy it (`&items` would box a copy instead).
template <class T>
inline Span<T> simse_spanOf(List<T>* items) {
    return Span<T>(items->data(), items->size());
}
