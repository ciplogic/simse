package borrowmutatesparam

// A `mutates`-marked callee (`List.append`) is obligation-free only on a receiver the body
// itself built. On a receiver the body was handed - here a pointer parameter - the write
// through it is the body's own write and the body must refuse its proof: without the
// receiver check, `f` proved clean, `g` borrowed its `q` on the strength of that proof, and
// `f`'s append reached `main`'s list through the borrowed pointer (`mine.size()` printed
// 2 where the by-value boundary owes 1).
//
// `keep` is the positive control: its append's receiver is a local the body built, so the
// call imposes nothing and `items` still borrows.

fun f(p: *List<Str>): Unit {
    p.append("x")
}

fun g(q: List<Str>): Int {
    f(q)
    return q.size()
}

fun keep(items: List<Str>): Int {
    var scratch: List<Str> = List<Str>()
    scratch.append("x")
    return items.size() + scratch.size()
}

fun main(): Int {
    var mine: List<Str> = List<Str>()
    mine.append("one")
    println(g(mine))
    println(mine.size())
    println(keep(listOf<Str>("a", "b")))
    return 0
}
