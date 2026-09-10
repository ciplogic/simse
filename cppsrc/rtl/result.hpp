#pragma once

#include "types.hpp"

// Result<T> reports a fallible operation: success carries a T or failure
// carries an error message as a Str. There being no exceptions, functions that
// can fail return a Result/Res (specs/core-types.md).
template <class T>
struct Res {
    T Value;
    Str Error;

    bool isOk() {
        return Error.length() == 0;
    }

    // The static forms used by Simse's `Res<T>.ok(x)` / `Res<T>.err(msg)`.
    // The free functions below are kept for existing hand-written C++ callers.
    static Res<T> ok(T value) {
        return Res<T>{value, ""};
    }
    static Res<T> err(Str errorMessage) {
        return Res<T>{{}, errorMessage};
    }
};

template <class T>
Res<T> ok(T value) {
    return Res<T>{value, ""};
}

template <class T>
Res<T> resError(Str errorMessage) {
    return Res<T>{{}, errorMessage};
}

template <class T>
Res<T> err(Str errorMessage) {
    return resError<T>(errorMessage);
}