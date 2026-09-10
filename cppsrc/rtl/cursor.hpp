#pragma once

#include <memory>
#include <utility>

#include "containers.hpp"
#include "types.hpp"

// Cursor<T> is an immutable, Span-like view over a `List<T>` (T16,
// specs/containers.md). Iteration is `while (c.hasValue()) { ... c = c.next() }`;
// the C++ headers intentionally have no `for`/range-for.
//
// The cursor is a value: `next` and `slice` return NEW cursors and never mutate
// the receiver. `source` is a counted reference to the backing list, so the
// cursor does not copy the elements and keeps the list alive while it is used.
//
// Bounds are unchecked, matching the language's no-exceptions policy: calling
// `value()` on an empty cursor, or `slice(count)` with a count larger than the
// remaining length, is undefined behavior.
template <class T>
struct Cursor {
    // The backing list (`&List<T>`). A default-constructed cursor has none.
    std::shared_ptr<List<T>> source;
    // Index of the first element the cursor still covers.
    Int start = 0;
    // Number of remaining elements.
    Int len = 0;

    Cursor() = default;
    Cursor(std::shared_ptr<List<T>> src, Int s, Int l)
        : source(std::move(src)), start(s), len(l) {}

    // True while at least one element remains.
    Bool hasValue() const {
        return len > 0;
    }

    // The first remaining element, by value.
    T value() const {
        return (*source)[(std::size_t) start];
    }

    // The cursor advanced by one element.
    Cursor<T> next() const {
        return slice(1);
    }

    // The cursor advanced by `count` elements (unchecked).
    Cursor<T> slice(Int count) const {
        return Cursor<T>(source, start + count, len - count);
    }

    // The number of remaining elements.
    Int size() const {
        return len;
    }
};

// The `cursorOf(items)` helper: a cursor covering all of `items` from index 0.
template <class T>
Cursor<T> simse_cursorOf(const std::shared_ptr<List<T>>& items) {
    Cursor<T> cursor;
    cursor.source = items;
    cursor.start = 0;
    cursor.len = items ? (Int) items->size() : 0;
    return cursor;
}
