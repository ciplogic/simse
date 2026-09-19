Generated C++
====
The RTL's hand-written C++ that used to live in headers (impl_specs/generators.md) - one
section per header it came from. Two sections carry the `emit` marker (its value is
`always`), so the compiler emits them for every program - `strtable`, the string table's
decoder, and `timeops`, the clocks. Three are *shared*, one header's worth of functions
that a declaration reaches by naming its symbol - `listops`, `dictops`, `strops`. `spanOf`
(and the collision fixture's `spanOfEmpty`) carries a symbol key of its own. `support`
holds what the program's *preamble* needs, `forward` a declaration and `bodies` a
definition, and a section's texts are placed in the emitted file's section of the same
name. A prose line here is ignored only when it holds no colon character, so keep the
spelling of an entry line in mind when editing.

A title opening with `!` marks its whole section *compile-only* (specs/resources.md). The
compiler reads such a section and the program it builds does not carry it. **Every code
section in this file is marked**, because that is exactly what the file is - C++ the
compiler emits into a program, which is compiled in. Carrying the same 23 KB of text in the
compiler's own string table as well would be a second copy of it, and nothing needs that
copy any more. The compiler reads this file from disk as it compiles, the way it reads the
prelude `.kt` files beside it, and that read is the second half of the generator lookup
(`impl_specs/generators.md`) - which is how a program carrying no `_res.md` of its own still
receives the RTL's C++ (`specs/resources.md`, "What the program carries").

!strtable
====
emit: always
support:
```cpp
// The string-literal pool's decoder (impl_specs/rtl-abi.md, "String literals: one
// table"): the emitter writes one pool of bytes plus two run-length encoded index
// series - where each entry starts and how long it is - and this expands them into the
// `StrView` per entry, once, before `main` runs. It was cppsrc/rtl/strtable.hpp.
//
// Six things are deliberate in the shape the emitter writes:
//
//  - **Both series are stored as "what to subtract from the previous value"**, with an
//    implicit 0 before the first entry: `value[i] = value[i-1] - series[i]`. The
//    literals are ordered longest first, so a length series descends slowly and a
//    *difference* of it is a small number - mostly 0 between the many literals of equal
//    length (85% of them on the compiler's own table).
//  - **Each series is run-length encoded**: its length first, then alternating blocks of
//    non-repeating values (a count, then that many values) and of runs (a count, then
//    that many `times, value` pairs), until the length is filled. The element type is a
//    template parameter because the emitter picks the width: `Int16` when every number
//    fits, `Int` otherwise.
//  - The pool is the literal texts themselves, adjacent, so the C++ compiler decodes
//    every escape and the emitter's decoding only has to agree about *how many bytes* an
//    escape costs. The emitted `static_assert` on the pool's `sizeof` is the check.
//  - The series are expanded into *stack* arrays in the initializer and dropped when it
//    returns: no heap, and the encoded statics are all the program carries.
//  - An entry is a 12-byte `StrView`, not the 32-byte owning `Str` the table used to
//    hold, and start-up allocates nothing for the literals.
//  - The pool is `const char` (a string literal, read-only) while `StrView` holds the
//    language's mutable `Char*`; the constness is cast away here, in the one place the
//    pool is touched, and nothing writes through it.

template <class T>
void simse_strTableExpand(const T* stream, Int* out, Int count);
void simse_strTableDecode(const char* pool, const Int* starts, const Int* lengths, StrView* table, Int count);
```
bodies:
```cpp
// Expands one run-length encoded series into `out`, which holds `count` values.
template <class T>
inline void simse_strTableExpand(const T* stream, Int* out, Int count) {
    Int at = 0;
    Int cursor = 1; // stream[0] is the series' own length
    while (at < count) {
        const Int literals = (Int) stream[cursor++];
        for (Int i = 0; i < literals && at < count; i++) out[at++] = (Int) stream[cursor++];
        if (at >= count) break;
        const Int runs = (Int) stream[cursor++];
        for (Int i = 0; i < runs && at < count; i++) {
            const Int times = (Int) stream[cursor++];
            const Int value = (Int) stream[cursor++];
            for (Int j = 0; j < times && at < count; j++) out[at++] = value;
        }
    }
}

// Fills `table` from the pool and the two expanded series: an offset increment and a
// byte count per entry, both rebuilt by subtracting the stored value from the one before.
inline void simse_strTableDecode(const char* pool, const Int* starts, const Int* lengths, StrView* table, Int count) {
    Char* bytes = const_cast<Char*>(reinterpret_cast<const Char*>(pool));
    Int delta = 0;  // this entry's offset increment, rebuilt from the start series
    Int length = 0; // this entry's byte count, rebuilt from the length series
    Int at = 0;
    for (Int i = 0; i < count; i++) {
        delta -= starts[i];
        length -= lengths[i];
        at += delta;
        table[i] = StrView(bytes + at, length);
    }
}
```

!timeops
====
emit: always
support:
```cpp
// Time natives (the Simse surface is the prelude file cppsrc/rtl/rtl.kt). Both are
// monotonic clocks - never going backwards - since an arbitrary fixed point:
// `simse_nowMillis` for logging, the finer `simse_nowMicros` for the instrumented
// profiler (`cppsrc/profiling`, and the emitted `profileApp.measure(...)` of a
// `--profile` build). It was cppsrc/rtl/timeops.hpp; the definitions are still
// cppsrc/rtl/native.cpp's, because they are the platform's business. `emit: always`
// because the profiler's runtime is emitted by the *compiler* rather than named by the
// program: a `--profile` build needs these two declarations whether or not the program
// ever asks for the time.
Int64 simse_nowMillis();
Int64 simse_nowMicros();
```

!listops
====
forward:
```cpp
#include <cstdint>
#include <type_traits>

// The List/Array/Str primitives behind the prelude (impl_specs/native-interop.md), moved
// out of cppsrc/rtl/listops.hpp. Index and range errors are unchecked, matching the
// language's no-exceptions policy: `removeAt`/`removeRange` with an out-of-range index is
// undefined behavior (specs/language-decisions.md).

// Appends `value` to the end of `self`. The value is a non-deduced context so a literal
// argument (e.g. a `const char[]`) converts to the element type instead of making `T`
// ambiguous.
template <class T>
void simse_list_append(List<T>& self, const std::type_identity_t<T>& value);

// The list literal's fallback (cppsrc/rtl/rtl.kt): the compiler turns
// `listOf<Str>("a", "b")` into the construction itself, so this runs only for a position
// with no destination slot.
template <class T>
List<T> simse_listOf(const List<T>* values);

template <class T>
void simse_list_removeAt(List<T>& self, Int index);
template <class T>
void simse_list_removeRange(List<T>& self, Int start, Int end);
template <class T>
Int simse_array_count(const Array<T>& self);
template <class T>
Array<T> simse_list_toArray(const List<T>& self);
template <class T>
List<T> simse_array_toList(const Array<T>& self);
template <class T>
Array<T> simse_arrayEmpty();

void simse_str_append(Str& self, Char value);
void simse_str_appendStr(Str& self, const Str& value);
void simse_str_appendStrPtr(Str& self, const Str* value);
void simse_str_reserve(Str& self, Int count);
Str simse_int_toString(Int self);
```
bodies:
```cpp
template <class T>
inline void simse_list_append(List<T>& self, const std::type_identity_t<T>& value) {
    self.push_back(value);
}

template <class T>
inline List<T> simse_listOf(const List<T>* values) {
    return *values;
}

// Removes the single element at `index`.
template <class T>
inline void simse_list_removeAt(List<T>& self, Int index) {
    self.erase(self.begin() + index);
}

// Removes the half-open range [start, end).
template <class T>
inline void simse_list_removeRange(List<T>& self, Int start, Int end) {
    self.erase(self.begin() + start, self.begin() + end);
}

// `Array<T>.count()`: the element count stored at the front of the block.
template <class T>
inline Int simse_array_count(const Array<T>& self) {
    return self.count();
}

// `List<T>.toArray()` (specs/built-in-types.md): copies the elements into one count-first
// block. Element copies are value copies, like every other copy in the language.
template <class T>
inline Array<T> simse_list_toArray(const List<T>& self) {
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

// `Array<T>.toList()`: the growable copy, which is how an element is added to an array.
template <class T>
inline List<T> simse_array_toList(const Array<T>& self) {
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
inline Array<T> simse_arrayEmpty() {
    return Array<T>();
}

// `Str.append(ch)`: `Str` has no single-character append, so this is `push_back`.
inline void simse_str_append(Str& self, Char value) {
    self.push_back(static_cast<char>(value));
}

// `Str.appendStr(text)`: appends in place, so an emitter accumulates output without
// `out = out + text` rebuilding the whole buffer on every line (which is quadratic).
inline void simse_str_appendStr(Str& self, const Str& value) {
    self.append(value);
}

// `Str.appendStrPtr(text)`: the same append for a text the caller only *borrows*, so
// nothing is copied on the way.
inline void simse_str_appendStrPtr(Str& self, const Str* value) {
    if (value != nullptr) self.append(*value);
}

// `Str.reserve(count)`: grows the buffer once, so a run of appends writes the text once
// instead of copying the accumulated prefix at every growth step. A *hint*, not a length.
inline void simse_str_reserve(Str& self, Int count) {
    self.reserve((Str::size_type) count);
}

// `Int.toString()`: the scalar-to-inline-string conversion (specs/memory-model.md).
inline Str simse_int_toString(Int self) {
    return std::to_string(self);
}
```

!dictops
====
forward:
```cpp
#include <algorithm>
#include <type_traits>
#include <utility>

// The Dictionary operations and the extra List helpers behind the prelude
// (impl_specs/native-interop.md), moved out of cppsrc/rtl/dictops.hpp. The key/value
// parameters are non-deduced (`std::type_identity_t`) so that a literal argument (e.g. a
// `const char[]` key or an integer value) converts to the element type instead of making
// the template argument ambiguous.
//
// Errors are unchecked, matching the dictionary's own semantics and the language's
// no-exceptions policy: `get`/`has` on a missing key behave as documented, while `remove`
// of an absent key is a no-op.

// `dictionaryOf<K, V>()`: the empty-dictionary construction.
template <class K, class V>
Dictionary<K, V> simse_dictionaryOf();

// `d.get(key)`: the value for `key`, or an empty `Opt` when absent.
template <class K, class V>
Opt<V> simse_dict_get(const Dictionary<K, V>& self, const std::type_identity_t<K>& key);

// `d.has(key)`: whether `key` is present.
template <class K, class V>
Bool simse_dict_has(const Dictionary<K, V>& self, const std::type_identity_t<K>& key);

// `d.insert(key, value)`: insert or replace.
template <class K, class V>
void simse_dict_insert(Dictionary<K, V>& self, const std::type_identity_t<K>& key,
                       const std::type_identity_t<V>& value);

// `d.remove(key)`: erase when present (a no-op otherwise).
template <class K, class V>
void simse_dict_remove(Dictionary<K, V>& self, const std::type_identity_t<K>& key);

// `d.size()`: the number of entries.
template <class K, class V>
Int simse_dict_size(const Dictionary<K, V>& self);

// `d.keys()`: the keys in the dictionary's iteration order (unspecified; sort for a
// deterministic order).
template <class K, class V>
List<K> simse_dict_keys(const Dictionary<K, V>& self);

// `d.values()`: the values in the dictionary's iteration order (unspecified).
template <class K, class V>
List<V> simse_dict_values(const Dictionary<K, V>& self);

// `d.clear()`: remove every entry.
template <class K, class V>
void simse_dict_clear(Dictionary<K, V>& self);

// `items.contains(value)`: linear membership test (`operator==` on elements).
template <class T>
Bool simse_list_contains(const List<T>& self, const std::type_identity_t<T>& value);

// `items.sort(less)`: in-place sort using the `(T, T) -> Bool` comparator. The comparator
// comes from a Simse lambda (a C++ lambda or Func), so it is a template parameter rather
// than a fixed type.
template <class T, class F>
void simse_list_sort(List<T>& self, F less);
```
bodies:
```cpp
// `dictionaryOf<K, V>()`: `Dictionary<K, V>` is a value type, so this default-constructs
// one.
template <class K, class V>
inline Dictionary<K, V> simse_dictionaryOf() {
    return Dictionary<K, V>();
}

template <class K, class V>
inline Opt<V> simse_dict_get(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    auto it = self.find(key);
    if (it == self.end()) return Opt<V>::none();
    return Opt<V>::some(it->second);
}

template <class K, class V>
inline Bool simse_dict_has(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    return self.find(key) != self.end();
}

template <class K, class V>
inline void simse_dict_insert(Dictionary<K, V>& self, const std::type_identity_t<K>& key,
                              const std::type_identity_t<V>& value) {
    self.insert_or_assign(key, value);
}

template <class K, class V>
inline void simse_dict_remove(Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    self.erase(key);
}

template <class K, class V>
inline Int simse_dict_size(const Dictionary<K, V>& self) {
    return (Int) self.size();
}

template <class K, class V>
inline List<K> simse_dict_keys(const Dictionary<K, V>& self) {
    List<K> out;
    out.reserve((Int) self.size());
    for (const auto& entry : self) out.push_back(entry.first);
    return out;
}

template <class K, class V>
inline List<V> simse_dict_values(const Dictionary<K, V>& self) {
    List<V> out;
    out.reserve((Int) self.size());
    for (const auto& entry : self) out.push_back(entry.second);
    return out;
}

template <class K, class V>
inline void simse_dict_clear(Dictionary<K, V>& self) {
    self.clear();
}

template <class T>
inline Bool simse_list_contains(const List<T>& self, const std::type_identity_t<T>& value) {
    for (const T& item : self) {
        if (item == value) return true;
    }
    return false;
}

template <class T, class F>
inline void simse_list_sort(List<T>& self, F less) {
    std::sort(self.begin(), self.end(), less);
}
```

!strops
====
forward:
```cpp
#include <charconv>
#include <cstddef>
#include <system_error>

// The string, character, numeric-conversion and min/max operations behind the prelude
// (impl_specs/native-interop.md, specs/built-in-types.md), moved out of
// cppsrc/rtl/strops.hpp.
//
// `Str` is the inline `SmString` (smstring.hpp): every size, length and index here is the
// language's `Int` (`int32_t`), including `Str::npos`, which is `-1`. Index/range errors
// are unchecked where the underlying operation is unchecked; the `Opt`-returning
// conversions never throw.

// `Str.charAt(index)`: the byte at `index` (unchecked; no bounds test).
Char simse_str_charAt(const Str& self, Int index);

// `Str.trim()` strips leading and trailing whitespace (space, tab, newline, CR).
Str simse_str_trim(const Str& self);

// `Str.split(separator)` splits on every occurrence. An empty separator returns the whole
// string as a single element. Two overloads: a separator string and a separator byte.
List<Str> simse_str_split(const Str& self, const Str& separator);
List<Str> simse_str_split(const Str& self, Char separator);

// ASCII/byte case folding (the string type is a byte string).
Str simse_str_toUpper(const Str& self);
Str simse_str_toLower(const Str& self);

// `Str.find(sub)` returns the first index of `sub`, or -1 when absent (the language's
// spelling of C++ `npos`).
Int simse_str_find(const Str& self, const Str& sub);

// `Str.lastIndexOf(sub)` returns the last index of `sub`, or -1 when absent.
Int simse_str_lastIndexOf(const Str& self, const Str& sub);

// `Str.substr(start, len)` clamps `start` to [0, size]; `len` may run past the end.
Str simse_str_substr(const Str& self, Int start, Int len);

Bool simse_str_startsWith(const Str& self, const Str& prefix);
Bool simse_str_endsWith(const Str& self, const Str& suffix);

// `Str.replace(from, to)` replaces every occurrence of `from` with `to`.
Str simse_str_replace(const Str& self, const Str& from, const Str& to);

// `Str.toInt()`/`Str.toFloat()` parse the whole string; failure (or a non-empty trailing
// remainder) yields `Opt.none()`. No exceptions: `std::from_chars` reports errors through
// its return value.
Opt<Int> simse_str_toInt(const Str& self);
Opt<Float64> simse_str_toFloat(const Str& self);

// `Char` is a signed 8-bit integer; the checks are byte-range tests so they do not depend
// on the C locale. Space, tab, newline and carriage return count as space; form feed and
// vertical tab do not.
Bool simse_char_isDigit(Char self);
Bool simse_char_isAlpha(Char self);
Bool simse_char_isAlphaOrDigit(Char self);
Bool simse_char_isSpace(Char self);

// Numeric conversions. `Char` is an 8-bit integer, so it stringifies as a number.
template <class T>
Str simse_num_toString(const T& self);
Str simse_char_toString(Char self);
Str simse_bool_toString(Bool self);
```
bodies:
```cpp
// `Str.isEmpty()` is the prelude's own body (cppsrc/rtl/rtl.kt), not a resource: `size()`
// is the built-in it needs. This one is the shared space test the `Char` predicate below
// uses too.
inline Bool simse_str_isSpaceByte(Char ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
}

inline Char simse_str_charAt(const Str& self, Int index) {
    return (Char) self[index];
}

inline Str simse_str_trim(const Str& self) {
    Int begin = 0;
    Int end = self.size();
    while (begin < end && simse_str_isSpaceByte((Char) self[begin])) begin++;
    while (end > begin && simse_str_isSpaceByte((Char) self[end - 1])) end--;
    return self.substr(begin, end - begin);
}

inline List<Str> simse_str_split(const Str& self, const Str& separator) {
    List<Str> parts;
    if (separator.empty()) {
        parts.push_back(self);
        return parts;
    }
    Int pos = 0;
    while (true) {
        Int found = self.find(separator, pos);
        if (found == Str::npos) {
            parts.push_back(self.substr(pos));
            break;
        }
        parts.push_back(self.substr(pos, found - pos));
        pos = found + separator.size();
    }
    return parts;
}

// How many bytes of `self` are `ch`: what the byte-separator split reserves up front.
inline Int simse_count_char_in_str(const Str* self, char ch) {
    Int count = 0;
    const char* data = self->data();
    Int len = self->size();
    for (Int i = 0; i < len; i++) {
        if (data[i] == ch) {
            count++;
        }
    }
    return count;
}

inline List<Str> simse_str_split(const Str& self, Char separator) {
    List<Str> parts;
    parts.reserve(simse_count_char_in_str(&self, separator));
    Int pos = 0;
    while (true) {
        Int found = self.find(separator, pos);
        if (found == Str::npos) {
            parts.push_back(self.substr(pos));
            break;
        }
        parts.push_back(self.substr(pos, found - pos));
        pos = found + 1;
    }
    return parts;
}

inline Str simse_str_toUpper(const Str& self) {
    Str result = self;
    for (char& ch : result) {
        if (ch >= 'a' && ch <= 'z') ch = (char) (ch - 'a' + 'A');
    }
    return result;
}

inline Str simse_str_toLower(const Str& self) {
    Str result = self;
    for (char& ch : result) {
        if (ch >= 'A' && ch <= 'Z') ch = (char) (ch - 'A' + 'a');
    }
    return result;
}

inline Int simse_str_find(const Str& self, const Str& sub) {
    Int found = self.find(sub);
    return found == Str::npos ? -1 : found;
}

inline Int simse_str_lastIndexOf(const Str& self, const Str& sub) {
    Int found = self.rfind(sub);
    return found == Str::npos ? -1 : found;
}

inline Str simse_str_substr(const Str& self, Int start, Int len) {
    if (start < 0) start = 0;
    if (start > self.size()) start = self.size();
    Str result = self.substr(start);
    if (len >= 0 && len < result.size()) result.resize(len);
    return result;
}

inline Bool simse_str_startsWith(const Str& self, const Str& prefix) {
    return prefix.size() <= self.size() && self.compare(0, prefix.size(), prefix) == 0;
}

inline Bool simse_str_endsWith(const Str& self, const Str& suffix) {
    return suffix.size() <= self.size()
           && self.compare(self.size() - suffix.size(), suffix.size(), suffix) == 0;
}

inline Str simse_str_replace(const Str& self, const Str& from, const Str& to) {
    if (from.empty()) return self;
    Str result;
    Int pos = 0;
    while (true) {
        Int found = self.find(from, pos);
        if (found == Str::npos) {
            result.append(self, pos, Str::npos);
            break;
        }
        result.append(self, pos, found - pos);
        result += to;
        pos = found + from.size();
    }
    return result;
}

inline Opt<Int> simse_str_toInt(const Str& self) {
    if (self.empty()) return Opt<Int>::none();
    Int value = 0;
    const char* begin = self.data();
    const char* end = begin + self.size();
    std::from_chars_result parsed = std::from_chars(begin, end, value);
    if (parsed.ec != std::errc() || parsed.ptr != end) return Opt<Int>::none();
    return Opt<Int>::some(value);
}

inline Opt<Float64> simse_str_toFloat(const Str& self) {
    if (self.empty()) return Opt<Float64>::none();
    Float64 value = 0;
    const char* begin = self.data();
    const char* end = begin + self.size();
    std::from_chars_result parsed = std::from_chars(begin, end, value);
    if (parsed.ec != std::errc() || parsed.ptr != end) return Opt<Float64>::none();
    return Opt<Float64>::some(value);
}

inline Bool simse_char_isDigit(Char self) {
    return self >= '0' && self <= '9';
}

inline Bool simse_char_isAlpha(Char self) {
    return (self >= 'a' && self <= 'z') || (self >= 'A' && self <= 'Z');
}

inline Bool simse_char_isAlphaOrDigit(Char self) {
    return simse_char_isAlpha(self) || simse_char_isDigit(self);
}

inline Bool simse_char_isSpace(Char self) {
    return simse_str_isSpaceByte(self);
}

template <class T>
inline Str simse_num_toString(const T& self) {
    return std::to_string(self);
}

inline Str simse_char_toString(Char self) {
    return std::to_string((int) self);
}

inline Str simse_bool_toString(Bool self) {
    return self ? "true" : "false";
}
```

!spanOf
====
symbol: simse_spanOf
forward:
```cpp
// `spanOf(items)`: a span over a list's elements, generated (`cppsrc/rtl/_res.md`).
template <class T>
Span<T> simse_spanOf(List<T>* items);
```
bodies:
```cpp
// `spanOf(items)`: a span over a list's elements. It borrows the list - the list has
// to outlive the span - and does not copy it (`&items` would box a copy instead).
template <class T>
inline Span<T> simse_spanOf(List<T>* items) {
    return Span<T>(items->data(), items->size());
}
```

Test fixture - the collision decoy
====
Not part of the RTL. This section defines the same C++ symbol as `spanOf` above, which
is what `stress/smgen-res-collision` needs to pin the documented last-write-wins rule
(impl_specs/generators.md, "Sections and named entries"). A section's entry is keyed by
the symbol, so a second generator that adds the same symbol replaces the first one's
text - here with a span whose length is -1.

!spanOfEmpty
====
symbol: simse_spanOf
bodies:
```cpp
// The collision fixture (the `spanOfEmpty` section of cppsrc/rtl/_res.md): the same
// symbol as `spanOf`, so whichever declaration the emitter reaches last wins.
template <class T>
inline Span<T> simse_spanOf(List<T>* items) {
    return Span<T>(nullptr, -1);
}
```
