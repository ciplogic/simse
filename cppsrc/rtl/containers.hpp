#pragma once

#include <memory>
#include <unordered_map>
#include <vector>

// SmallVector<N, T> is a distinct inline vector type with a small-buffer
// optimization (specs/containers.md). It stores up to N elements inline and
// spills to a heap buffer once it grows past N.
template <class T, int N>
struct SmallVector{
    int _len {};
    int _cap = N;
    union {
        T _data[N];
        T* heapElements{};
    };
};

// List<T> is a mutable value sequence with deep-copy semantics. The runtime
// representation is std::vector<T>.
template <class T>
using List = std::vector<T>;

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
template <class T>
struct Array {
    int _count{};
    std::shared_ptr<T> _data{};

    Array() = default;
    explicit Array(int count) {
        _count = count;
        _data.reset(new T[count], std::default_delete<T[]>());
    }

    int count() const { return _count; }

    T& operator[](int i) { return _data.get()[i]; }
    const T& operator[](int i) const { return _data.get()[i]; }
};