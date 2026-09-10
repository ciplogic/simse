#pragma once

#include "types.hpp"

// Result<T> reports a fallible operation: success carries a T or failure
// carries an error message as a Str. There being no exceptions, functions that
// can fail return a Result/Res (specs/core-types.md).
template <class T>
struct Result {
    T Value;
    Str Error;

    bool isOk() {
        return Error.length() == 0;
    }
};

template <class T>
Result<T> ok(T value) {
    return Result<T>{value, ""};
}

template <class T>
Result<T> resError(Str errorMessage) {
    return Result<T>{{}, errorMessage};
}

// Res<T> is the spec name (specs/core-types.md) for Result<T>.
template <class T>
using Res = Result<T>;

template <class T>
Res<T> err(Str errorMessage) {
    return resError<T>(errorMessage);
}