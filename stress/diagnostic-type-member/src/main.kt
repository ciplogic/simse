package diagnostic_type_member

data class Point(var x: Int, var y: Int)

fun main(): Int {
    val p = Point(1, 2)
    println(p.z)
    return 0
}
