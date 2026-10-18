package fixtures

import unsafe

// The `unsafe` module (modules/unsafe/api.kt): the unsigned integer types. The case names
// the module (`compiler-args` holds `--module modules/unsafe`) and prints every type's
// wrap point, mask read, bit operations and shifts.
//
// The storage is a wider signed scalar, so this pins the reads: a `UInt8` of 300 reads as
// 44, a `UInt32` of -1 reads as 4294967295, and the arithmetic wraps at the width.

fun main(): Int {
    // 8-bit: wrapping add and the masked read.
    println("u8-add " + (UInt8(200) + UInt8(100)).toString())
    println("u8-wrap " + UInt8(300).toString())
    println("u8-cmp " + (UInt8(200) > UInt8(100)).toString())
    println("u8-eq " + (UInt8(300) == UInt8(44)).toString())

    // 8-bit: the bit operations and the shifts.
    println("u8-and " + UInt8(255).andWith(UInt8(15)).toString())
    println("u8-or " + UInt8(240).orWith(UInt8(15)).toString())
    println("u8-xor " + UInt8(255).xorWith(UInt8(15)).toString())
    println("u8-shl " + UInt8(240).shl(4).toString())
    println("u8-shr " + UInt8(240).shr(4).toString())
    println("u8-shl-past " + UInt8(240).shl(8).toString())

    // 16-bit.
    println("u16-add " + (UInt16(65535) + UInt16(1)).toString())
    println("u16-shr " + UInt16(65535).shr(8).toString())

    // 32-bit: the mask that reads the top bit set, and the wrap past it.
    println("u32-ff " + UInt32(-1).value().toString())
    println("u32-neg " + UInt32(-306674912).value().toString())
    println("u32-shr " + UInt32(-1).shr(16).value().toString())
    println("u32-mask " + UInt32(-1).andWith(UInt32(65535)).value().toString())
    println("u32-add " + (UInt32(-1) + UInt32(1)).value().toString())
    println("u32-shl " + UInt32(0 - 2147483647 - 1).shl(1).value().toString())

    // 64-bit: the signed read caveat, and `toInt64`.
    println("u64-v " + UInt64(42).value().toString())
    println("u64-add " + (UInt64(4294967296) + UInt64(4294967296)).toInt64().toString())
    println("u64-shr " + UInt64(4294967296).shr(32).toString())
    return 0
}
