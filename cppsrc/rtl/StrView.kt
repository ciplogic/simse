// StrView.kt
//
// `StrView` is a `Span<Char>` under a second name: one type, and the text operations below
// are what the name adds (specs/built-in-types.md). It owns nothing, so it is valid only
// while the bytes it points at are alive; `spanOfStr(text)` borrows its source, which must
// outlive the view.
//
// `slice` stays a view; `substr`/`toString` are the owned copies.

package rtl

typealias StrView = Span<Char>

@SmGen("res", "strview", "simse_strView_size")
fun size(this: StrView): Int

@SmGen("res", "strview", "simse_strView_isEmpty")
fun isEmpty(this: StrView): Bool

// The byte at `index` is the span's own `at` (`Span<T>.at`); a view is a `Span<Char>`, so
// declaring it here too would be one operation under two names.

// From `start` to the end (unchecked).
@SmGen("res", "strview", "simse_strView_slice")
fun slice(this: StrView, start: Int): StrView

// `count` bytes from `start` (unchecked).
@SmGen("res", "strview", "simse_strView_slice")
fun slice(this: StrView, start: Int, count: Int): StrView

// The byte at `index` (unchecked); spelled like `Str.charAt`.
@SmGen("res", "strview", "simse_strView_charAt")
fun charAt(this: StrView, index: Int): Char

@SmGen("res", "strview", "simse_strView_startsWith")
fun startsWith(this: StrView, text: Str): Bool

// The same comparison against a text this view does not own, by raw pointer and with its
// length already known; `startsWith` would copy the `Str` first.
@SmGen("res", "strview", "simse_strView_startsWithPtr")
fun startsWithPtr(this: StrView, text: *Str, length: Int): Bool

// The index of the first occurrence of `sub`, or -1 (compared in place).
@SmGen("res", "strview", "simse_strView_find")
fun find(this: StrView, sub: Str): Int

// `indexOf` is the other spelling of `find`.
@SmGen("res", "strview", "simse_strView_indexOf")
fun indexOf(this: StrView, sub: Str): Int

// The owned copy of `count` bytes from `from`, clamped like `Str.substr`.
@SmGen("res", "strview", "simse_strView_substr")
fun substr(this: StrView, from: Int, count: Int): Str

// The owned copy of the whole view, as a `Str`.
@SmGen("res", "strview", "simse_strView_toString")
fun toString(this: StrView): Str

// A view over a string's bytes, borrowing the string (which must outlive the view).
@SmGen("res", "strview", "simse_spanOfStr")
fun spanOfStr(text: *Str): StrView
