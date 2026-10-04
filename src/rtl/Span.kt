// Span.kt
//
// `Span<T>` is a borrowed view over a contiguous run of `T`: a pointer and a length, owning
// nothing (specs/containers.md), valid only while its memory is alive and unmodified; a
// `spanOf(items)` borrow must outlive the span. The struct is generated from the
// declaration below like any other data class (specs/attributes.md): the fields, and the
// class-body members as free functions taking the receiver by pointer. Hand-written C++ that
// names the type early - the `streams` module's `FileStream` header, the string table's
// decoder - forward-declares it in types.hpp.

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

    // From `start` to the end (unchecked). The pointer advances through the element's
    // address (`*ptr[index]`), the language's spelling of `ptr + start`.
    fun slice(start: Int): Span<T> {
        return Span<T>(*this.ptr[start], this.len - start)
    }

    // `count` elements from `start` (unchecked).
    fun slice(start: Int, count: Int): Span<T> {
        return Span<T>(*this.ptr[start], count)
    }
}

// A span over a list's elements, borrowing the list (which must outlive the span). The C++
// is generated from the `spanOf` resource section (impl_specs/generators.md).
@SmGen("res", "spanOf")
borrow fun spanOf<T>(items: *List<T>): Span<T>

// The same over an array's block: `for (x in array)` iterates this span
// (`impl_specs/for.md`), so the array and the list share one iterator machine. A name of its
// own, not a second `spanOf`: the extractor's plain-call resolution refuses two same-arity
// overloads (it disambiguates *members* by receiver, not arguments by type).
@SmGen("res", "spanOf", "simse_spanOf")
borrow fun spanOfArray<T>(items: *Array<T>): Span<T>

// `span.atPtr(index)`: the element at `index` as a *place* (`*T`), so a write through it
// reaches the source (the `*T` the class's own `auto` members cannot name). The body goes
// through the pointer field, not the index syntax: an index read through `operator get`
// is a *value* - the address of it would be the address of a temporary
// (specs/functions.md). A raw-pointer index is always a place (`isPointerIndex`).
fun Span<T>.atPtr<T>(index: Int): *T {
    return *this.ptr[index]
}

// The indexer, the Kotlin convention (specs/functions.md): `span[index]` is `get`, the
// element as a value. The class-body `at` is the same read; the language spells the
// subscript through here now.
operator fun Span<T>.get<T>(index: Int): T {
    return this.ptr[index]
}

// The assignable indexer: `span[index] = value` is `set`, a write through the borrowed
// pointer.
operator fun Span<T>.set<T>(index: Int, value: T): Unit {
    this.ptr[index] = value
}
