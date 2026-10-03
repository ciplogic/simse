// linq.kt
//
// A LINQ-style pipeline over machines (impl_specs/for.md, impl_specs/yield.md): the
// operators are extensions on the *pointer* machine a container's span hands out
// (`..*T`), so a chain copies no element - every lambda receives the element's place.
// Each operator is itself a machine: `select` maps, `where` filters, `take`/`skip`
// bound the walk, and `toList` drains the chain into a list.
//
// The receiver is stepped by hand (`this.advance()`), not with a `for`: a `for` machine
// would have to live across the operator's own yield, and that is a field a yielding
// body cannot hold yet (impl_specs/yield.md). The filter is `where`, not `when`: `when`
// is a statement keyword.
//
// A machine receiver makes the operator's machine a template over the source machine's
// class (`semMachineReceiver`), so the receiver's type parameter is listed in the
// operator's own `<...>` (`fun ..*T.select<T, U>`) - the same rule `Span<T>.iter<T>`
// follows.
//
// A pipeline is pointer-typed end to end: `for (p in chain)` binds each element's place
// (`*T`), so a read copies nothing, and `toList()` drains the chain into values when
// copies are wanted.

package linq

// A list as the pointer machine the operators take. A container's `for` is rewritten to
// its span's machine (`spanOf(xs).iterPtr()`, impl_specs/for.md); this is that same walk
// spelled as an ordinary extension, so a pipeline can start at the list itself.
fun List<T>.iterPtr<T>(): ..*T {
    var i: Int = 0
    val len = this.size()
    while (i < len) {
        yield * this[i]
        i = i + 1
    }
}

// `select`: each element through `f`, lazily, as a *pointer* machine like every other
// operator: the mapped value lives in a machine field across the yield, and the machine
// hands out its place (`..*U`), so a chain stays pointer-typed end to end and the next
// operator's lambda still reads in place. The place is valid until the next advance.
fun ..*T.select<T, U>(f: (*T) -> U): ..*U {
    while (this.advance()) {
        var mapped: U = f(this.current)
        yield *mapped
    }
}

// `where`: the elements `keep` accepts, lazily.
fun ..*T.where<T>(keep: (*T) -> Bool): ..*T {
    while (this.advance()) {
        if (keep(this.current)) {
            yield this.current
        }
    }
}

// `take`: at most `count` elements. The count is checked *before* the step, so the
// source is not consumed one element past what was handed out.
fun ..*T.take<T>(count: Int): ..*T {
    var taken: Int = 0
    while (taken < count) {
        if (!this.advance()) {
            break
        }
        yield this.current
        taken = taken + 1
    }
}

// `skip`: the elements after the first `count`.
fun ..*T.skip<T>(count: Int): ..*T {
    var skipped: Int = 0
    while (this.advance()) {
        if (skipped < count) {
            skipped = skipped + 1
        } else {
            yield this.current
        }
    }
}

// `toList`: drain the chain, copying each element once. The one operator that is not
// lazy, and the one that reads the element's *value* (`*this.current`).
fun ..*T.toList<T>(): List<T> {
    var out: List<T> = List<T>()
    while (this.advance()) {
        out.append(*this.current)
    }
    return out
}
