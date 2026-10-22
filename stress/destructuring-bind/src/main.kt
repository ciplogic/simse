package fixtures

// `val (a, b) = e` (specs/declarations.md, "Destructuring bind"): Go's `a, b := vals()`
// bind. The value is held by one compiler-named temp; each binder is an ordinary
// declaration read from it - base fields first down an inheritance chain, `_` taking a
// slot without binding it, `var` making every binder it names reassignable.

data class Point(var x: Int, var y: Int) {
    fun movedBy(dx: Int, dy: Int): Point {
        return Point(this.x + dx, this.y + dy)
    }
}

data class Point3D(var z: Int) : Point

fun vals(): Point {
    return Point(3, 7)
}

fun main(): Int {
    val (a, b) = vals()
    println(a.toString() + " " + b.toString())                    // 3 7

    val (_, onlyY) = Point(10, 20)
    println(onlyY.toString())                                     // 20

    var (mx, my) = Point(1, 2)
    mx = mx + 40
    println(mx.toString() + " " + my.toString())                  // 41 2

    val (x, y, z) = Point3D(5, 6, 7)
    println(x.toString() + " " + y.toString() + " " + z.toString())   // 5 6 7  (x, y are the base's)

    val base: Point = Point(3, 7)
    val (nx, ny) = base.movedBy(1, 1)
    println(nx.toString() + " " + ny.toString())                  // 4 8

    if (true) {
        val (a, b) = vals()                                       // a fresh pair, in the block's scope
        println(a.toString() + " " + b.toString())                // 3 7
    }
    return 0
}
