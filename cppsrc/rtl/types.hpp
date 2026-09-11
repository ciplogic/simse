#pragma once

#include <cstdint>
#include <string>

// Fixed-width scalar types (specs/built-in-types.md).
// Char is a signed 8-bit value, exactly equivalent to Int8.
using Int8    = std::int8_t;
using Int16   = std::int16_t;
using Int32   = std::int32_t;
using Int64   = std::int64_t;
using Float32 = float;
using Float64 = double;
using Char    = std::int8_t;
using Int     = Int32; // default integer type; alias of Int32
using Bool    = bool;  // two-valued built-in (specs/built-in-types.md)

// Str is the mutable inline byte-string type. The runtime representation uses
// std::string, which satisfies the deep-copy value semantics the specs require.
using Str = std::string;

// `*value` in Simse is the raw-pointer (address-of) form (specs/memory-model.md).
// The lvalue overload covers ordinary expressions; the forwarding overload binds
// temporaries. A pointer to a temporary is valid until the end of the full
// expression, i.e. for the duration of the call it is passed to.
template <class T>
T* simse_addressOf(T& value) {
    return &value;
}

template <class T>
T* simse_addressOf(T&& value) {
    return &value;
}