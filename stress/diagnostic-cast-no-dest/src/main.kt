package diagnostic_cast_no_dest

fun main(): Int {
    val h: &Int = &Int(5)
    h.getAs<Int>()
    return 0
}
