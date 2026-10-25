package unsafe

// The `unsafe` module: the unsigned integer types the language's scalar set lacks. Each
// keeps its bits in a wider signed scalar - `UInt8`, `UInt16` and `UInt32` in an `Int`,
// `UInt64` in an `Int64` - and `value()` masks the stored bits back to the width, so a
// `UInt8` of 300 (or -56, or 200) reads as 44, 200, 200: the type is C#'s
// `byte`/`ushort`/`uint`/`ulong` with every operation wrapping at the width.
//
// `value()` is an `Int` for the 8- and 16-bit types (`0..255`, `0..65535`), and an `Int64`
// only for `UInt32`/`UInt64`, whose values can pass `Int`'s range. The constructors take
// the bits as written; masking happens on every read, so `UInt8(300)` and `UInt8(44)` are
// the same value, and a 32-bit constant above `2^31 - 1` is written as its two's-complement
// form (`UInt32(-306674912)` is `0xEDB88320`).
//
// Only `+`, the comparisons and `==` have operator spellings (`specs/functions.md` lowers
// `get`/`set`/`compareTo`/`equals`/`plus` only); the rest are named methods. `+` and `shl`
// mask their result and `shr` masks its input, so every method reads the same whatever
// the constructor was given. Every type is exact at its width. `UInt64` keeps its bits in
// a signed `Int64` (the language has no unsigned 64-bit scalar) and recovers the unsigned
// surface as raw C++ over `uint64_t` - `shr` the logical shift, `uDiv`/`uMod` the
// machine's own unsigned division and remainder, `toString` the `%llu` spelling
// (`_res.md`'s `u64` section behind the `@SmGen` declarations below the class) - while
// `compareTo` answers the u64 order and the wrap arithmetic stays two's-complement
// `Int64`, which is the u64 wrap bit for bit.
//
// A program reaches the module with `--module modules/unsafe` and `import unsafe`.

// An 8-bit unsigned integer: `value()` is 0..255.
data class UInt8(var bits: Int) {
    fun value(): Int {
        return this.bits & 255
    }

    operator fun plus(other: UInt8): UInt8 {
        return UInt8((this.bits + other.bits) & 255)
    }

    operator fun compareTo(other: UInt8): Int {
        if (this.value() < other.value()) {
            return -1
        }
        if (this.value() > other.value()) {
            return 1
        }
        return 0
    }

    operator fun equals(other: UInt8): Bool {
        return this.value() == other.value()
    }

    fun andWith(other: UInt8): UInt8 {
        return UInt8(this.bits & other.bits)
    }

    fun orWith(other: UInt8): UInt8 {
        return UInt8(this.bits | other.bits)
    }

    fun xorWith(other: UInt8): UInt8 {
        return UInt8(this.bits ^ other.bits)
    }

    // `count` bits left; a count at or past the width answers 0.
    fun shl(count: Int): UInt8 {
        if (count >= 8) {
            return UInt8(0)
        }
        return UInt8((this.value() << count) & 255)
    }

    fun shr(count: Int): UInt8 {
        if (count >= 8) {
            return UInt8(0)
        }
        return UInt8(this.value() >> count)
    }

    fun toInt(): Int {
        return this.value()
    }

    fun toInt64(): Int64 {
        return this.value()
    }

    fun toString(): Str {
        return this.value().toString()
    }
}

// A 16-bit unsigned integer: `value()` is 0..65535.
data class UInt16(var bits: Int) {
    fun value(): Int {
        return this.bits & 65535
    }

    operator fun plus(other: UInt16): UInt16 {
        return UInt16((this.bits + other.bits) & 65535)
    }

    operator fun compareTo(other: UInt16): Int {
        if (this.value() < other.value()) {
            return -1
        }
        if (this.value() > other.value()) {
            return 1
        }
        return 0
    }

    operator fun equals(other: UInt16): Bool {
        return this.value() == other.value()
    }

    fun andWith(other: UInt16): UInt16 {
        return UInt16(this.bits & other.bits)
    }

    fun orWith(other: UInt16): UInt16 {
        return UInt16(this.bits | other.bits)
    }

    fun xorWith(other: UInt16): UInt16 {
        return UInt16(this.bits ^ other.bits)
    }

    fun shl(count: Int): UInt16 {
        if (count >= 16) {
            return UInt16(0)
        }
        return UInt16((this.value() << count) & 65535)
    }

    fun shr(count: Int): UInt16 {
        if (count >= 16) {
            return UInt16(0)
        }
        return UInt16(this.value() >> count)
    }

    fun toInt(): Int {
        return this.value()
    }

    fun toInt64(): Int64 {
        return this.value()
    }

    fun toString(): Str {
        return this.value().toString()
    }
}

// A 32-bit unsigned integer: `value()` is 0..4294967295 - an `Int64`, since the top half of
// the range passes an `Int`'s. The wrap arithmetic runs in `Int64` and truncates back to
// the stored `Int`'s 32 bits, which is the width's wrap.
data class UInt32(var bits: Int) {
    fun value(): Int64 {
        val wide: Int64 = this.bits
        return wide & 4294967295
    }

    operator fun plus(other: UInt32): UInt32 {
        // A signed 32-bit `+` could overflow; the halves add in 32-bit lanes and the high
        // half shifts back, so the wrap is computed rather than relied on.
        val low: Int = (this.bits & 65535) + (other.bits & 65535)
        val high: Int = (this.bits >> 16) + (other.bits >> 16) + (low >> 16)
        return UInt32((high << 16) | (low & 65535))
    }

    operator fun compareTo(other: UInt32): Int {
        if (this.value() < other.value()) {
            return -1
        }
        if (this.value() > other.value()) {
            return 1
        }
        return 0
    }

    operator fun equals(other: UInt32): Bool {
        return this.value() == other.value()
    }

    fun andWith(other: UInt32): UInt32 {
        return UInt32(this.bits & other.bits)
    }

    fun orWith(other: UInt32): UInt32 {
        return UInt32(this.bits | other.bits)
    }

    fun xorWith(other: UInt32): UInt32 {
        return UInt32(this.bits ^ other.bits)
    }

    fun shl(count: Int): UInt32 {
        if (count >= 32) {
            return UInt32(0)
        }
        // C++20's left shift is the modulo one, so the wrap is defined.
        return UInt32(this.bits << count)
    }

    fun shr(count: Int): UInt32 {
        if (count >= 32) {
            return UInt32(0)
        }
        if (count == 0) {
            return UInt32(this.bits)
        }
        // The stored bits shift arithmetically; the mask takes off what the sign bit
        // carried in (2^(32-count) - 1), so the read is the logical shift.
        return UInt32((this.bits >> count) & (2147483647 >> (count - 1)))
    }

    // The low 32 bits as a plain `Int` (negative when the top bit is set).
    fun toInt(): Int {
        return this.bits
    }

    // The unsigned value as an `Int64` - always non-negative.
    fun toInt64(): Int64 {
        return this.value()
    }

    fun toString(): Str {
        return this.value().toString()
    }
}

// A 64-bit unsigned integer: `value()` is the `Int64`'s bits (it may read negative,
// since the language has no unsigned 64-bit scalar - the bits are the value). The
// unsigned surface the signed `Int64` read lacks is raw C++ over `uint64_t`
// (`_res.md`'s `u64` section, reached through the `@SmGen` declarations below the
// class): `shr` is the logical shift, `uDiv`/`uMod` the machine's own unsigned
// division and remainder, `toString` the `%llu` spelling - one instruction or one
// formatting pass each, never the mask dance or the binary long division a signed
// scalar needs. `+`/`-`/`*` and the bitwise ops wrap in two's complement, which is
// the u64 wrap bit for bit, so they stay plain Simse; `compareTo` is the u64 order
// (top-bit-clear patterns sort below top-bit-set ones).
data class UInt64(var bits: Int64) {
    fun value(): Int64 {
        return this.bits
    }

    operator fun plus(other: UInt64): UInt64 {
        return UInt64(this.bits + other.bits)
    }

    fun minus(other: UInt64): UInt64 {
        return UInt64(this.bits - other.bits)
    }

    fun times(other: UInt64): UInt64 {
        return UInt64(this.bits * other.bits)
    }

    // The u64 order: the patterns with the top bit clear (the non-negative `Int64`s)
    // sort below the ones with it set, and within each half the signed order is the
    // pattern order.
    operator fun compareTo(other: UInt64): Int {
        val aHigh: Bool = this.bits < Int64(0)
        val bHigh: Bool = other.bits < Int64(0)
        if (aHigh != bHigh) {
            if (aHigh) {
                return 1
            }
            return -1
        }
        if (this.bits < other.bits) {
            return -1
        }
        if (this.bits > other.bits) {
            return 1
        }
        return 0
    }

    operator fun equals(other: UInt64): Bool {
        return this.bits == other.bits
    }

    fun andWith(other: UInt64): UInt64 {
        return UInt64(this.bits & other.bits)
    }

    fun orWith(other: UInt64): UInt64 {
        return UInt64(this.bits | other.bits)
    }

    fun xorWith(other: UInt64): UInt64 {
        return UInt64(this.bits ^ other.bits)
    }

    fun shl(count: Int): UInt64 {
        if (count >= 64) {
            return UInt64(0)
        }
        return UInt64(this.bits << count)
    }

    // The low 32 bits as a plain `Int` (negative when bit 31 is set).
    fun toInt(): Int {
        return Int(this.bits)
    }

    fun toInt64(): Int64 {
        return this.bits
    }
}

// The C++-backed unsigned surface (`_res.md`'s `u64` section): each is one
// `uint64_t` operation, template-shaped so it fits whatever namespace the
// program's generated `UInt64` landed in. The bodies live in resources rather
// than the class so a program that never divides unsigned never compiles
// unsigned division.
@SmGen("res", "u64", "simse_u64_shr")
fun shr(this: UInt64, count: Int): UInt64

@SmGen("res", "u64", "simse_u64_uDiv")
fun uDiv(this: UInt64, other: UInt64): UInt64

@SmGen("res", "u64", "simse_u64_uMod")
fun uMod(this: UInt64, other: UInt64): UInt64

@SmGen("res", "u64", "simse_u64_toString")
fun toString(this: UInt64): Str
