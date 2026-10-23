package fixtures

// A place address does not survive a container-mutating call (specs/memory-model.md,
// "Automatic dereference"; impl_specs/expr-reuse.md): `xs[i].field` read, an append that
// may move the buffer, then `xs[i].field` written - the write must re-locate the place
// after the append, never store through the pre-append address. This is the shape that
// crashed the graph benchmark: the expression-reuse pass merged the two `&xs[i]`
// computations across the append, and the store landed in the freed old allocation.

data class Cell(var v: Int)

fun main(): Int {
    var xs: List<Cell> = List<Cell>(0)
    var i: Int = 0
    while (i < 200) {
        xs.append(Cell(i))
        i++
    }
    var round: Int = 0
    while (round < 100) {
        val head: Int = xs[5].v
        xs.append(Cell(round))
        xs[5].v = head + 1
        round++
    }
    println(xs[5].v)
    println(xs.size())
    return 0
}
