#pragma once

#include "containers.hpp"
#include "types.hpp"

// Native implementations behind the Simse prelude `cppsrc/rtl/rtl.simse`
// (impl_specs/native-interop.md). These are the operations the language exposes
// as `native("symbol") fun name(...)` extensions.
//
// Index and range errors are unchecked, matching `std::vector` and the language's
// no-exceptions policy: `removeAt`/`removeRange` with an out-of-range index or
// range is undefined behavior (see specs/language-decisions.md).

// Appends `value` to the end of `self` (std::vector::push_back).
template <class T>
void simse_list_append(List<T>& self, const T& value) {
    self.push_back(value);
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

// `Str.append(ch)` on a byte string. Str is std::string, which has no
// single-character append(), so route through push_back.
inline void simse_str_append(Str& self, Char value) {
    self.push_back(static_cast<char>(value));
}

// `Int.toString()`: the scalar-to-inline-string conversion (specs/memory-model.md).
inline Str simse_int_toString(Int self) {
    return std::to_string(self);
}
