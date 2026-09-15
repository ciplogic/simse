package flow

// Nested loops with break/continue, an if/else chain, a switch and a loop whose
// condition is a literal: the shapes the linear lowering has to break into
// labels, gotos and conditional jumps.

fun classify(n: Int): Str {
    switch (n) {
        case 0:
            return "zero"
        case 1:
            return "one"
        case 2:
            return "two"
        default:
            return "many"
    }
}

fun main(): Int {
    var total: Int = 0
    var row: Int = 0
    while (row < 4) {
        var col: Int = 0
        while (col < 4) {
            col = col + 1
            if (col == 2) {
                continue
            }
            if (row == 3) {
                break
            }
            total = total + 1
        }
        row = row + 1
    }
    println(total)

    println(classify(0))
    println(classify(1))
    println(classify(2))
    println(classify(9))

    var i: Int = 0
    while (i < 10) {
        if (i > 2 && i < 5) {
            println("mid " + i.toString())
        }
        i = i + 1
    }

    var spins: Int = 0
    while (true) {
        spins = spins + 1
        if (spins >= 3) {
            break
        }
    }
    println(spins)
    return 0
}
