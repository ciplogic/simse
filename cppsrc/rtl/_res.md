Generated C++
====
The RTL's hand-written C++ that used to live in headers (impl_specs/generators.md) - one
section per header it came from. The *prelude's* C++ is here; a module's own C++ lives in the
module (cppsrc/modules/io/_res.md, for instance), since the tree's own `_res.md` files are the
first the generator lookup reads. Two sections carry the `emit` marker (its value is `always`),
so the compiler emits them for every program - `strtable`, the string table's decoder, and
`timeops`, the clocks, because the profiler's runtime is emitted by the compiler rather than
named by the program. Three are
*shared*, one header's worth of functions
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
#include <chrono>

// Time natives (the Simse surface is the prelude file cppsrc/rtl/rtl.kt). Both are
// monotonic clocks - never going backwards - since an arbitrary fixed point:
// `simse_nowMillis` for logging, the finer `simse_nowMicros` for the instrumented
// profiler (`cppsrc/profiling`, and the emitted `profileApp.measure(...)` of a
// `--profile` build). It was cppsrc/rtl/timeops.hpp, and its definitions were the last
// thing left in cppsrc/rtl/native.cpp - they are this section's now, so the clock is
// emitted into the program like any other prelude body and there is nothing to link.
// `emit: always`
// because the profiler's runtime is emitted by the *compiler* rather than named by
// the program: a `--profile` build needs these two declarations whether or not the program
// ever asks for the time.
Int64 simse_nowMillis();
Int64 simse_nowMicros();
```
bodies:
```cpp
// The two monotonic clocks. `steady_clock` is the one clock the standard library
// promises cannot go backwards, which is what makes a duration between two readings
// meaningful (`impl_specs/profiling.md`).
Int64 simse_nowMillis() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return (Int64) std::chrono::duration_cast<std::chrono::milliseconds>(now).count();
}

Int64 simse_nowMicros() {
    const auto now = std::chrono::steady_clock::now().time_since_epoch();
    return (Int64) std::chrono::duration_cast<std::chrono::microseconds>(now).count();
}
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

// `d.getPtr(key)`: the value's place in the dictionary, or `null` when the key is
// absent - the read that copies nothing (`get` copies the value out, `has` is this with
// the pointer tested). The place is the dictionary's own storage, so it is valid until
// the next `insert`/`remove`/`clear` on that dictionary.
template <class K, class V>
V* simse_dict_getPtr(const Dictionary<K, V>& self, const std::type_identity_t<K>& key);

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
inline V* simse_dict_getPtr(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    return self.valuePtr(key);
}

template <class K, class V>
inline Opt<V> simse_dict_get(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    const V* found = simse_dict_getPtr(self, key);
    if (found == nullptr) return Opt<V>::none();
    return Opt<V>::some(*found);
}

template <class K, class V>
inline Bool simse_dict_has(const Dictionary<K, V>& self, const std::type_identity_t<K>& key) {
    return simse_dict_getPtr(self, key) != nullptr;
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

!resfmt
====
forward:
```cpp
// The resource format's two byte-level helpers (specs/resources.md, "Markers"): the bytes a
// hex dump stands for, and those bytes spelled as the C++ literal the pool holds them as.
//
// They are reached by the declarations in `cppsrc/resources/Resources.kt`, which is a module
// of the *compiler* - a program neither sees them nor emits them, which is why they are a
// section of their own rather than part of `strops`: a shared section is emitted whole, so
// any program that reached `strops` would carry these too.
Str simse_resHexToBytes(const Str& self);
Str simse_resQuoteBinary(const Str& self);
```
bodies:
```cpp
// The bytes a `*`-marked value's hex stands for: every pair of hex digits is one byte, in
// order, so the value is a byte string like any other - a `Str` with a length, which may hold
// a `\0` in the middle.
//
// The hex is **lower case** and whitespace (spaces, tabs, newlines) is not part of it, so a
// dump may be wrapped however the file likes; decoding stops at the first character that is
// neither, and a last digit left unpaired is dropped. Stopping rather than skipping is
// deliberate: a value that is not lower-case hex decodes to nothing rather than to half its
// bytes, which is the loudest failure this format can give - it validates nothing anywhere, so
// the program's own output is where a bad dump shows up.
inline Str simse_resHexToBytes(const Str& self) {
    Str out;
    out.reserve(self.size() / 2);
    Int high = -1;
    for (Char ch : self) {
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') continue;
        Int digit = -1;
        if (ch >= '0' && ch <= '9') digit = ch - '0';
        else if (ch >= 'a' && ch <= 'f') digit = ch - 'a' + 10;
        if (digit < 0) break;
        if (high < 0) {
            high = digit;
        } else {
            out.push_back((char) (high * 16 + digit));
            high = -1;
        }
    }
    return out;
}

// Bytes as the C++ literal the pool holds them as: printable bytes stand for themselves, and
// every other byte is an octal escape with all three digits written, so that a following
// character which happens to be an octal digit cannot run the escape on (`\x` would: it takes
// every hex digit that follows it). `cgLiteralByteLength` counts an octal escape as the one
// byte it is, which is what keeps the pool and its length index in step - and it is what makes
// a `\0` in the middle of a value an ordinary byte of the pool.
inline Str simse_resQuoteBinary(const Str& self) {
    Str out = "\"";
    for (Char ch : self) {
        Int byte = (Int) (unsigned char) ch;
        if (ch == '\\') {
            out.append("\\\\");
        } else if (ch == '\"') {
            out.append("\\\"");
        } else if (byte >= 0x20 && byte <= 0x7e) {
            out.push_back((char) byte);
        } else {
            out.push_back('\\');
            out.push_back((char) ('0' + ((byte >> 6) & 7)));
            out.push_back((char) ('0' + ((byte >> 3) & 7)));
            out.push_back((char) ('0' + (byte & 7)));
        }
    }
    out.push_back('"');
    return out;
}
```

!strview
====
forward:
```cpp
// The operations a view is read through (specs/built-in-types.md, "Views"; the
// declarations are cppsrc/rtl/StrView.kt). The *type* and the *literal interop* stay in
// cppsrc/rtl/strview.hpp: `using StrView = Span<Char>`, the comparison operators, `+`,
// `<<` and the `Str` conversions are reached by C++ overload resolution at a literal
// site rather than by a prelude declaration, so no declaration could reach a section for
// them - while these eleven are named, one symbol each, and a program that calls one
// pays for this text (`sourcegen/ResGen.kt`).
//
// `StrView` *is* a `Span<Char>`, so `self.len`, `self[i]` and `self.slice(...)` below are
// the span's own members (cppsrc/rtl/span.hpp) - and `at` is *not* an operation of its
// own: the span's member serves it (`cppsrc/rtl/StrView.kt`), as `atPtr` is the
// language's (cppsrc/rtl/Span.kt).
Int simse_strView_size(StrView self);
Bool simse_strView_isEmpty(StrView self);
StrView simse_strView_slice(StrView self, Int start);
StrView simse_strView_slice(StrView self, Int start, Int count);
Char simse_strView_charAt(StrView self, Int index);
Bool simse_strView_startsWith(StrView self, const Str& text);
Bool simse_strView_startsWithPtr(StrView self, const Str* text, Int length);
Int simse_strView_find(StrView self, const Str& sub);
Int simse_strView_indexOf(StrView self, const Str& sub);
Str simse_strView_substr(StrView self, Int from, Int count);
Str simse_strView_toString(StrView self);
StrView simse_spanOfStr(Str* text);
```
bodies:
```cpp
inline Int simse_strView_size(StrView self) {
    return self.len;
}

inline Bool simse_strView_isEmpty(StrView self) {
    return self.len <= 0;
}

// `view.slice(start)`: from `start` to the end (C# `Slice(int)`).
inline StrView simse_strView_slice(StrView self, Int start) {
    return self.slice(start);
}

// `view.slice(start, count)`: `count` bytes from `start` (C# `Slice(int, int)`).
inline StrView simse_strView_slice(StrView self, Int start, Int count) {
    return self.slice(start, count);
}

inline Char simse_strView_charAt(StrView self, Int index) {
    return self[index];
}

// True when the view begins with `text`.
inline Bool simse_strView_startsWith(StrView self, const Str& text) {
    const Int count = text.size();
    if (count > self.len) return false;
    for (Int i = 0; i < count; i++) {
        if ((char) self[i] != text[i]) return false;
    }
    return true;
}

// `startsWithPtr(text, length)`: the same comparison against text this view does not
// own, reached by raw pointer and with its length already known. `startsWith` would
// copy the `Str` first, which is what a table lookup cannot afford; the first byte is
// the caller's cheap test, this does the rest.
inline Bool simse_strView_startsWithPtr(StrView self, const Str* text, Int length) {
    if (length > self.len) return false;
    for (Int i = 1; i < length; i++) {
        if ((char) self[i] != (*text)[i]) return false;
    }
    return true;
}

// `find(sub)`: the index of the first occurrence of `sub` in the bytes, or -1. The
// bytes are compared in place: nothing is copied.
inline Int simse_strView_find(StrView self, const Str& sub) {
    const Int needle = sub.size();
    if (needle == 0) return 0;
    if (needle > self.len) return -1;
    for (Int i = 0; i + needle <= self.len; i++) {
        Int j = 0;
        while (j < needle && (char) self[i + j] == sub[j]) j++;
        if (j == needle) return i;
    }
    return -1;
}

// `indexOf` is the other spelling of `find`.
inline Int simse_strView_indexOf(StrView self, const Str& sub) {
    return simse_strView_find(self, sub);
}

// The owned copy of `count` bytes from `from`, with `from` clamped to [0, size] and
// `count` allowed to run to the end, like `Str.substr`.
inline Str simse_strView_substr(StrView self, Int from, Int count) {
    const Int len = self.len;
    Int begin = from < 0 ? 0 : from;
    if (begin > len) begin = len;
    Int end = count < 0 ? begin : begin + count;
    if (end > len) end = len;
    Str result;
    if (end > begin) {
        result.resize(end - begin);
        std::memcpy(result.data(), self.ptr + begin, (std::size_t) (end - begin));
    }
    return result;
}

// The owned copy of the whole view, as a `Str` (the language's `toString()`
// convention, like `Int.toString()`).
inline Str simse_strView_toString(StrView self) {
    return simse_strView_substr(self, 0, self.len);
}

// `spanOfStr(text)`: a view over a string's bytes. It borrows the string - the string
// has to outlive the view - and does not copy it (`&text` would box a copy instead).
// `Str` is a `char` buffer on the C++ side and the language's `Char` is a signed byte,
// hence the cast.
inline StrView simse_spanOfStr(Str* text) {
    return StrView(reinterpret_cast<Char*>(text->data()), text->size());
}
```

!resources
====
forward:
```cpp
// `Resources.entries()` (cppsrc/rtl/resources.kt): a borrowed view of the entries a
// program carries. The storage stays in cppsrc/rtl/resources.hpp - `install`, which the
// table the emitter writes calls before `main`, and the function-local static it fills -
// because both are reached before any declaration is; this is the one accessor over it.
Span<ResourceEntry> simse_resources_entries();
```
bodies:
```cpp
inline Span<ResourceEntry> simse_resources_entries() {
    List<ResourceEntry>& entries = simse_resourcesStorage();
    return Span<ResourceEntry>(entries.data(), entries.size());
}
```

!tasks
====
forward:
```cpp
#include <condition_variable>
#include <fstream>
#include <mutex>
#include <thread>
#include <vector>

// The work pool (impl_specs/async.md, "The runtime"): `ioThreads` worker threads behind two
// queues. The thread that called `simse_tasksStart` is the *loop* - a program's main logic -
// and the workers are the io side; they meet only at the queues. Only *values* cross a queue,
// never a counted handle, and a reference is transferred under the queue's own lock, which is
// what lets the language's reference counts stay non-atomic: one owner at a time. A worker
// touches its job and the queues and nothing else, which is what makes it pool-schedulable.
//
void simse_tasksStart(Int ioThreads);
void simse_tasksStop();
void simse_tasksSubmitRead(Int slot, const Str& path);
void simse_tasksJoin(Int count);
Str simse_tasksText(Int slot);
```
bodies:
```cpp
namespace simse_tasks {

struct Job {
    Int slot;
    Str path;
};

// Heap-allocated and never freed: a program that never stops the pool must still exit quietly,
// and a static's destructor racing a worker's wait would not.
struct Pool {
    std::mutex workMutex;
    std::condition_variable workReady;
    std::vector<Job> work;
    bool closed = false;

    std::mutex doneMutex;
    std::condition_variable doneReady;
    std::vector<Str> texts;
    Int completed = 0;

    std::vector<std::thread> workers;
};

Pool& storage() {
    static Pool* shared = new Pool();
    return *shared;
}

void worker(Pool* shared) {
    for (;;) {
        Job job;
        {
            std::unique_lock<std::mutex> lock(shared->workMutex);
            shared->workReady.wait(lock, [shared] { return shared->closed || !shared->work.empty(); });
            if (shared->work.empty()) return;
            // Any order will do: the join counts completions, it does not order them.
            job = shared->work.back();
            shared->work.pop_back();
        }
        // The job's file, read here rather than through `fileio`: that section is the `io`
        // module's C++ now (cppsrc/modules/io/_res.md), a layer this prelude section must not
        // reach into. Empty when the file cannot be read, as `fileio`'s reader answers.
        Str text;
        {
            std::ifstream input(simse_toStdString(job.path), std::ios::binary);
            if (input) {
                input.seekg(0, std::ios::end);
                const std::streamoff size = input.tellg();
                input.seekg(0, std::ios::beg);
                text.resize((Int) size);
                input.read(text.data(), size);
            }
        }
        {
            std::lock_guard<std::mutex> lock(shared->doneMutex);
            if (job.slot >= 0 && (std::size_t) job.slot < shared->texts.size()) {
                shared->texts[(std::size_t) job.slot] = text;
            }
            shared->completed = shared->completed + 1;
        }
        shared->doneReady.notify_all();
    }
}

}  // namespace simse_tasks

void simse_tasksStart(Int ioThreads) {
    simse_tasks::Pool& shared = simse_tasks::storage();
    if (!shared.workers.empty()) {
        return;
    }
    if (ioThreads < 1) {
        ioThreads = 1;
    }
    for (Int i = 0; i < ioThreads; i = i + 1) {
        shared.workers.push_back(std::thread(simse_tasks::worker, &shared));
    }
}

void simse_tasksStop() {
    simse_tasks::Pool& shared = simse_tasks::storage();
    {
        std::lock_guard<std::mutex> lock(shared.workMutex);
        shared.closed = true;
    }
    shared.workReady.notify_all();
    for (Int i = 0; i < (Int) shared.workers.size(); i = i + 1) {
        std::thread& thread = shared.workers[(std::size_t) i];
        if (thread.joinable()) {
            thread.join();
        }
    }
    shared.workers.clear();
}

void simse_tasksSubmitRead(Int slot, const Str& path) {
    simse_tasks::Pool& shared = simse_tasks::storage();
    // The two locks are never held at once, so there is no order to get wrong.
    {
        std::lock_guard<std::mutex> lock(shared.doneMutex);
        if (slot >= 0 && (std::size_t) slot >= shared.texts.size()) {
            shared.texts.resize((std::size_t) slot + 1);
        }
    }
    {
        std::lock_guard<std::mutex> lock(shared.workMutex);
        shared.work.push_back(simse_tasks::Job{slot, path});
    }
    shared.workReady.notify_one();
}

// The structural join: wait until `count` jobs have settled, whatever order they finished in.
void simse_tasksJoin(Int count) {
    simse_tasks::Pool& shared = simse_tasks::storage();
    std::unique_lock<std::mutex> lock(shared.doneMutex);
    shared.doneReady.wait(lock, [&shared, count] { return shared.completed >= count; });
}

Str simse_tasksText(Int slot) {
    simse_tasks::Pool& shared = simse_tasks::storage();
    std::lock_guard<std::mutex> lock(shared.doneMutex);
    if (slot < 0 || (std::size_t) slot >= shared.texts.size()) {
        return Str();
    }
    return shared.texts[(std::size_t) slot];
}
```

