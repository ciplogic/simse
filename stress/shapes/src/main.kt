package fixtures
enum class Shape {
    Circle,
    Square = 4
}

fun Str.describe(): Str {
    return this
}

fun area(width: Int, height: Int): Int {
    return width * height
}

fun main(): Int {
    val name: Str = "box"
    println(name.describe())
    println(area(3, 4))
    println(Shape.Circle == Shape.Circle)
    println('a')
    return 0
}
