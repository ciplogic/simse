package diagnostic_assign_target

fun twice(n: Int): Int { return n * 2 }

fun main(): Int {
    twice(1) = 3
    return 0
}
