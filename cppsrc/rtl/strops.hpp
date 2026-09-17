#pragma once

#include <charconv>
#include <cstddef>
#include <system_error>

#include "containers.hpp"
#include "optional.hpp"
#include "types.hpp"

// Native implementations behind the Simse prelude `cppsrc/rtl/rtl.simse`
// (impl_specs/native-interop.md, specs/built-in-types.md). These are the string,
// character, numeric-conversion, and min/max operations the language exposes as
// `native("symbol") fun name(...)` extensions.
//
// `Str` is the inline `SmString` (smstring.hpp): every size, length and index in
// this file is the language's `Int` (`int32_t`), including `Str::npos`, which is
// `-1`. Index/range errors are unchecked where the underlying operation is
// unchecked; the `Opt`-returning conversions never throw.

// ---- `Str` library --------------------------------------------------------

// `Str.charAt(index)` returns the byte at `index` (unchecked; no bounds test).
inline Char simse_str_charAt(const Str& self, Int index) {
    return (Char) self[index];
}

// `Str.isEmpty()` is the prelude's own body (cppsrc/rtl/rtl.kt), not a native: `size()`
// is the built-in it needs.
inline Bool simse_str_isSpaceByte(Char ch) {
    return ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
}

// `Str.trim()` strips leading and trailing whitespace (space, tab, newline, CR).
inline Str simse_str_trim(const Str& self) {
    Int begin = 0;
    Int end = self.size();
    while (begin < end && simse_str_isSpaceByte((Char) self[begin])) begin++;
    while (end > begin && simse_str_isSpaceByte((Char) self[end - 1])) end--;
    return self.substr(begin, end - begin);
}

// `Str.split(separator)` splits on every occurrence. An empty separator returns
// the whole string as a single element.
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

// ASCII/byte case folding (the string type is a byte string).
inline Str simse_str_toUpper(const Str& self) {
    Str result = self;
    for (char& ch: result) {
        if (ch >= 'a' && ch <= 'z') ch = (char) (ch - 'a' + 'A');
    }
    return result;
}

inline Str simse_str_toLower(const Str& self) {
    Str result = self;
    for (char& ch: result) {
        if (ch >= 'A' && ch <= 'Z') ch = (char) (ch - 'A' + 'a');
    }
    return result;
}

// `Str.find(sub)` returns the first index of `sub`, or -1 when absent (the
// language's spelling of C++ `npos`).
inline Int simse_str_find(const Str& self, const Str& sub) {
    Int found = self.find(sub);
    return found == Str::npos ? -1 : found;
}

// `Str.lastIndexOf(sub)` returns the last index of `sub`, or -1 when absent.
inline Int simse_str_lastIndexOf(const Str& self, const Str& sub) {
    Int found = self.rfind(sub);
    return found == Str::npos ? -1 : found;
}

// `Str.substr(start, len)` clamps `start` to [0, size]; `len` may run past the
// end, matching `std::string::substr` with a fitted count.
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

// `Str.replace(from, to)` replaces every occurrence of `from` with `to`.
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

// `Str.toInt()` parses the whole string; failure (or a non-empty trailing
// remainder) yields `Opt.none()`. No exceptions: `std::from_chars` reports
// errors through its return value.
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

// ---- `Char` predicates ----------------------------------------------------
// `Char` is a signed 8-bit integer; the checks are byte-range tests so they do
// not depend on the C locale.

inline Bool simse_char_isDigit(Char self) {
    return self >= '0' && self <= '9';
}

inline Bool simse_char_isAlpha(Char self) {
    return (self >= 'a' && self <= 'z') || (self >= 'A' && self <= 'Z');
}

inline Bool simse_char_isAlphaOrDigit(Char self) {
    return simse_char_isAlpha(self) || simse_char_isDigit(self);
}

// Space, tab, newline, and carriage return. Form feed and vertical tab are not
// included; the language treats those as ordinary bytes.
inline Bool simse_char_isSpace(Char self) {
    return simse_str_isSpaceByte(self);
}

// ---- numeric conversions --------------------------------------------------

template <class T>
inline Str simse_num_toString(const T& self) {
    return std::to_string(self);
}

// `Char` is an 8-bit integer, so it stringifies as a number.
inline Str simse_char_toString(Char self) {
    return std::to_string((int) self);
}

inline Str simse_bool_toString(Bool self) {
    return self ? "true" : "false";
}
