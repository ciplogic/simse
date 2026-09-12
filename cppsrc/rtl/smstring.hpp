#pragma once

#include <cstring>
#include <functional>
#include <istream>
#include <ostream>
#include <string>

#include "strsmallvector.hpp"

// SmString is the language's inline byte string (specs/built-in-types.md,
// specs/containers.md): a NUL-terminated `SmallVector<kStrInlineCapacity, Char>`.
// The inline buffer holds at most `kStrInlineCapacity - 1` characters plus the
// terminating NUL; longer strings spill to the heap, and `data()` is always a
// valid C string (the reserved NUL is not part of `size()`). The buffer itself is
// `StrSmallVector`, the char-specialized form of that vector, which owns the
// capacity constant (strsmallvector.hpp).
//
// `Str` is SmString unless SIMSE_STR_STD_STRING is defined, in which case `Str`
// is `std::string` exactly as before (impl_specs/rtl-abi.md). The
// `simse_toStdString` / `simse_fromStdString` helpers exist for native code
// that has to talk to the standard library (fopen, std::filesystem, streams):
// with `Str = std::string` they are trivial copies, so the same native code
// compiles in both configurations.
//
// The public surface mirrors std::string closely enough that the compiler's
// existing string code compiles against either type.
SIMSE_PACK_PUSH
class SmString {
public:
    using value_type = char;
    using size_type = std::size_t;
    using iterator = char*;
    using const_iterator = const char*;
    using reference = char&;
    using const_reference = const char&;

    static constexpr size_type npos = size_type(-1);
    // The layout lives in StrSmallVector (strsmallvector.hpp); these only spell it
    // in `size_type`s for the std::string-shaped surface.
    static constexpr size_type inlineCapacity = (size_type) StrSmallVector::inlineCapacity;
    static constexpr size_type maxInlineLen = inlineCapacity - 1;

    // ---- construction -----------------------------------------------------

    // The buffer starts out as the empty string (its NUL is part of the stored
    // length), so there is nothing to do here.
    constexpr SmString() = default;

    // The literal/`const char*` case, which is the hot one (`Str s = "..."`,
    // `f(x, "literal")`): `strlen` proves the source carries its NUL.
    constexpr SmString(const char* text) {
        size_type count = text == nullptr ? 0 : std::char_traits<char>::length(text);
        _data.assign(text == nullptr ? "" : text, (Int) count);
    }

    // A window that is not necessarily terminated at `count`.
    constexpr SmString(const char* text, size_type count) {
        _data.assignSubstring(text, (Int) count);
    }

    constexpr SmString(const std::string& text) { _data.assign(text.data(), (Int) text.size()); }

    // `Str(count, value)`: a repeated byte (and the std::string-style fill ctor).
    constexpr SmString(size_type count, char value) {
        _data.resize((Int) count);
        if (count > 0) std::char_traits<char>::assign(_data.data(), count, value);
    }

    constexpr SmString(const SmString& other) { _data.assign(other.data(), (Int) other.size()); }

    SmString(SmString&& other) noexcept {
        _data = std::move(other._data);
        other._data.clear();
    }

    SmString& operator=(const SmString& other) {
        if (this != &other) _data.assign(other.data(), (Int) other.size());
        return *this;
    }

    SmString& operator=(SmString&& other) noexcept {
        if (this != &other) {
            _data = std::move(other._data);
            other._data.clear();
        }
        return *this;
    }

    constexpr SmString& operator=(const char* text) {
        size_type count = text == nullptr ? 0 : std::char_traits<char>::length(text);
        _data.assign(text == nullptr ? "" : text, (Int) count);
        return *this;
    }

    SmString& operator=(const std::string& text) {
        _data.assign(text.data(), (Int) text.size());
        return *this;
    }

    SmString& operator=(char value) {
        _data.assignSubstring(&value, 1);
        return *this;
    }

    ~SmString() = default;

    // ---- size and capacity ------------------------------------------------

    constexpr size_type size() const { return (size_type) _data.size(); }
    constexpr size_type length() const { return (size_type) _data.size(); }
    constexpr Bool empty() const { return _data.size() == 0; }
    size_type capacity() const { return (size_type) _data.capacity(); }

    constexpr void reserve(size_type count) { _data.reserve((Int) count + 1); }
    constexpr void clear() { _data.clear(); }

    // ---- element access ---------------------------------------------------

    constexpr char& operator[](size_type index) { return _data[(Int) index]; }
    constexpr const char& operator[](size_type index) const { return _data[(Int) index]; }

    // `at` checks the bound (std::string throws; the runtime has no exceptions,
    // so an out-of-range `at` aborts instead of reading past the end).
    char& at(size_type index) { return _data.at((Int) index); }
    const char& at(size_type index) const { return _data.at((Int) index); }

    char& front() { return _data.front(); }
    const char& front() const { return _data.front(); }
    char& back() { return _data.back(); }
    const char& back() const { return _data.back(); }

    // Mutable, NUL-terminated buffer (std::string::data in C++17).
    constexpr char* data() { return _data.data(); }
    constexpr const char* data() const { return _data.data(); }
    constexpr const char* c_str() const { return _data.data(); }

    iterator begin() { return _data.begin(); }
    iterator end() { return _data.end(); }
    const_iterator begin() const { return _data.begin(); }
    const_iterator end() const { return _data.end(); }
    const_iterator cbegin() const { return _data.cbegin(); }
    const_iterator cend() const { return _data.cend(); }

    // ---- modifiers --------------------------------------------------------

    constexpr void push_back(char value) { _data.push_back(value); }

    constexpr void pop_back() { _data.pop_back(); }

    SmString& append(const SmString& text) { return append(text.data(), text.size()); }
    SmString& append(const SmString& text, size_type pos, size_type count) {
        return append(text.data() + clampPos(text.size(), pos), fitted(text.size() - pos, count));
    }
    SmString& append(const char* text) {
        size_type count = text == nullptr ? 0 : std::char_traits<char>::length(text);
        if (count > 0) _data.append(text == nullptr ? "" : text, (Int) count);
        return *this;
    }
    SmString& append(const char* text, size_type count) {
        if (text != nullptr && count > 0) _data.appendSubstring(text, (Int) count);
        return *this;
    }
    SmString& append(size_type count, char value) {
        if (count > 0) {
            size_type from = size();
            _data.resize((Int) (from + count));
            std::char_traits<char>::assign(_data.data() + from, count, value);
        }
        return *this;
    }

    SmString& operator+=(const SmString& text) { return append(text); }
    SmString& operator+=(const char* text) { return append(text); }
    SmString& operator+=(const std::string& text) { return append(text.data(), text.size()); }
    SmString& operator+=(char value) {
        push_back(value);
        return *this;
    }
    // `resize` pads with `value` (default NUL), like std::string. The NUL after
    // the text is written by the buffer's `resize`.
    constexpr void resize(size_type count, char value = '\0') {
        if (count < size()) {
            _data.resize((Int) count);
        } else if (count > size()) {
            size_type from = size();
            _data.resize((Int) count);
            std::char_traits<char>::assign(_data.data() + from, count - from, value);
        }
    }

    SmString& insert(size_type pos, const SmString& text) {
        pos = clampPos(size(), pos);
        SmString tail(*this);
        tail.erase(0, pos);
        resize(pos);
        append(text);
        append(tail);
        return *this;
    }

    SmString& erase(size_type pos = 0, size_type count = npos) {
        size_type from = clampPos(size(), pos);
        size_type removed = fitted(size() - from, count);
        size_type tail = size() - (from + removed);
        if (tail > 0) std::memmove(_data.data() + from, _data.data() + from + removed, tail);
        _data.resize((Int) (from + tail));
        return *this;
    }

    // `replace(pos, count, text)`: the middle range becomes `text`.
    SmString& replace(size_type pos, size_type count, const SmString& text) {
        size_type from = clampPos(size(), pos);
        size_type removed = fitted(size() - from, count);
        SmString tail(substr(from + removed));
        _data.resize((Int) from);
        append(text);
        append(tail);
        return *this;
    }

    void swap(SmString& other) {
        _data.swap(other._data);
    }

    // ---- operations -------------------------------------------------------

    SmString substr(size_type pos = 0, size_type count = npos) const {
        size_type from = clampPos(size(), pos);
        size_type len = fitted(size() - from, count);
        SmString result;
        result._data.assignSubstring(data() + from, (Int) len);
        return result;
    }

    constexpr size_type find(const SmString& text, size_type pos = 0) const {
        return find(text.data(), pos, text.size());
    }
    constexpr size_type find(const char* text, size_type pos = 0) const {
        return find(text, pos, text == nullptr ? 0 : std::char_traits<char>::length(text));
    }
    constexpr size_type find(char value, size_type pos = 0) const {
        if (pos > size()) return npos;
        const char* found = std::char_traits<char>::find(data() + pos, size() - pos, value);
        return found == nullptr ? npos : (size_type) (found - data());
    }

    constexpr size_type rfind(const SmString& text, size_type pos = npos) const {
        return rfind(text.data(), pos, text.size());
    }
    constexpr size_type rfind(const char* text, size_type pos = npos) const {
        return rfind(text, pos, text == nullptr ? 0 : std::char_traits<char>::length(text));
    }
    size_type rfind(char value, size_type pos = npos) const {
        if (size() == 0) return npos;
        size_type from = pos >= size() ? size() - 1 : pos;
        for (size_type i = from + 1; i > 0; i--) {
            if (_data[(Int) (i - 1)] == value) return i - 1;
        }
        return npos;
    }

    // std::string-compatible three-way compare of a substring against `text`.
    constexpr int compare(size_type pos, size_type count, const SmString& text) const {
        size_type from = clampPos(size(), pos);
        size_type len = fitted(size() - from, count);
        size_type common = len < text.size() ? len : text.size();
        int diff = common == 0 ? 0 : std::char_traits<char>::compare(data() + from, text.data(), common);
        if (diff != 0) return diff < 0 ? -1 : 1;
        if (len == text.size()) return 0;
        return len < text.size() ? -1 : 1;
    }

    constexpr int compare(const SmString& text) const { return compare(0, npos, text); }

    // A raw C string is compared in place: routing it through a temporary
    // SmString made every `str == "literal"` build and scan a whole string
    // before the comparison (see compareBytes).
    constexpr int compare(const char* text) const {
        if (text == nullptr) return size() == 0 ? 0 : 1;
        return compareBytes(text, std::char_traits<char>::length(text));
    }

    // ---- conversions ------------------------------------------------------

    // The standard-library spelling of the same bytes; native code that has to
    // call std::filesystem / std::ifstream / std::stoi goes through this (or the
    // `simse_toStdString` helper) so it compiles in either Str configuration.
    std::string toStdString() const { return std::string(data(), size()); }

    void writeTo(std::ostream& out) const { out.write(data(), (std::streamsize) size()); }

private:
    using Data = StrSmallVector;

    // The buffer tracks the terminating NUL as part of its stored length, so
    // `data()[size()]` is always '\0' and no separate terminate pass is needed.
    Data _data;

    constexpr void ensure(size_type wanted) { _data.reserve((Int) wanted + 1); }

    static constexpr size_type clampPos(size_type length, size_type pos) {
        return pos > length ? length : pos;
    }

    // Three-way compare of the whole string against `count` bytes at `text`.
    // `std::char_traits` is the standard library's own primitive (constexpr, and
    // `memcmp` for char), so there is nothing to gain from a temporary.
    constexpr int compareBytes(const char* text, size_type count) const {
        size_type mine = size();
        size_type common = mine < count ? mine : count;
        int diff = common == 0 ? 0 : std::char_traits<char>::compare(data(), text, common);
        if (diff != 0) return diff < 0 ? -1 : 1;
        if (mine == count) return 0;
        return mine < count ? -1 : 1;
    }

    static constexpr size_type fitted(size_type available, size_type count) {
        return count == npos || count > available ? available : count;
    }

    // `memcmp` spelled through char_traits, which is constexpr (and compiles to
    // the same intrinsic), so a comparison can be constant-evaluated.
    constexpr size_type find(const char* text, size_type pos, size_type length) const {
        if (length == 0) return pos <= size() ? pos : npos;
        if (text == nullptr || pos > size() || length > size() - pos) return npos;
        const char* self = data();
        for (size_type i = pos; i + length <= size(); i++) {
            if (self[i] == text[0] && std::char_traits<char>::compare(self + i, text, length) == 0) {
                return i;
            }
        }
        return npos;
    }

    constexpr size_type rfind(const char* text, size_type pos, size_type length) const {
        if (length == 0) return pos <= size() ? pos : size();
        if (text == nullptr || length > size()) return npos;
        size_type last = size() - length;
        size_type from = pos >= last ? last : pos;
        for (size_type i = from + 1; i > 0; i--) {
            if (std::char_traits<char>::compare(data() + (i - 1), text, length) == 0) return i - 1;
        }
        return npos;
    }
};
SIMSE_PACK_POP

inline constexpr Bool operator==(const SmString& left, const SmString& right) {
    return left.size() == right.size() && left.compare(right) == 0;
}
inline constexpr Bool operator!=(const SmString& left, const SmString& right) { return !(left == right); }
inline constexpr Bool operator<(const SmString& left, const SmString& right) {
    return left.compare(right) < 0;
}
inline constexpr Bool operator>(const SmString& left, const SmString& right) { return right < left; }
inline constexpr Bool operator<=(const SmString& left, const SmString& right) { return !(right < left); }
inline constexpr Bool operator>=(const SmString& left, const SmString& right) { return !(left < right); }

inline constexpr Bool operator==(const SmString& left, const char* right) { return left.compare(right) == 0; }
inline constexpr Bool operator==(const char* left, const SmString& right) { return right.compare(left) == 0; }
inline constexpr Bool operator!=(const SmString& left, const char* right) { return !(left == right); }
inline constexpr Bool operator!=(const char* left, const SmString& right) { return !(left == right); }
inline constexpr Bool operator<(const SmString& left, const char* right) { return left.compare(right) < 0; }
inline constexpr Bool operator<(const char* left, const SmString& right) {
    return right.compare(left) > 0;
}
inline constexpr Bool operator>(const SmString& left, const char* right) { return left.compare(right) > 0; }
inline constexpr Bool operator>(const char* left, const SmString& right) { return right.compare(left) < 0; }
// Without these two, `str <= "literal"` fell back to the SmString-vs-SmString
// operator with a converting temporary.
inline constexpr Bool operator<=(const SmString& left, const char* right) { return left.compare(right) <= 0; }
inline constexpr Bool operator<=(const char* left, const SmString& right) { return right.compare(left) >= 0; }
inline constexpr Bool operator>=(const SmString& left, const char* right) { return left.compare(right) >= 0; }
inline constexpr Bool operator>=(const char* left, const SmString& right) { return right.compare(left) <= 0; }

inline constexpr Bool operator==(const SmString& left, const std::string& right) {
    return left.size() == right.size()
           && std::char_traits<char>::compare(left.data(), right.data(), left.size()) == 0;
}
inline constexpr Bool operator==(const std::string& left, const SmString& right) { return right == left; }
inline constexpr Bool operator!=(const SmString& left, const std::string& right) { return !(left == right); }
inline constexpr Bool operator!=(const std::string& left, const SmString& right) { return !(right == left); }

inline SmString operator+(const SmString& left, const SmString& right) {
    SmString result;
    result.reserve(left.size() + right.size());
    result.append(left);
    result.append(right);
    return result;
}
inline SmString operator+(const SmString& left, const char* right) {
    SmString result;
    result.append(left);
    result.append(right);
    return result;
}
inline SmString operator+(const char* left, const SmString& right) {
    SmString result;
    result.append(left);
    result.append(right);
    return result;
}
inline SmString operator+(const SmString& left, char right) {
    SmString result(left);
    result.push_back(right);
    return result;
}
inline SmString operator+(char left, const SmString& right) {
    SmString result;
    result.push_back(left);
    result.append(right);
    return result;
}
inline SmString operator+(const SmString& left, const std::string& right) {
    SmString result(left);
    result.append(right.data(), right.size());
    return result;
}
inline SmString operator+(const std::string& left, const SmString& right) {
    SmString result;
    result.append(left.data(), left.size());
    result.append(right);
    return result;
}

inline void swap(SmString& left, SmString& right) { left.swap(right); }

inline std::ostream& operator<<(std::ostream& out, const SmString& value) {
    value.writeTo(out);
    return out;
}

inline std::istream& getline(std::istream& in, SmString& line) {
    std::string buffer;
    std::getline(in, buffer);
    line = buffer;
    return in;
}

namespace std {
    template <>
    struct hash<SmString> {
        std::size_t operator()(const SmString& value) const {
            // FNV-1a over the bytes (the same hash shape for both Str spellings).
            std::size_t hash = 1469598103934665603ull;
            for (std::size_t i = 0; i < value.size(); i++) {
                hash ^= (unsigned char) value[i];
                hash *= 1099511628211ull;
            }
            return hash;
        }
    };
}

// The language's `Str` is SmString by default; defining SIMSE_STR_STD_STRING
// backs it with std::string instead (impl_specs/rtl-abi.md).
#if defined(SIMSE_STR_STD_STRING)
using Str = std::string;
#else
using Str = SmString;
#endif

// Native-code boundary helpers (impl_specs/rtl-abi.md): standard-library APIs
// take/return `std::string`, while the language works in `Str`. With
// `Str = std::string` both directions are trivial copies, so native code written
// against these helpers compiles in either configuration.
inline std::string simse_toStdString(const Str& value) {
#if defined(SIMSE_STR_STD_STRING)
    return value;
#else
    return value.toStdString();
#endif
}

inline Str simse_fromStdString(const std::string& value) {
#if defined(SIMSE_STR_STD_STRING)
    return value;
#else
    return SmString(value);
#endif
}
