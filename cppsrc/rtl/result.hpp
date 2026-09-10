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