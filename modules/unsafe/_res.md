Generated C++
====
The `unsafe` module's C++ - the declarations are modules/unsafe/api.kt. The `u64` section
is `UInt64`'s unsigned surface: the four operations a signed `Int64` cannot answer
directly, written as one `uint64_t` operation each so a program using them compiles to
the same instructions a C++ `uint64_t` row does - no masks, no binary long division
(`bench/README.md`, "The u64 question", is the consumer that cares).

!u64
====
The bodies are templates over the value type, because the generated `UInt64` struct lands
in whatever namespace the program's package layout gave it (`ns2_UInt64` in one program,
`ns5_UInt64` in another); the one thing that is stable is the field name `bits`. An
unreached section is not emitted, and an uninstantiated template costs nothing, so the
section's text reaches a program whole and the linker keeps only what it called.

forward:
```cpp
template <typename U> U simse_u64_shr(const U& self, int32_t count);
template <typename U> U simse_u64_uDiv(const U& self, const U& other);
template <typename U> U simse_u64_uMod(const U& self, const U& other);
template <typename U> Str simse_u64_toString(const U& self);
```

bodies:
```cpp
// The logical shift right: `>>` on the unsigned read, 0 past the width (a count at or
// past 64 is undefined in C++, so it is answered rather than shifted).
template <typename U>
U simse_u64_shr(const U& self, int32_t count) {
    U out;
    if (count >= 64) {
        out.bits = 0;
        return out;
    }
    out.bits = (int64_t)(((uint64_t)self.bits) >> count);
    return out;
}

// The unsigned division over the bit patterns: the machine's own `udiv`.
template <typename U>
U simse_u64_uDiv(const U& self, const U& other) {
    U out;
    out.bits = (int64_t)(((uint64_t)self.bits) / ((uint64_t)other.bits));
    return out;
}

// The unsigned remainder over the bit patterns: the machine's own `urem`.
template <typename U>
U simse_u64_uMod(const U& self, const U& other) {
    U out;
    out.bits = (int64_t)(((uint64_t)self.bits) % ((uint64_t)other.bits));
    return out;
}

// The unsigned decimal spelling of the bit pattern - what C++ prints with
// `printf("%llu")`: one divide-by-10 digit at a time, reversed into place.
template <typename U>
Str simse_u64_toString(const U& self) {
    uint64_t v = (uint64_t)self.bits;
    char digits[20];
    int32_t n = 0;
    do {
        digits[n++] = char('0' + int32_t(v % 10));
        v /= 10;
    } while (v != 0);
    Str out;
    out.resize(n);
    char* p = out.data();
    for (int32_t i = 0; i < n; i++) {
        p[i] = digits[n - 1 - i];
    }
    return out;
}
```
