#pragma once

#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <initializer_list>
#include <memory>
#include <type_traits>
#include <unordered_map>
#include <utility>
#include <vector>

#include "types.hpp"

// SmallVector<T, N> is a vector with a small-buffer optimization
// (specs/containers.md): up to N elements live in an inline buffer inside the
// object, and the storage spills to the heap once it grows past N. The Simse
// spelling is `SmallVector<N, T>`; the emitter reifies it as `SmallVector<T, N>`
// (impl_specs/reification.md, impl_specs/rtl-abi.md).
//
// Layout (specs/containers.md): `Int _len`, `Int _cap`, then the buffer. `_cap`
// doubles as the inline/heap discriminant: `_cap <= N` means the inline buffer is
// in use, anything larger refers to a heap allocation. Element lifetimes are
// managed explicitly (placement new / std::destroy_at), so T needs neither a
// default constructor nor a live inline buffer of default-constructed values.
//
// The operations mirror the std::vector surface the compiler uses, so the
// `List<T>` alias below can switch between the two. The class follows the
// language's 4-byte packing rule (`SIMSE_PACK_PUSH`/`SIMSE_PACK_POP` in
// types.hpp, specs/memory-model.md): its alignment is 4, and the inline buffer
// holds elements at the packed stride.
SIMSE_PACK_PUSH
template <class T, int N>
class SmallVector {
public:
    using value_type = T;
    using size_type = Int;
    using iterator = T*;
    using const_iterator = const T*;
    using reference = T&;
    using const_reference = const T&;

    SmallVector() = default;

    explicit SmallVector(Int count) {
        resize(count);
    }

    SmallVector(Int count, const T& value) {
        assign(count, value);
    }

    SmallVector(std::initializer_list<T> values) {
        reserve((Int) values.size());
        for (const T& value: values) push_back(value);
    }

    template <class It>
    SmallVector(It first, It last) {
        for (It it = first; it != last; ++it) push_back(*it);
    }

    SmallVector(const SmallVector& other) {
        copyFrom(other);
    }

    SmallVector(SmallVector&& other) noexcept {
        takeFrom(other);
    }

    ~SmallVector() {
        destroyElements();
        releaseHeap();
    }

    SmallVector& operator=(const SmallVector& other) {
        if (this != &other) {
            clear();
            copyFrom(other);
        }
        return *this;
    }

    SmallVector& operator=(SmallVector&& other) noexcept {
        if (this != &other) {
            clear();
            releaseHeap();
            takeFrom(other);
        }
        return *this;
    }

    SmallVector& operator=(std::initializer_list<T> values) {
        clear();
        reserve((Int) values.size());
        for (const T& value: values) push_back(value);
        return *this;
    }

    // ---- element access ---------------------------------------------------

    T& operator[](Int index) { return raw()[index]; }
    const T& operator[](Int index) const { return raw()[index]; }

    // Unchecked in the v1 subset (no exceptions); an out-of-range `at` aborts
    // rather than silently reading past the end.
    T& at(Int index) {
        if (index < 0 || index >= _len) std::abort();
        return raw()[index];
    }
    const T& at(Int index) const {
        if (index < 0 || index >= _len) std::abort();
        return raw()[index];
    }

    T& front() { return raw()[0]; }
    const T& front() const { return raw()[0]; }
    T& back() { return raw()[_len - 1]; }
    const T& back() const { return raw()[_len - 1]; }
    T* data() { return raw(); }
    const T* data() const { return raw(); }

    iterator begin() { return raw(); }
    iterator end() { return raw() + _len; }
    const_iterator begin() const { return raw(); }
    const_iterator end() const { return raw() + _len; }
    const_iterator cbegin() const { return raw(); }
    const_iterator cend() const { return raw() + _len; }

    // ---- capacity ---------------------------------------------------------

    Bool empty() const { return _len == 0; }
    Int size() const { return _len; }
    Int capacity() const { return _cap; }

    void reserve(Int count) {
        if (count <= _cap) return;
        Int next = _cap < N ? N : _cap * 2;
        if (next < count) next = count;
        T* fresh = std::allocator<T>().allocate((std::size_t) next);
        if constexpr (std::is_trivially_copyable_v<T>) {
            // Byte-wise relocation is valid for trivially copyable elements and
            // keeps the hot container paths (Char buffers, scalars, handles) free
            // of per-element constructor calls.
            if (_len > 0) std::memcpy(fresh, raw(), (std::size_t) _len * sizeof(T));
        } else {
            for (Int i = 0; i < _len; i++) {
                std::construct_at(fresh + i, std::move(raw()[i]));
            }
            destroyElements();
        }
        releaseHeap();
        _cap = next;
        _buf._heap = fresh;
    }

    // ---- modifiers --------------------------------------------------------

    void clear() {
        destroyElements();
        _len = 0;
    }

    void push_back(const T& value) { emplace_back(value); }
    void push_back(T&& value) { emplace_back(std::move(value)); }

    template <class... Args>
    T& emplace_back(Args&&... args) {
        if (_len == _cap) reserve(_cap + 1);
        T* slot = raw() + _len;
        std::construct_at(slot, std::forward<Args>(args)...);
        _len++;
        return *slot;
    }

    void pop_back() {
        _len--;
        std::destroy_at(raw() + _len);
    }

    void resize(Int count) {
        if (count < _len) {
            for (Int i = count; i < _len; i++) std::destroy_at(raw() + i);
            _len = count;
            return;
        }
        reserve(count);
        for (Int i = _len; i < count; i++) std::construct_at(raw() + i);
        _len = count;
    }

    void resize(Int count, const T& value) {
        if (count < _len) {
            for (Int i = count; i < _len; i++) std::destroy_at(raw() + i);
            _len = count;
            return;
        }
        reserve(count);
        for (Int i = _len; i < count; i++) std::construct_at(raw() + i, value);
        _len = count;
    }

    void assign(Int count, const T& value) {
        clear();
        reserve(count);
        for (Int i = 0; i < count; i++) std::construct_at(raw() + i, value);
        _len = count;
    }

    iterator erase(const_iterator position) { return erase(position, position + 1); }

    iterator erase(const_iterator first, const_iterator last) {
        T* base = raw();
        Int from = (Int) (first - base);
        Int to = (Int) (last - base);
        Int removed = to - from;
        Int tail = _len - to;
        if (tail > 0) {
            if constexpr (std::is_trivially_copyable_v<T>) {
                std::memmove(base + from, base + to, (std::size_t) tail * sizeof(T));
            } else {
                for (Int i = 0; i < tail; i++) base[from + i] = std::move(base[to + i]);
            }
        }
        for (Int i = _len - removed; i < _len; i++) std::destroy_at(base + i);
        _len -= removed;
        return base + from;
    }

    iterator insert(const_iterator position, const T& value) {
        Int index = (Int) (position - raw());
        if (_len == _cap) reserve(_cap + 1);
        T* base = raw();
        if (index == _len) {
            std::construct_at(base + _len, value);
        } else {
            std::construct_at(base + _len, std::move(base[_len - 1]));
            for (Int i = _len - 1; i > index; i--) base[i] = std::move(base[i - 1]);
            base[index] = value;
        }
        _len++;
        return base + index;
    }

    void swap(SmallVector& other) {
        if (this == &other) return;
        SmallVector temporary(std::move(other));
        other = std::move(*this);
        *this = std::move(temporary);
    }

    friend Bool operator==(const SmallVector& left, const SmallVector& right) {
        if (left._len != right._len) return false;
        for (Int i = 0; i < left._len; i++) {
            if (!(left[i] == right[i])) return false;
        }
        return true;
    }

    friend Bool operator!=(const SmallVector& left, const SmallVector& right) {
        return !(left == right);
    }

private:
    // `_cap <= N` selects the inline buffer; otherwise `_heap` is live. The
    // union keeps the object at the documented layout size: the inline buffer
    // and the heap pointer overlap, so heap storage costs no extra bytes.
    union Storage {
        T* _heap;
        alignas(4) unsigned char _inlineStore[sizeof(T) * N];
        Storage() : _heap(nullptr) {}
        ~Storage() {}
    };

    Int _len = 0;
    Int _cap = N;
    Storage _buf;

    Bool isInline() const { return _cap <= N; }

    T* raw() { return isInline() ? reinterpret_cast<T*>(_buf._inlineStore) : _buf._heap; }
    const T* raw() const {
        return isInline() ? reinterpret_cast<const T*>(_buf._inlineStore) : _buf._heap;
    }

    void destroyElements() {
        if constexpr (std::is_trivially_copyable_v<T>) {
            return;                              // nothing to destroy
        } else {
            T* base = raw();
            for (Int i = 0; i < _len; i++) std::destroy_at(base + i);
        }
    }

    void releaseHeap() {
        if (!isInline()) std::allocator<T>().deallocate(_buf._heap, (std::size_t) _cap);
        _cap = N;
    }

    // Copy `other`'s elements into an empty, inline `*this`.
    void copyFrom(const SmallVector& other) {
        reserve(other._len);
        if constexpr (std::is_trivially_copyable_v<T>) {
            if (other._len > 0) {
                std::memcpy(raw(), other.raw(), (std::size_t) other._len * sizeof(T));
            }
        } else {
            for (Int i = 0; i < other._len; i++) {
                std::construct_at(raw() + i, other.raw()[i]);
            }
        }
        _len = other._len;
    }

    // Move `other` into an empty, inline `*this`, stealing its heap buffer when
    // it has one and moving the elements inline otherwise. `other` is left
    // empty and inline.
    void takeFrom(SmallVector& other) {
        if (!other.isInline()) {
            _len = other._len;
            _cap = other._cap;
            _buf._heap = other._buf._heap;
            other._len = 0;
            other._cap = N;
            other._buf._heap = nullptr;
            return;
        }
        _len = other._len;
        if constexpr (std::is_trivially_copyable_v<T>) {
            if (_len > 0) std::memcpy(raw(), other.raw(), (std::size_t) _len * sizeof(T));
        } else {
            for (Int i = 0; i < _len; i++) {
                std::construct_at(raw() + i, std::move(other.raw()[i]));
            }
            other.destroyElements();
        }
        other._len = 0;
    }
};
SIMSE_PACK_POP

template <class T, int N>
void swap(SmallVector<T, N>& left, SmallVector<T, N>& right) {
    left.swap(right);
}

// List<T> is a mutable value sequence with deep-copy semantics
// (specs/containers.md). The backing store is selectable at compile time:
// SmallVector<T, 4> by default, or std::vector<T> when SIMSE_LIST_STD_VECTOR is
// defined. `SmallVector<N, T>` in Simse source is the same C++ type as
// `List<T>` when N is the inline capacity (impl_specs/rtl-abi.md records the
// divergence from specs/containers.md, which keeps them distinct types).
inline constexpr int kListInlineCapacity = 4;

#if defined(SIMSE_LIST_STD_VECTOR)
template <class T>
using List = std::vector<T>;
#else
template <class T>
using List = SmallVector<T, kListInlineCapacity>;
#endif

// `SmString` (and therefore `Str`) is defined in its own header, but it is built
// on SmallVector, so it can only be pulled in once the containers above exist.
// Including it here keeps `Str` available everywhere it used to be, including
// files that only include `types.hpp`/`containers.hpp`.
#include "smstring.hpp"

// PList<T> is a counted reference to a List<T> (specs/memory-model.md):
// the language spelling `&List<T>`. It shares one heap list between handles
// and deep-copies nothing on copy; the List itself remains a mutable value.
// `makeList()` is the runtime spelling of the `&List<T>()` construction.
template <class T>
using PList = std::shared_ptr<List<T>>;

template <class T>
PList<T> makeList() {
    return std::make_shared<List<T>>();
}

// Dictionary<K, V> is a built-in generic value dictionary
// (specs/dictionary.md), equivalent in purpose to std::unordered_map.
template <class TKey, class TValue>
using Dictionary = std::unordered_map<TKey, TValue>;

// RawArray<T> is the unmanaged `*T` spelling of a contiguous element block
// (specs/built-in-types.md). It carries no count, is not ref-counted, and
// does not keep the allocation alive.
template <class T>
using RawArray = T*;

// Array<T> is a ref-counted contiguous array (specs/built-in-types.md):
// assignment shares the same allocation, the length is fixed at construction,
// and elements are mutable through indexing. The reference count is provided
// by std::shared_ptr; the typeId described by the runtime header is currently
// unused by the runtime and therefore not stored.
// A count plus a shared block. Both are 4-byte packed with the rest of the
// language (specs/memory-model.md); the shared_ptr member is under-aligned by
// the host's standards, as recorded in impl_specs/rtl-abi.md.
SIMSE_PACK_PUSH
template <class T>
struct Array {
    Int _count{};
    std::shared_ptr<T> _data{};

    Array() = default;
    explicit Array(Int count) {
        _count = count;
        _data.reset(new T[count], std::default_delete<T[]>());
    }

    Int count() const { return _count; }

    T& operator[](Int i) { return _data.get()[i]; }
    const T& operator[](Int i) const { return _data.get()[i]; }
};
SIMSE_PACK_POP