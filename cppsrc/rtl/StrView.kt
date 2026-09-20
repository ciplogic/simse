// StrView.kt
//
// `StrView` is the view a string's bytes are read through, and it is a `Span<Char>`: the
// alias below is the whole declaration, so a `Span<Char>` a program holds is a `StrView`
// and the other way round - one type with two names, and the text operations below are
// what the second name adds to it (specs/built-in-types.md, "Views"). It owns nothing and
// copies nothing, so it is valid only while the bytes it points at are alive and
// unmodified - what `FileStream.readLineView()` hands back is valid until the next read on
// that stream.
//
// The C++ type and every operation below live in cppsrc/rtl/strview.hpp (the type and the
// literal interop) and the `strview` section of cppsrc/rtl/_res.md (the operations); each
// declaration below reaches its symbol there with `@SmGen("res", "strview", ...)`, and the
// symbol is also what gives the emitter its receiver and return types, so
// `view.slice(0, n).toString()` and `"x" + view.toString()` emit correctly.
//
// `slice` stays a view (the parsing idiom: split a buffer without allocating),
// `substr`/`toString` are the owned copies. A view **borrows** its source
// (`spanOfStr(text)`): the source must outlive the view.

package rtl

typealias StrView = Span<Char>

// The number of bytes in the view.
@SmGen("res", "strview", "simse_strView_size")
fun size(this: StrView): Int

// True when the view covers no bytes.
@SmGen("res", "strview", "simse_strView_isEmpty")
fun isEmpty(this: StrView): Bool

// The byte at `index` is *not* declared here: `at` is the span's own member
// (`Span<T>.at`, Span.kt) and a view is a `Span<Char>`, so the two would be one
// operation under two names - `view.at(i)`, a value like any other `T`.

// From `start` to the end (C# `Slice(int)`; unchecked).
@SmGen("res", "strview", "simse_strView_slice")
fun slice(this: StrView, start: Int): StrView

// `count` bytes from `start` (C# `Slice(int, int)`; unchecked).
@SmGen("res", "strview", "simse_strView_slice")
fun slice(this: StrView, start: Int, count: Int): StrView

// The byte at `index`, spelled like `Str.charAt` (unchecked).
@SmGen("res", "strview", "simse_strView_charAt")
fun charAt(this: StrView, index: Int): Char

// True when the view begins with `text`.
@SmGen("res", "strview", "simse_strView_startsWith")
fun startsWith(this: StrView, text: Str): Bool

// `startsWithPtr(text, length)`: the same comparison against text this view does not
// own, by raw pointer and with its length already known. `startsWith` would copy the
// `Str` first, which is what a table lookup cannot afford; the first byte is the
// caller's cheap test, this does the rest.
@SmGen("res", "strview", "simse_strView_startsWithPtr")
fun startsWithPtr(this: StrView, text: *Str, length: Int): Bool

// The index of the first occurrence of `sub` in the bytes, or -1 (compared in place;
// nothing is copied).
@SmGen("res", "strview", "simse_strView_find")
fun find(this: StrView, sub: Str): Int

// `indexOf` is the other spelling of `find`.
@SmGen("res", "strview", "simse_strView_indexOf")
fun indexOf(this: StrView, sub: Str): Int

// The owned copy of `count` bytes from `from` (clamped like `Str.substr`).
@SmGen("res", "strview", "simse_strView_substr")
fun substr(this: StrView, from: Int, count: Int): Str

// The owned copy of the whole view, as a `Str`.
@SmGen("res", "strview", "simse_strView_toString")
fun toString(this: StrView): Str

// A view over a string's bytes, borrowing the string (which must outlive the view).
@SmGen("res", "strview", "simse_spanOfStr")
fun spanOfStr(text: *Str): StrView
