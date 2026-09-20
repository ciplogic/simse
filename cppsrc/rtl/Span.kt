// Span.kt
//
// `Span<T>` is a borrowed view over a contiguous run of `T`: a pointer and a length,
// nothing else (specs/containers.md, "Span<T>"). It owns nothing and copies nothing,
// so it is valid only while the memory it points at is alive and unmodified - the
// language's spelling of C#'s `Span<T>`, with the same indexing and `slice`.
//
// Declarations only: the concrete type and its members live in cppsrc/rtl/span.hpp,
// and codegen maps `Span<T>` onto the RTL type, so the bodies here are documentation,
// not emitted code. That is also why `slice` says only what the length becomes: the
// RTL advances the data pointer, which the language has no expression for.
//
// `Span<T>` stays uniform over `T`; the text-specific operations live on `StrView`
// (StrView.kt), which *is* a `Span<Char>`. Walk a list with a span:

// ```text
// var span: Span<Int> = spanOf(*items)
// while (!span.isEmpty()) {
//     process(span[0])
//     span = span.slice(1)
// }
// ```
//
// A span **borrows** its source (`spanOf(items)`): the source must outlive the span,
// and `&items` would box a *copy* instead of borrowing.

package rtl

data class Span<T>(var ptr: *T, var len: Int) {
    // The number of elements.
    fun size(): Int {
        return this.len
    }

    // True when the span covers nothing.
    fun isEmpty(): Bool {
        return this.len <= 0
    }

    // The element at `index` (unchecked; `span[index]` is the same thing).
    fun at(index: Int): T {
        return this.ptr[index]
    }

    // From `start` to the end (C# `Slice(int)`; unchecked).
    fun slice(start: Int): Span<T> {
        return Span<T>(this.ptr, this.len - start)
    }

    // `count` elements from `start` (C# `Slice(int, int)`; unchecked).
    fun slice(start: Int, count: Int): Span<T> {
        return Span<T>(this.ptr, count)
    }
}

// A span over a list's elements, borrowing the list (which must outlive the span).
//
// The C++ is *generated* (impl_specs/generators.md): the `spanOf` resource section of
// cppsrc/rtl/_res.md holds the declaration and the definition, and this declaration
// names it. The spelling before generators existed was `native("simse_spanOf")`.
@SmGen("res", "spanOf")
fun spanOf<T>(items: *List<T>): Span<T>

// `span.atPtr(index)`: the element at `index` as a *place* (`*T`) - the same element `at`
// hands back as a value, reached by address, so nothing is copied and a write through it
// reaches the span's source. It is the language's own body rather than a C++ member
// (`min`/`max` in rtl.kt, `iter` below are the same shape): the emitter reifies it
// per instantiation, and *the call site has a type* - `*T` - where the class's own
// methods are emitted as members whose type the rules cannot name (`at`, `size`, `slice`
// print as `auto`). `for (*x in xs)` is this same `*this[i]` (impl_specs/for.md).
//
// The body goes through the span's own index - `this[index]` - and not `this.ptr[index]`:
// the *subscript* is the place the emitter can take the address of (`simse_addressOf`),
// while `*` on an index of a raw pointer is dropped by the lowering today (guide4ai.md §9).
fun Span<T>.atPtr<T>(index: Int): *T {
    return * this[index]
}
