// intrinsics.kt
//
// The machine primitives (src/rtl/intrinsics.hpp): the only operations the RTL keeps in
// C++, because Simse has no raw memory and no pointer arithmetic. A caller passes a base
// pointer and an index and the intrinsic does the one addition. Everything else in the RTL
// is written in the language over these.

package rtl

@SmGen("cpp", "simse_mem_copy")
fun memCopy(dst: *Char, dstIndex: Int, src: *Char, srcIndex: Int, count: Int): Unit

@SmGen("cpp", "simse_mem_fill")
fun memFill(dst: *Char, from: Int, count: Int, value: Char): Unit

// The three-way compare of `count` bytes: -1, 0 or 1.
@SmGen("cpp", "simse_mem_compare")
borrow fun memCompare(a: *Char, aIndex: Int, b: *Char, bIndex: Int, count: Int): Int

// The index of the first `value` in the `count` bytes from `from`, or -1.
@SmGen("cpp", "simse_mem_findByte")
borrow fun memFindByte(a: *Char, from: Int, count: Int, value: Char): Int

// The position of the highest set bit of `|value|` (the magnitude as an unsigned 64-bit
// pattern), plus one - one `clz`/`bsr`. `INT64_MIN` answers 64, because its magnitude is
// 2^63, which is exactly what the digit-counting scan over it wants; the unsigned
// negation lives here because the language has no unsigned arithmetic.
@SmGen("cpp", "simse_bit_width64")
data fun bitWidth64(value: Int64): Int

// A `Str`'s bytes, borrowed: valid until the string changes. It is the one place a `Str`'s
// internals are reached, and the reason the text operations can be written in the language.
@SmGen("cpp", "simse_str_data")
data fun strBytes(text: *Str): *Char

// A string's bytes replaced by a copy of a byte range: the owned-copy primitive. One
// resize and one block copy (`memCopy`), where a per-byte `append` would walk the bytes.
@SmGen("cpp", "simse_str_setBytes", "mutates")
fun setBytes(this: Str, src: *Char, srcIndex: Int, count: Int): Unit
