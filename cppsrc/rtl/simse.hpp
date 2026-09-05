#pragma once

#include <functional>
#include <string>
#include <vector>
#include <unordered_map>

template <class T, int N>
struct SmallVector{
    int _len {};
    int _cap = N;
    union {
        T _data[N];
        T* heapElements{};
    };
};

template <class T>
using List = std::vector<T>;

template <class TKey, class TValue>
using Dictionary = std::unordered_map<TKey, TValue>;

template <class T>
using Func = std::function<T>;

using Str = std::string;
