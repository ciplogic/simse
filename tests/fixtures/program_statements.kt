package fixtures
fun compute(n: Int): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < n) {
        if (i == 3) {
            i = i + 1
            continue
        }
        total = total + i
        i = i + 1
    }
    if (total > 10) {
        return total
    } else {
        return -total
    }
}

fun loopForever(): Unit {
    while (true) {
        break
    }
}
