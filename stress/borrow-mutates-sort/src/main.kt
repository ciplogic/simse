package borrowmutatessort

// `List.sort` reorders its receiver in place, so its declaration carries the `mutates`
// mark, not `borrow`: a `borrow` mark promised the fixpoint a read-only callee, a body
// calling `xs.sort(...)` on its own parameter proved clean on that promise, borrowed the
// parameter, and the reorder reached the caller's list through the by-value boundary.
// The other receiver-only mutators (`Dictionary.insert/remove/clear`,
// `List.removeAt/removeRange`, `Str.initByValue`) carry the mark for the same reason.
//
// `countsOf` is the positive control: a body-built dictionary with `insert` calls in the
// loop body still proves, so the walk over the words stays a place loop.

fun reorder(xs: List<Str>): Unit {
    xs.sort((left: *Str, right: *Str) -> (left.size() > right.size()))
}

fun countsOf(words: List<Str>): Int {
    var counts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    for (word in words) {
        val seen: Opt<Int> = counts.get(word)
        if (seen.hasValue()) {
            counts.insert(word, seen.value() + 1)
        } else {
            counts.insert(word, 1)
        }
    }
    return counts.size()
}

fun main(): Int {
    var mine: List<Str> = listOf<Str>("a", "ccc", "bb")
    reorder(mine)
    println(mine[0])
    println(countsOf(listOf<Str>("a", "b", "a")))
    return 0
}
