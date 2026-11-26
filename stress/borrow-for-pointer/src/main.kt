package fixtures

// A `for (*p in c)` over a *by-value* parameter: `p` is an address into the container's
// storage, so a write through it is a write into that parameter. The borrow pass has to
// see that - the parameter stays by value, the write reaches the copy, and the caller's
// list is untouched (the by-value boundary owes 2). `borrow-mutates-param` pins the same
// rule for a `mutates`-marked receiver call; this is the pointer-element shape.
//
// The `for` desugar steps its machine with `tryAdvance(&_sm_item<n>)` and binds the loop
// variable off that slot (ParserStmt.parseFor). The slot is declared with no initializer,
// so on its own it reads as storage the body owns - and a classifier that stops there
// borrows `items` and this prints 102: the first cut of that template did
// (`bpClassifyPlace` gives the slot the provenance of its machine).

fun bump(p: *Int): Unit {
    *p = *p + 100
}

fun touch(items: List<Int>): Int {
    for (*p in items) {
        if (*p == 2) {
            bump(p)
        }
    }
    return items.size()
}

// The control: a list the body built is the body's own, whatever the loop writes through.
fun buildAndBump(): Int {
    var own: List<Int> = listOf(1, 2, 3)
    for (*p in own) {
        bump(p)
    }
    return own[1]
}

fun main(args: List<Str>): Unit {
    var mine: List<Int> = listOf(1, 2, 3)
    val size: Int = touch(mine)
    println((size * 1000 + mine[1]).toString())
    println(buildAndBump().toString())
}
