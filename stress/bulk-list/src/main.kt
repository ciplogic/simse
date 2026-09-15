package bulk

// Ten thousand elements: the list crosses from the inline buffer to the heap on
// the way, so this pins the growth path and the sums that read it back.

fun main(): Int {
    var values: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 10000) {
        values.append(i % 97)
        i = i + 1
    }
    println(values.size())

    var total: Int = 0
    i = 0
    while (i < values.size()) {
        total = total + values[i]
        i = i + 1
    }
    println(total)
    println(values[0])
    println(values[9999])
    return 0
}
