package diagnostic_type_return

fun f(): Int {
    return "a"
}

fun main(): Int {
    println(f())
    return 0
}
