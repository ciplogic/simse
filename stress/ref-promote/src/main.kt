package refpromote

// A counted reference (`&T`) is a heap box plus a refcount. When the handle never escapes and is
// bound once, the box is built on the *stack* and the handle becomes a raw pointer to it
// (impl_specs/escape-analysis.md): no allocation, no refcount traffic, and the value is destroyed
// with the scope. The pass refuses anything it cannot prove, so an escaping handle stays a box.

data class Cell(var value: Int) {
    fun add(n: Int): Unit {
        this.value = this.value + n
    }
}

// `c` never escapes: not returned, not stored, not passed, not `*`-ed, not reassigned, and used
// only through its own field. It becomes a stack `Cell` and a `Cell*`.
fun bump(): Int {
    var c: &Cell = &Cell(7)
    c.value = c.value + 1
    return c.value
}

// The handle is *returned*, so it escapes and stays a box (`makeRef`). The caller's `p` is not a
// construction either, so it stays a box too.
fun make(v: Int): &Cell {
    var c: &Cell = &Cell(v)
    return c
}

// A method receiver is safe when the receiver is declared by value (its C++ parameter is `T* self`,
// which a raw pointer is), and `&List<Int>()` is the `Box` shape: the value is already there, so
// pointing at it drops the copy the box made.

// `*c` hands out the raw pointer the counted reference already is, and mutating through it reaches
// the value the handle names.
fun point(): Int {
    var c: &Cell = &Cell(3)
    var p: *Cell = *c
    p.value = p.value + 1
    return c.value
}

fun main(): Int {
    println(bump())
    val p: &Cell = make(10)
    p.value = p.value + 5
    println(p.value)

    var c: &Cell = &Cell(1)
    c.add(4)
    println(c.value)

    var xs: &List<Int> = &List<Int>()
    xs.append(3)
    xs.append(4)
    println(xs[0] + xs[1])

    println(point())
    return 0
}
