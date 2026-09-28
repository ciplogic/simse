// intrinsics.kt
//
// The machine primitives (cppsrc/rtl/intrinsics.hpp): the only operations the RTL keeps in
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
fun memCompare(a: *Char, aIndex: Int, b: *Char, bIndex: Int, count: Int): Int

// The index of the first `value` in the `count` bytes from `from`, or -1.
@SmGen("cpp", "simse_mem_findByte")
fun memFindByte(a: *Char, from: Int, count: Int, value: Char): Int

// A `Str`'s bytes, borrowed: valid until the string changes. It is the one place a `Str`'s
// internals are reached, and the reason the text operations can be written in the language.
@SmGen("cpp", "simse_str_data")
data fun strBytes(text: *Str): *Char

// A string's bytes replaced by a copy of a byte range: the owned-copy primitive. One
// resize and one block copy (`memCopy`), where a per-byte `append` would walk the bytes.
@SmGen("cpp", "simse_str_setBytes")
fun setBytes(this: Str, src: *Char, srcIndex: Int, count: Int): Unit
