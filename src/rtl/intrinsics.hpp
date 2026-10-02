#pragma once

#include <cstring>

#include "types.hpp"

// The machine primitives (src/rtl/intrinsics.kt declares them in the prelude). Simse has
// no raw memory and no pointer arithmetic, so the handful of operations that *are* the
// machine - a block copy, a fill, a byte-range compare, a byte search, and a string's byte
// pointer - stay in C++ here, and nothing else does: the RTL's text operations are written
// in the language over these (src/rtl/StrView.kt, and the `strops` section of
// src/rtl/_res.md as it migrates).
//
// A caller passes a base pointer and an index, never a formed pointer, so the language
// never does pointer arithmetic; each intrinsic does the one addition. `count <= 0` is a
// no-op, which keeps a caller's clamps simple.
inline void simse_mem_copy(Char* dst, Int dstIndex, const Char* src, Int srcIndex, Int count) {
    if (count > 0) {
        std::memmove(dst + dstIndex, src + srcIndex, (std::size_t) count);
    }
}

inline void simse_mem_fill(Char* dst, Int from, Int count, Char value) {
    if (count > 0) {
        std::memset(dst + from, (int) value, (std::size_t) count);
    }
}

// The three-way compare of `count` bytes: -1, 0 or 1 (the sign is all a caller uses).
inline Int simse_mem_compare(const Char* a, Int aIndex, const Char* b, Int bIndex, Int count) {
    if (count <= 0) {
        return 0;
    }
    const int diff = std::memcmp(a + aIndex, b + bIndex, (std::size_t) count);
    return diff < 0 ? -1 : (diff > 0 ? 1 : 0);
}

// The index of the first `value` in the `count` bytes from `from`, or -1.
inline Int simse_mem_findByte(const Char* a, Int from, Int count, Char value) {
    if (count <= 0) {
        return -1;
    }
    const void* found = std::memchr(a + from, (int) value, (std::size_t) count);
    if (found == nullptr) {
        return -1;
    }
    return (Int) (reinterpret_cast<const Char*>(found) - a);
}
