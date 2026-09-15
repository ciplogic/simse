package app

import util
import shapes

// Calls same-named methods on types from two different packages, and a plain
// function from one of them.

fun main(): Int {
    val point: Point = Point(3, 4)
    val rect: Rect = Rect(5, 2)
    println(point.describe())
    println(rect.describe())
    println(twice(21))
    println(area(rect))
    return 0
}
