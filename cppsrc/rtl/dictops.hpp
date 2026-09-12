#pragma once

#include <algorithm>
#include <type_traits>
#include <utility>

#include "containers.hpp"
#include "optional.hpp"
#include "types.hpp"

// Native implementations behind the Simse prelude `cppsrc/rtl/rtl.simse`
// (impl_specs/native-interop.md, T20). These are the Dictionary operations and
// the extra List helpers the language exposes as `native("symbol") fun name(...)`
// extensions or free generic functions.
//
// The key/value parameters are non-deduced (`std::type_identity_t`) so that a
// literal argument (e.g. a `const char[]` key or an integer value) converts to
// the element type instead of making the template argument ambiguous.
//
// Errors are unchecked, matching `std::unordered_map` and the language's
// no-exceptions policy: `get`/`has` on a missing key behave as documented, while
// `remove` of an absent key is a no-op.

// `dictionaryOf<K, V>()`: the empty-dictionary construction. `Dictionary<K, V>` is
// a value type (`std::unordered_map`), so this simply default-constructs one.
template <class K, class V>
Dictionary<K, V> simse_dictionaryOf() {
    return Dictionary<K, V>();
}

// `d.get(key)`: the value for `key`, or an empty `Opt` when absent.
template <class K, class V>
Opt<V> simse_dict_get(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    auto it = self.find(key);
    if (it == self.end()) return Opt<V>::none();
    return Opt<V>::some(it->second);
}

// `d.has(key)`: whether `key` is present.
template <class K, class V>
Bool simse_dict_has(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    return self.find(key) != self.end();
}

// `d.insert(key, value)`: insert or replace (std::unordered_map::insert_or_assign).
template <class K, class V>
void simse_dict_insert(Dictionary<K, V>& self, const std::type_identity_t<K>& key,
                       const std::type_identity_t<V>& value) {
    self.insert_or_assign(key, value);
}

// `d.remove(key)`: erase when present (a no-op otherwise).
template <class K, class V>
void simse_dict_remove(Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    self.erase(key);
}

// `d.size()`: the number of entries.
template <class K, class V>
Int simse_dict_size(const Dictionary<K, V>& self) {
    return (Int) self.size();
}

// `d.keys()`: the keys in the dictionary's iteration order (unspecified; sort for
// a deterministic order).
template <class K, class V>
List<K> simse_dict_keys(const Dictionary<K, V>& self) {
    List<K> out;
    out.reserve((Int) self.size());
    for (const auto& entry: self) out.push_back(entry.first);
    return out;
}

// `d.values()`: the values in the dictionary's iteration order (unspecified).
template <class K, class V>
List<V> simse_dict_values(const Dictionary<K, V>& self) {
    List<V> out;
    out.reserve((Int) self.size());
    for (const auto& entry: self) out.push_back(entry.second);
    return out;
}

// `d.clear()`: remove every entry.
template <class K, class V>
void simse_dict_clear(Dictionary<K, V>& self) {
    self.clear();
}

// `items.contains(value)`: linear membership test (`operator==` on elements).
template <class T>
Bool simse_list_contains(const List<T>& self, const std::type_identity_t<T>& value) {
    for (const T& item: self) {
        if (item == value) return true;
    }
    return false;
}

// `items.sort(less)`: in-place std::sort using the `(T, T) -> Bool` comparator.
// The comparator comes from a Simse lambda (a C++ lambda or Func), so it is a
// template parameter rather than a fixed type.
template <class T, class F>
void simse_list_sort(List<T>& self, F less) {
    std::sort(self.begin(), self.end(), less);
}
