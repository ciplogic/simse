package kit

import unsafe

// Shared support for the Simse benchmark implementations (bench/README.md). The
// benchmarks are ports of aardappel/goose's bench suite, whose generators and
// checksums are written against C++ `uint64_t` (bench/cpp/bench.h). The u64 values
// travel as `unsafe.UInt64` (modules/unsafe/api.kt): the bits sit in an `Int64`, the
// wrap arithmetic (`+`/`-`/`*`) is two's complement and therefore the u64 wrap bit
// for bit, and the unsigned surface is the module's - `shr` the logical shift,
// `uDiv`/`uMod` the machine's own unsigned division and remainder, `toString` the
// `%llu` spelling, all raw `uint64_t` C++ (verified by the runner, which fails the
// bench when a checksum disagrees with the C++ row).
//
// A byte read out of a `Str` needs one more mask: `Char` is a *signed* 8-bit
// type, so `byteAt` answers 0..255 the way `uint8_t` reads.

// The xorshift64 the C++ references use: x ^= x << 13; x ^= x >> 7 (logical);
// x ^= x << 17. The left shifts are the modulo-2^64 ones C++20 defines; the right
// shift is `UInt64.shr`, the logical one.
fun xsNext(x: UInt64): UInt64 {
    var v: UInt64 = x
    v = v.xorWith(v.shl(13))
    v = v.xorWith(v.shr(7))
    v = v.xorWith(v.shl(17))
    return v
}

// One byte of a `Str` or a view of one, 0..255: the unsigned read every C++
// row gets from `uint8_t`/`unsigned char`.
fun byteAt(s: *Str, i: Int): Int {
    return Int(s[i]) & 255
}
