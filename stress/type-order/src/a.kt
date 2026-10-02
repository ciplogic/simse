package a

import z

// A field that holds another file's type *by value*: `a.kt` sorts before `z.kt`, so `Node`'s
// definition must be pulled in front of `Holder`'s - a forward declaration is not enough for a
// by-value field. Without the dependency-ordered emission the generated C++ reads
// "uses undefined struct ...", which is exactly what this case pins.

data class Holder(var n: Node)

fun main(): Int {
    val h: Holder = Holder(Node(3))
    println(h.n.x)
    return 0
}
