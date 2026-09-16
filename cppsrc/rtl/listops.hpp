#pragma once

#include "containers.hpp"
#include "types.hpp"

#include <type_traits>

// Native implementations behind the Simse prelude `cppsrc/rtl/rtl.simse`
// (impl_specs/native-interop.md). These are the operations the language exposes
// as `native("symbol") fun name(...)` extensions.
//
// Index and range errors are unchecked, matching `std::vector` and the language's
// no-exceptions policy: `removeAt`/`removeRange` with an out-of-range index or
// range is undefined behavior (see specs/language-decisions.md).

// Appends `value` to the end of `self` (std::vector::push_back). The value is a
// non-deduced context so a literal argument (e.g. a `const char[]`) converts to
// the element type instead of making `T` ambiguous.
template <class T>
void simse_list_append(List<T>& self, const std::type_identity_t<T>& value) {
    self.push_back(value);
}

// The list literal's fallback (`cppsrc/rtl/rtl.kt`): the compiler turns
// `listOf<Str>("a", "b")` into the construction itself, so this runs only for a
// position with no destination slot (a dropped result).
template <class T>
List<T> simse_listOf(const List<T>* values) {
    return *values;
}

// Removes the single element at `index`.
template <class T>
void simse_list_removeAt(List<T>& self, Int index) {
    self.erase(self.begin() + index);
}

// Removes the half-open range [start, end).
template <class T>
void simse_list_removeRange(List<T>& self, Int start, Int end) {
    self.erase(self.begin() + start, self.begin() + end);
}

// `Array<T>.count()`: the element count stored at the front of the block.
template <class T>
Int simse_array_count(const Array<T>& self) {
    return self.count();
}

// `List<T>.toArray()` (specs/built-in-types.md): copies the elements into one
// count-first block. Element copies are value copies, like every other copy in
// the language - a `List<Str>` copy shares no bytes, a `List<&T>` copy shares the
// referenced objects.
template <class T>
Array<T> simse_list_toArray(const List<T>& self) {
    const Int count = self.size();
    if (count <= 0) {
        return Array<T>();
    }
    Array<T> result(count);
    for (Int i = 0; i < count; i++) {
        result[i] = self[i];
    }
    return result;
}

// `Array<T>.toList()`: the growable copy, which is how an element is added to an
// array (arrays are fixed length: `arr.toList().append(x).toArray()`).
template <class T>
List<T> simse_array_toList(const Array<T>& self) {
    List<T> result;
    const Int count = self.count();
    result.reserve(count);
    for (Int i = 0; i < count; i++) {
        result.push_back(self[i]);
    }
    return result;
}

// `arrayEmpty<T>()`: the shared, zero-length array of `T` (no allocation).
template <class T>
Array<T> simse_arrayEmpty() {
    return Array<T>();
}

// `Str.append(ch)` on a byte string. Str is std::string, which has no
// single-character append(), so route through push_back.
inline void simse_str_append(Str& self, Char value) {
    self.push_back(static_cast<char>(value));
}

// `Str.appendStr(text)`: appends in place. The language has no `+=`, so this is
// how an emitter accumulates output without `out = out + text` rebuilding the
// whole buffer on every line (which is quadratic).
inline void simse_str_appendStr(Str& self, const Str& value) {
    self.append(value);
}

// `Str.appendStrPtr(text)`: the same append for a text the caller only *borrows*
// (`*Str`, the `Ptr` convention `StrView.startsWithPtr` keeps). The reference the
// append takes is the borrow's own pointee, so nothing is copied on the way -
// `out.appendStrPtr(part)` of a loop variable appends the element itself.
inline void simse_str_appendStrPtr(Str& self, const Str* value) {
    if (value != nullptr) self.append(*value);
}

// `fmtStr(fmt, items)`: the format text with each `|` replaced, in order, by one
// item. The result's length is known before anything is written - the format minus
// the points it fills, plus every item - so one buffer is reserved and written
// once, and neither the literal runs nor the items are copied into a temporary on
// the way.
//
// A `|` is a point wherever it appears, `||` included (an empty run between two
// points), and what does not line up still loses nothing: with no point left the
// remaining items are appended, and with no item left the rest of the format -
// its `|`s too - is appended verbatim.
inline Str simse_fmtStr(const Str* fmt, const List<Str>* items) {
    Str out;
    if (fmt == nullptr) return out;
    const Int count = items == nullptr ? 0 : (Int) items->size();
    const Int fmtLen = (Int) fmt->size();
    Int points = 0;
    for (Int i = 0; i < fmtLen; i++) {
        if ((*fmt)[i] == '|') points++;
    }
    const Int filled = points < count ? points : count;
    Int len = fmtLen - filled;
    if (items != nullptr) {
        for (const Str& item: *items) len += (Int) item.size();
    }
    out.reserve(len);
    Int start = 0;
    Int used = 0;
    for (Int i = 0; i < fmtLen && used < filled; i++) {
        if ((*fmt)[i] != '|') continue;
        out.append(fmt->data() + start, (Str::size_type) (i - start));
        out.append((*items)[used]);
        used++;
        start = i + 1;
    }
    out.append(fmt->data() + start, (Str::size_type) (fmtLen - start));
    for (Int i = used; i < count; i++) {
        out.append((*items)[i]);
    }
    return out;
}

// `Str.reserve(count)`: grows the buffer once, so a run of `append`/`appendStr`
// writes the text once instead of copying the accumulated prefix at every growth
// step. A *hint*, not a length: the string keeps its size, and an append past the
// reservation grows it as usual. `SmString::reserve` keeps the terminating NUL,
// so `count` is the character count the caller is about to write.
inline void simse_str_reserve(Str& self, Int count) {
    self.reserve((Str::size_type) count);
}

// `Int.toString()`: the scalar-to-inline-string conversion (specs/memory-model.md).
inline Str simse_int_toString(Int self) {
    return std::to_string(self);
}
