package fixtures

// `for (*x in c)` and `for ((*x, i) in c)`: the pointer forms of `for`. The loop
// variable is a *pointer to the element* (a `*T`, the prelude's `smToYieldPtr`) rather
// than a copy of it, so a loop over a container of aggregates copies nothing per
// iteration and a mutation through the loop variable reaches the container
// (impl_specs/for.md). A pointer to an aggregate reads through itself (`cell.value`),
// a pointer to a scalar is read with `*value`.

data class Cell(var value: Int)

// The plain pointer form: the mutation through `cell` reaches the list.
fun bump(cells: *List<Cell>): Int {
    var total: Int = 0
    for (*cell in cells) {
        cell.value = cell.value + 1
        total = total + cell.value
    }
    return total
}

// The indexed pointer form: the place and the index in one loop.
fun report(cells: *List<Cell>): Unit {
    for ((*cell, i) in cells) {
        println(i.toString() + ":" + cell.value.toString())
    }
}

// Over an array: the array is a copy of the list, so its mutations stay in it.
fun arrayBump(items: *List<Cell>): Int {
    val cells: Array<Cell> = items.toArray()
    var total: Int = 0
    for (*cell in cells) {
        cell.value = cell.value * 2
        total = total + cell.value
    }
    return total
}

// Over borrowed storage: a scalar element is read through the pointer with `*`.
fun spanSum(items: *List<Int>): Int {
    val span: Span<Int> = spanOf(items)
    var total: Int = 0
    for (*value in span) {
        total = total + * value
    }
    return total
}

fun main(): Int {
    val cells: List<Cell> = List<Cell>()
    cells.append(Cell(1))
    cells.append(Cell(2))
    cells.append(Cell(3))

    println(bump(*cells).toString())
    report(*cells)
    println(cells[0].value.toString())

    println(arrayBump(*cells).toString())
    println(cells[0].value.toString())

    val numbers: List<Int> = List<Int>()
    numbers.append(4)
    numbers.append(7)
    numbers.append(9)
    println(spanSum(*numbers).toString())
    return 0
}
