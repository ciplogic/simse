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
// the constructor was given. The 64-bit type travels in a signed `Int64`, so its shifts
// are the platform's two's-complement ones - the 8/16/32-bit types are exact.
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
        val sum: Int = this.value() + other.value()
        return UInt32(sum)
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
        val shifted: Int = (this.value() << count) & 4294967295
        return UInt32(shifted)
    }

    fun shr(count: Int): UInt32 {
        if (count >= 32) {
            return UInt32(0)
        }
        val shifted: Int = this.value() >> count
        return UInt32(shifted)
    }

    // The low 32 bits as a plain `Int` (negative when the top bit is set).
    fun toInt(): Int {
        return this.value()
    }

    // The unsigned value as an `Int64` - always non-negative.
    fun toInt64(): Int64 {
        return this.value()
    }

    fun toString(): Str {
        return this.value().toString()
    }
}

// A 64-bit unsigned integer: `value()` is the `Int64`'s bits read unsigned (it may read
// negative, since the language has no unsigned 64-bit scalar). The shifts below are the
// platform's two's-complement ones, valid while the value fits in 63 bits.
data class UInt64(var bits: Int64) {
    fun value(): Int64 {
        return this.bits
    }

    operator fun plus(other: UInt64): UInt64 {
        return UInt64(this.bits + other.bits)
    }

    operator fun compareTo(other: UInt64): Int {
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

    fun shr(count: Int): UInt64 {
        if (count >= 64) {
            return UInt64(0)
        }
        return UInt64(this.bits >> count)
    }

    fun toInt64(): Int64 {
        return this.bits
    }

    fun toString(): Str {
        return this.bits.toString()
    }
}
