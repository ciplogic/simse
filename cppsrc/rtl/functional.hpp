#pragma once

#include <functional>

// Func<T> is the runtime representation of callable/function types
// (specs/memory-model.md), lowered to std::function.
template <class T>
using Func = std::function<T>;

using Action = std::function<void()>;

// A Simse lambda is a data class (its captures) plus a free `<sym>_invoke(self, args...)`
// whose `self` is the closure passed by value. `simse_closureFunc` is the one bridge into
// the type-erased `Func`: the returned callable owns a copy of the closure and hands the
// free function a copy of that on every call.
template <class F, class Self, class R, class... Args>
F simse_closureFunc(R (*invoke)(Self, Args...), Self self) {
    return F([invoke, self](Args... args) -> R { return invoke(self, args...); });
}

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