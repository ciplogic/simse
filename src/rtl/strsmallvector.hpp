#pragma once

#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <type_traits>

#include "types.hpp"

// kStrInlineCapacity is the inline capacity of `Str`, in bytes, and the reserved
// terminating NUL is one of them (specs/containers.md): a `Str` holds up to
// `kStrInlineCapacity - 1` characters before its buffer spills to the heap. It is
// defined once, here, and both `SmString` (smstring.hpp) and its buffer
// (`StrSmallVector` below) read it, so the layout has a single source of truth.
//
// The default is the specification's 24 bytes (23 characters inline); names,
// messages and attribute text in the compiler's own vocabulary routinely run
// past 15 characters, and at 16 bytes `"Expr.GenericName"` — a typical AST kind
// — would allocate. `SIMSE_STR_INLINE_CAPACITY` overrides it for size/speed
// measurements (impl_specs/rtl-abi.md, T33 in impl_specs/capability-matrix.md).
#ifndef SIMSE_STR_INLINE_CAPACITY
#define SIMSE_STR_INLINE_CAPACITY 24
#endif
inline constexpr Int kStrInlineCapacity = SIMSE_STR_INLINE_CAPACITY;

// StrSmallVector is the buffer behind `Str` (specs/containers.md): the same
// layout as `SmallVector<char, kStrInlineCapacity>` — `Int _len`, `Int _cap`,
// then the inline buffer unioned with the heap pointer, `8 + inlineCapacity`
// bytes in total (32 at the spec capacity of 24, 24 at 16) — specialized for the
// single element type it ever holds.
//
// **`_len` is the character count** — zero-based, so the empty string is
// `_len == 0`, the same convention `SmallVector` uses for elements. The buffer
// always keeps a NUL at `data()[_len]`, one byte past the text: the terminator is
// an invariant of the storage, not something each caller has to remember to
// write, and the allocation (`_cap`, in bytes) is what carries it.
//
//   * the read path is arithmetic-free: `size()` is `_len`, `empty()` is
//     `_len == 0`, `end()` is `raw() + _len`, `data()` is always a valid C
//     string;
//   * the `+1` for the terminator lives in the write paths, which run once per
//     mutation rather than once per read;
//   * growing operations write the new NUL as part of the same write
//     (`push_back`, `resize`, `assign`, `append`), so there is no separate
//     "terminate" pass after them;
//   * a text that fits is copied in one move — `assign` copies `count + 1` bytes
//     because the source's NUL is part of the text, which is what literals,
//     `std::string::data()` and other `Str`s provide.
//
// The specialization exists because the generic SmallVector manages element
// lifetimes one element at a time, and for `Char` that walk is pure overhead.
//
// It keeps the 4-byte packing rule of every language aggregate
// (specs/memory-model.md) and is constructible in a constant expression while it
// stays inline, so `constexpr Str` works (`smstring.hpp`).
SIMSE_PACK_PUSH
class StrSmallVector {
public:
    using value_type = char;
    using size_type = Int;
    using iterator = char*;
    using const_iterator = const char*;

    static constexpr Int inlineCapacity = kStrInlineCapacity;   // bytes, NUL included
    static constexpr Int maxInlineSize = inlineCapacity - 1;    // characters

    // The inline buffer starts out holding the empty string (its NUL). Writing
    // it also selects the inline union member.
    constexpr StrSmallVector() : _len(0), _cap(inlineCapacity) {
        _storage._inlineStore[0] = '\0';
    }

    StrSmallVector(const StrSmallVector& other) : _len(0), _cap(inlineCapacity) {
        assign(other.data(), other.size());
    }

    StrSmallVector(StrSmallVector&& other) noexcept : _len(0), _cap(inlineCapacity) {
        takeFrom(other);
    }

    constexpr ~StrSmallVector() { releaseHeap(); }

    StrSmallVector& operator=(const StrSmallVector& other) {
        if (this != &other) assign(other.data(), other.size());
        return *this;
    }

    StrSmallVector& operator=(StrSmallVector&& other) noexcept {
        if (this != &other) {
            releaseHeap();
            takeFrom(other);
        }
        return *this;
    }

    // ---- element access ---------------------------------------------------

    constexpr char& operator[](Int index) { return raw()[index]; }
    constexpr const char& operator[](Int index) const { return raw()[index]; }

    char& at(Int index) {
        if (index < 0 || index >= size()) std::abort();
        return raw()[index];
    }
    const char& at(Int index) const {
        if (index < 0 || index >= size()) std::abort();
        return raw()[index];
    }

    constexpr char& front() { return raw()[0]; }
    constexpr const char& front() const { return raw()[0]; }
    constexpr char& back() { return raw()[size() - 1]; }
    constexpr const char& back() const { return raw()[size() - 1]; }

    constexpr char* data() { return raw(); }
    constexpr const char* data() const { return raw(); }

    // Characters only: the stored NUL is not part of the range.
    iterator begin() { return raw(); }
    iterator end() { return raw() + size(); }
    const_iterator begin() const { return raw(); }
    const_iterator end() const { return raw() + size(); }
    const_iterator cbegin() const { return raw(); }
    const_iterator cend() const { return raw() + size(); }

    // ---- capacity ---------------------------------------------------------

    constexpr Bool empty() const { return _len == 0; }
    constexpr Int size() const { return _len; }
    constexpr Int capacity() const { return _cap - 1; }   // characters

    // `count` is a *stored* byte count (NUL included), like `_cap`: it is the
    // allocation's unit, and the only place that has to think in bytes.
    constexpr void reserve(Int count) {
        if (count <= _cap) return;
        Int next = _cap < inlineCapacity ? inlineCapacity : _cap * 2;
        if (next < count) next = count;
        char* fresh = std::allocator<char>().allocate((std::size_t) next);
        char* old = raw();
        const Int stored = _len + 1;
        if (std::is_constant_evaluated()) {
            for (Int i = 0; i < stored; i++) fresh[i] = old[i];
        } else {
            std::memcpy(fresh, old, (std::size_t) stored);   // includes the NUL
        }
        if (!isInline()) std::allocator<char>().deallocate(old, (std::size_t) _cap);
        _cap = next;
        _storage._heap = fresh;                 // switches the union to the heap
    }

    // ---- modifiers --------------------------------------------------------

    // Back to the empty string; the NUL is written as part of it.
    constexpr void clear() {
        _len = 0;
        raw()[0] = '\0';
    }

    // Growing leaves the new characters uninitialized; the NUL after them is
    // written here, and every caller (`SmString`) fills the characters in.
    constexpr void resize(Int count) {
        Int needed = count + 1;
        if (needed > _cap) reserve(needed);
        _len = count;
        raw()[count] = '\0';
    }

    constexpr void push_back(char value) {
        if (_len + 2 > _cap) reserve(_len + 2);
        raw()[_len] = value;
        raw()[_len + 1] = '\0';
        _len++;
    }

    constexpr void pop_back() {
        if (_len > 0) {
            _len--;
            raw()[_len] = '\0';
        }
    }

    // ---- whole-text writes ------------------------------------------------
    //
    // `assign`/`append` are for text laid out as a string: `text[count]` is the
    // terminating NUL (a literal, a `std::string`'s `data()`, another `Str`), so
    // it travels in a single `count + 1` byte move. The `...Substring` pair is
    // for a window inside a longer string (a `substr` range, a `(ptr, count)`
    // source that is not terminated at `count`): it copies `count` bytes and
    // writes the NUL after them. The two cases share the capacity and heap logic
    // through a compile-time parameter, so each method's body is straight-line.

    constexpr void assign(const char* text, Int count) { assignImpl<true>(text, count); }
    constexpr void assignSubstring(const char* text, Int count) { assignImpl<false>(text, count); }

    constexpr void append(const char* text, Int count) { appendImpl<true>(text, count); }
    constexpr void appendSubstring(const char* text, Int count) { appendImpl<false>(text, count); }

    void swap(StrSmallVector& other) {
        if (this == &other) return;
        StrSmallVector temporary(std::move(other));
        other = std::move(*this);
        *this = std::move(temporary);
    }

public:
    // The character count (zero-based) and the allocated capacity in bytes
    // (NUL included); `isInline` distinguishes the two storage modes.
    Int _len = 0;
    Int _cap = inlineCapacity;

    union Storage {
        char* _heap;
        char _inlineStore[inlineCapacity];      // `_inline` is an MSVC keyword
        // Inside a constant expression the inline buffer is activated and zeroed
        // so its elements are live and can be assigned; outside one it stays raw
        // storage (the elements are written as plain chars), which costs nothing.
        constexpr Storage() {
            if (std::is_constant_evaluated()) {
                for (Int i = 0; i < inlineCapacity; i++) _inlineStore[i] = '\0';
            } else {
                _heap = nullptr;
            }
        }
        constexpr ~Storage() {}
    };
    Storage _storage;

    constexpr Bool isInline() const { return _cap <= inlineCapacity; }
    constexpr char* raw() { return isInline() ? _storage._inlineStore : _storage._heap; }
    constexpr const char* raw() const {
        return isInline() ? _storage._inlineStore : _storage._heap;
    }

    // Writes `count` characters and the NUL after them. `TextHasNul` says the
    // source's NUL is part of the text, in which case the move carries it and no
    // separate store is needed. `memmove` (not `memcpy`) covers the self-assign
    // cases.
    template <Bool TextHasNul>
    static constexpr void writeInto(char* destination, const char* text, Int count) {
        if (std::is_constant_evaluated()) {
            for (Int i = 0; i < count; i++) destination[i] = text[i];
            destination[count] = '\0';
            return;
        }
        if constexpr (TextHasNul) {
            std::memmove(destination, text, (std::size_t) count + 1);
        } else {
            std::memmove(destination, text, (std::size_t) count);
            destination[count] = '\0';
        }
    }

    template <Bool TextHasNul>
    constexpr void assignImpl(const char* text, Int count) {
        Int needed = count + 1;
        if (needed > _cap) {
            reserve(needed);
            writeInto<TextHasNul>(raw(), text, count);
        } else if (needed <= inlineCapacity && !isInline()) {
            // Shorter text: move back into the inline buffer, then free the heap
            // block (the copy happens first: `text` may point into it).
            char* old = _storage._heap;
            Int oldCapacity = _cap;
            writeInto<TextHasNul>(_storage._inlineStore, text, count);
            _cap = inlineCapacity;
            std::allocator<char>().deallocate(old, (std::size_t) oldCapacity);
        } else {
            writeInto<TextHasNul>(raw(), text, count);
        }
        _len = count;
    }

    template <Bool TextHasNul>
    constexpr void appendImpl(const char* text, Int count) {
        if (count <= 0) return;
        Int from = _len;                        // the current NUL's index
        Int needed = _len + count + 1;          // NUL moves right by `count`
        if (needed > _cap) reserve(needed);
        writeInto<TextHasNul>(raw() + from, text, count);
        _len = from + count;
    }

    constexpr void releaseHeap() {
        if (!isInline()) {
            std::allocator<char>().deallocate(_storage._heap, (std::size_t) _cap);
            _cap = inlineCapacity;
            _len = 0;
            _storage._inlineStore[0] = '\0';
        }
    }

    // Moves `other`'s contents in, leaving it empty and inline.
    constexpr void takeFrom(StrSmallVector& other) {
        if (!other.isInline()) {
            _len = other._len;
            _cap = other._cap;
            _storage._heap = other._storage._heap;
            other._len = 0;
            other._cap = inlineCapacity;
            other._storage._heap = nullptr;
            other._storage._inlineStore[0] = '\0';
            return;
        }
        _len = other._len;
        writeInto<true>(_storage._inlineStore, other._storage._inlineStore, other.size());
        other._len = 0;
        other._storage._inlineStore[0] = '\0';
    }
};
SIMSE_PACK_POP
