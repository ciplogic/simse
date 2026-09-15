package arrayops

// `Array<T>` is one allocation with the element count first and the elements
// after it (specs/built-in-types.md). This case pins the surface that goes with
// that layout: `count()`, indexing, `toArray`/`toList`, and `arrayEmpty<T>()`,
// whose empty array is the shared one rather than a fresh allocation.

data class Point(var x: Int, var y: Int)

fun texts(): List<Str> {
    var texts: List<Str> = List<Str>()
    texts.append("alpha")
    texts.append("beta")
    return texts
}

fun main(): Int {
    var values: List<Int> = List<Int>()
    values.append(10)
    values.append(20)
    values.append(30)

    val arr: Array<Int> = values.toArray()
    println(arr.count())
    println(arr[0] + arr[1] + arr[2])

    // The handle shares the block: a write through one name is visible through
    // the other.
    var alias: Array<Int> = arr
    alias[1] = 99
    println(arr[1])

    // The empty array is shared and zero-length: nothing is allocated for it.
    val empty: Array<Int> = arrayEmpty<Int>()
    println(empty.count())

    // The empty *list* converges on the same empty array: `toArray` of a list with
    // no elements allocates nothing (`simse_list_toArray`).
    var nothing: List<Int> = List<Int>()
    val emptyAgain: Array<Int> = nothing.toArray()
    println(emptyAgain.count())

    // An array is fixed length, so adding an element goes through a list.
    var growable: List<Int> = arr.toList()
    growable.append(40)
    val grown: Array<Int> = growable.toArray()
    println(grown.count())
    println(grown[3])

    // Element types with fields, and element types that own heap bytes.
    var points: List<Point> = List<Point>()
    points.append(Point(1, 2))
    points.append(Point(3, 4))
    val pointArray: Array<Point> = points.toArray()
    println(pointArray.count())
    println(pointArray[0].x + pointArray[1].y)

    val names: Array<Str> = texts().toArray()
    println(names.count())
    println(names[1])
    return 0
}
