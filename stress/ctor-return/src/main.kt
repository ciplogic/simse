package ctorreturn

// `return (x)` / `return ()` and `var x = T(a, b)`: `initByValue` constructs the type. It is
// an extension on the instance (it sets the receiver, returns nothing); `Opt<Int>` and `Str`
// provide it in the RTL, and a user type declares its own.

data class Point(var x: Int, var y: Int)

fun Point.initByValue(x: Int, y: Int): Unit {
    this.x = x
    this.y = y
}

fun three(): Opt<Int> {
    return (3)
}

fun nothing(): Opt<Int> {
    return ()
}

fun pair(): Point {
    return (4, 5)
}

fun main() {
    val a = three()
    val b = nothing()
    if (a.hasValue()) {
        println(a.value())
    }
    println(b.hasValue())
    val p = pair()
    println(p.x)
    println(p.y)
    var q = Point(7, 8)
    println(q.x)
    println(q.y)
    var s = Str("abc")
    println(s)
    var empty = Str()
    println(empty.size())
    // An explicit type is the ordinary constructor, not the convention.
    var typedStr: Str = Str("xyz")
    println(typedStr)
    var boxed: Point = Point(1, 2)
    println(boxed.y)
}
