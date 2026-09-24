// Span.kt
//
// `Span<T>` is a borrowed view over a contiguous run of `T`: a pointer and a length, owning
// nothing (specs/containers.md), valid only while its memory is alive and unmodified.
// `spanOf(items)` borrows its source, which must outlive the span.
//
// Codegen maps `Span<T>` onto the RTL type, so these bodies document it, not emit.

package rtl

data class Span<T>(var ptr: *T, var len: Int) {
    fun size(): Int {
        return this.len
    }

    fun isEmpty(): Bool {
        return this.len <= 0
    }

    // Unchecked; `span[index]` is the same operation.
    fun at(index: Int): T {
        return this.ptr[index]
    }

    // From `start` to the end (unchecked).
    fun slice(start: Int): Span<T> {
        return Span<T>(this.ptr, this.len - start)
    }

    // `count` elements from `start` (unchecked).
    fun slice(start: Int, count: Int): Span<T> {
        return Span<T>(this.ptr, count)
    }
}

// A span over a list's elements, borrowing the list (which must outlive the span). The C++
// is generated from the `spanOf` resource section (impl_specs/generators.md).
@SmGen("res", "spanOf")
fun spanOf<T>(items: *List<T>): Span<T>

// `span.atPtr(index)`: the element at `index` as a *place* (`*T`), so a write through it
// reaches the source (the `*T` the class's own `auto` members cannot name). The body goes
// through `this[index]`, not `this.ptr[index]`: the *subscript* is the place the emitter can
// take the address of (`guide4ai.md` §9).
fun Span<T>.atPtr<T>(index: Int): *T {
    return * this[index]
}
