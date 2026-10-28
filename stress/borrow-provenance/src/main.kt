package borrowprovenance

// The result-provenance fixpoint (`impl_specs/escape-analysis.md`, the named follow-up to
// the write-signature change): a callee the write fixpoint proved borrow-clean earns a
// *provenance entry* when every return classifies to storage it built (`fresh`) or to one
// parameter position, and the classifier may then derive a caller's local from the call.
// The shapes this case pins, with the emission golden carrying the signatures:
//
// - `Tray.view` is proved clean and unmarked, and its result reaches its receiver: the
//   entry says position 0. `restock` binds that result and appends through it - before
//   the fixpoint the local was unattributable, the append refused the body, and `label`
//   was copied at every call; with the entry the write attributes to position 0 (the
//   receiver the caller passes as `T* self` anyway) and `label` borrows (`Str*`).
// - `buildTags` returns a list its body built: the entry says `fresh`, so `tally`'s local
//   is storage the body owns and `label` borrows.
// - `leakPool` is proved clean too (returning a file-level var writes nothing), but its
//   result is the static's storage: no entry, the local stays unattributable, and
//   `stain`'s append through it is the static write the fixpoint must see - `label`
//   stays by value (`Str`), pinned by the golden.

data class Tray(var tag: Str, var items: List<Str>)

fun Tray.view(): List<Str> {
    return this.items
}

fun Tray.restock(label: Str): Int {
    var held: List<Str> = this.view()
    held.append(label)
    return held.size()
}

fun buildTags(seed: Str): List<Str> {
    var out: List<Str> = List<Str>()
    out.append(seed)
    return out
}

fun tally(label: Str): Int {
    var made: List<Str> = buildTags(label)
    made.append(label)
    return made.size()
}

var pool: List<Str> = List<Str>()

fun leakPool(): List<Str> {
    return pool
}

fun stain(label: Str): Int {
    var leaked: List<Str> = leakPool()
    leaked.append(label)
    return leaked.size()
}

fun main(): Int {
    var tray: Tray = Tray("t", List<Str>())
    println(tray.restock("a"))
    println(tray.items.size())
    var mark: Str = "b"
    println(tally(mark))
    var spot: Str = "c"
    println(stain(spot))
    println(pool.size())
    return 0
}
