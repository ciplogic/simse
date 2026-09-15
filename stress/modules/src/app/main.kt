package app

import util

// Uses the `util` package: an imported function, and a data class with a method
// that is defined in another file of the same project.

fun describe(index: Int): Str {
    if (index == 0) {
        return "zero"
    }
    return index.toString()
}

fun main(): Int {
    var i: Int = 0
    while (i < 4) {
        println(describe(i) + "|" + twice(i).toString())
        i = i + 1
    }
    val point: Point = Point(3, 4)
    println(point.manhattan())
    return 0
}
