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

// `Int.toString()`: the scalar-to-inline-string conversion (specs/memory-model.md).
inline Str simse_int_toString(Int self) {
    return std::to_string(self);
}
