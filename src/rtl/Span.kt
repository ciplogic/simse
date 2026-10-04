// Span.kt
//
// `Span<T>` is a borrowed view over a contiguous run of `T`: a pointer and a length, owning
// nothing (specs/containers.md), valid only while its memory is alive and unmodified; a
// `spanOf(items)` borrow must outlive the span. Codegen maps the type onto the RTL one, so
// these bodies document it, not emit.

package rtl

// The type's C++ is the hand-written `span.hpp`, already `#include`d: `@SmGen("cpp")` is
// the materialization marker (specs/attributes.md), so the emitter must not generate the
// struct. An *unmarked* prelude type would be generated from its declaration instead.
// The class-body members below document the header's members; the *indexers* are the
// `operator` extensions at the end, which are emitted (an extension with a body is a
// function like any other), so a program's `span[index]` lowers through `get`/`set`
// rather than the header's `operator[]`.
@SmGen("cpp")
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
