package fixtures
fun useLater(): Int {
    val p: Point = Point(1, 2)
    return helper(p.x)
}

data class Point(var x: Int, var y: Int)

fun helper(value: Int): Int {
    return value + 1
}
