#pragma once

#include "variant2.hpp"

// Opt<T> models an optional value (specs/core-types.md): it either holds a `T` or is
// empty, and it is the language's way of saying "no value" instead of a null pointer.
//
// The storage is `Variant2<T, VoidEnum>` (variant2.hpp): the second alternative is the
// empty one, so an empty optional builds nothing and the absence is a state of the tag
// rather than a second object living beside the payload. `hasValue()` and `value()` are
// the language's two accesses, `some`/`none` its two constructors - and `value()` on an
// empty optional is unchecked, as every RTL access is.
template <class T>
using Opt = Variant2<T, VoidEnum>;
