#pragma once

#include "variant2.hpp"

// Res<T> reports a fallible operation: success carries a T, failure carries an error
// message as a Str. There being no exceptions, functions that can fail return a Res
// (specs/core-types.md).
//
// The storage is `Variant2<T, Str>` (variant2.hpp): the payload and the message are the
// two alternatives of one union, so a failure carries no `T` and a success carries no
// message. `isOk()` reads the tag - an empty message is not what makes a result ok, which
// is what the two-field shape it replaced did (`Res<T>.err("")` used to read as a
// success). The message is spelled `Error` and the payload `Value`, the field names the
// emitter writes through for `Res<T>`.
template <class T>
using Res = Variant2<T, Str>;

// The free forms, kept for hand-written C++ callers; the language's own spellings are the
// static members above (`Res<T>.ok(x)` / `Res<T>.err(msg)`, sema/TypeInfer.kt).
template <class T>
Res<T> ok(T value) {
    return Res<T>::ok(std::move(value));
}

template <class T>
Res<T> resError(Str errorMessage) {
    return Res<T>::err(std::move(errorMessage));
}

template <class T>
Res<T> err(Str errorMessage) {
    return resError<T>(std::move(errorMessage));
}
