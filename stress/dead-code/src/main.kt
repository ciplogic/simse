package deadcode

fun compute(): Int {
    println("called")
    return 3
}

fun main(): Int {
    var unusedText: Str = "gone"
    var b: Int = 1
    var c: Int = 2
    var a: Int = b + c
    var dropped: Int = compute()
    var live: Int = 5
    println(live)
    return 0
}
