#pragma once

#include <functional>

// Func<T> is the runtime representation of callable/function types
// (specs/memory-model.md), lowered to std::function.
template <class T>
using Func = std::function<T>;

using Action = std::function<void()>;

// AutoDefer runs an action when it goes out of scope (RAII).
struct AutoDefer{
    Action _action;
    AutoDefer(Action action) {
        _action = action;
    }
    ~AutoDefer() {
        _action();
    }
};