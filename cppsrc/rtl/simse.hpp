#pragma once

#include <functional>
#include <string>
#include <vector>

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

template <class T>
using Func = std::function<T>;

using Str = std::string;
