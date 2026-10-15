package main

// Inheritance: Point3D adds `z` at the end of Point2D's `x`, `y`, so a Point3D stands
// where a Point2D is expected - by value (a copy of the prefix), by raw pointer (the same
// address; the derived layout starts with the base's), and the base's own method still
// resolves on the derived receiver.

data class Point2D(val x: Int, val y: Int) {
    fun movedBy(dx: Int, dy: Int): Point2D {
        return Point2D(this.x + dx, this.y + dy)
    }

    // A *method* argument that is a point and a comparison against it: the argument is a
    // value position like any other. `between` is a method, so the conversion cannot come
    // from the call site's own spelling - the extractor builds it (LinearFormExpr).
    fun between(other: Point2D): Int {
        return (this.x - other.x) + (this.y - other.y)
    }
}

data class Point3D(val z: Int) : Point2D

// A base-typed result: the returned derived value is copied into a Point2D.
fun flattened(p: Point3D): Point2D {
    return p
}

// A managed field (`Str`) on the base: it flattens into the derived struct like any other
// field, and a value conversion copies it (the copy is independent of the source).
data class Named(val name: Str)

data class Person(val age: Int) : Named

fun nameOf(n: Named): Str {
    return n.name
}

fun partManaged(): Unit {
    var p = Person("ada", 36)
    println(nameOf(p))
    val n: Named = p
    p.name = "grace"
    println(n.name)
    println(p.name)
}

// A handle source: a `&Derived` where a base *value*, a `*Base` or a `&Base` is expected.
// The first reads through the box, the second is the box's pointer (cast, `.get()`), and
// the third boxes a snapshot copy of the prefix.
fun partHandleSource(): Unit {
    val hd: &Point3D = &Point3D(4, 5, 6)
    println(asValue(hd))
    println(sumViaPointer(hd))
    val hb: &Point2D = hd
    println(hb.x + hb.y)
    println(hd.z)
}

fun asValue(p: Point2D): Int {
    return p.x * 100 + p.y * 10
}

fun sumViaPointer(p: *Point2D): Int {
    return p.x + p.y
}

fun bump(p: *Point2D): Unit {
    p.x = p.x + 1
}

fun boxed(p: &Point2D): Int {
    val q: *Point2D = *p
    return q.x + q.y
}

fun main(): Int {
    var a = Point3D(1, 2, 3)
    println(asValue(a))
    println(sumViaPointer(*a))
    bump(*a)
    println(a.x)
    println(a.y)
    println(a.z)
    val m = a.movedBy(10, 10)
    println(m.x)
    println(m.y)
    var q3: *Point3D = *a
    println(sumViaPointer(q3))
    val h: &Point2D = &a
    println(boxed(h))
    var b: Point2D = a
    bump(*b)
    println(b.x)
    println(a.x)
    println(m.between(a))
    val f: Point2D = flattened(a)
    println(f.x)
    println(f.y)
    partManaged()
    partHandleSource()
    return 0
}
