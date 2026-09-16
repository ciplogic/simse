package fixtures

// A `*T` parameter takes the *argument's* address, so what the callee writes is what
// the argument named - a local, an element of a list, a field of an object. The
// conversion the compiler does at the call (`specs/functions.md`, "Handles at a
// call") has to keep a place a place: binding `cells[0]` to a temporary first would
// hand the callee the address of a copy and the write would be lost (silently - the
// emitted C++ still compiles).

data class Cell(var value: Int)

data class Grid(var cell: Cell)

fun bump(value: *Int): Unit {
    *value = * value +1
}

fun bumpCell(cell: *Cell): Unit {
    cell.value = cell.value + 1
}

fun main(): Int {
    // A local.
    var local: Int = 1
    bump(local)
    println(local.toString())             // 2

    // An element of a list: the list is the caller's, and it changes.
    val cells: List<Cell> = listOf(Cell(1), Cell(2))
    bumpCell(cells[0])
    println(cells[0].value.toString())    // 2

    // A field of an object.
    val grid: Grid = Grid(Cell(5))
    bumpCell(grid.cell)
    println(grid.cell.value.toString())   // 6

    // A field of an element: two levels, still the caller's storage.
    bump(cells[1].value)
    println(cells[1].value.toString())    // 3

    // The same call written with an explicit `*` is the same address, not a second
    // copy: both spellings reach the caller's value.
    bump(*local)
    println(local.toString())             // 3
    return 0
}
