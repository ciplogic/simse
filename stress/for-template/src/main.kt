package fixtures

// Every shape the `for` desugar takes (ParserStmt.parseFor: a machine, an out slot
// `_sm_item<n>` its `tryAdvance` fills, and the loop variable bound off that slot), with the
// for-walk on (`compiler-args`) so the walked and the machine-driven forms are both
// exercised: the value form's walk takes the slot over as its counter, the indexed form's
// drops it and uses `_sm_index<n>`, and a loop the walk refuses keeps the machine.
//
// `continue` jumps to the condition, so the indexed form pre-increments as the body's first
// statement - the index skipped here is 1, not 2. `break` leaves at the index shown.

fun main(args: List<Str>): Unit {
    val xs: List<Int> = listOf(10, 20, 30, 40)
    var out: Str = ""

    // Indexed over a list, walked: `continue` skips index 1.
    for ((v, i) in xs) {
        if (i == 1) {
            continue
        }
        out = out + i.toString() + ":" + v.toString() + " "
    }
    println(out)

    // Indexed over a range, walked as the counter.
    out = ""
    for ((v, i) in 0 until 3) {
        out = out + i.toString() + "=" + v.toString() + " "
        if (i == 1) {
            break
        }
    }
    println(out)

    // The inclusive range.
    out = ""
    for (i in 0..3) {
        out = out + i.toString() + " "
    }
    println(out)

    // The machine stays - the body grows the list it iterates, so the storage is not
    // stable - and it read the length when it was built: two iterations, whatever the
    // appends do.
    out = ""
    var ys: List<Int> = listOf(1, 2)
    for ((v, i) in ys) {
        ys.append(9)
        out = out + i.toString() + "/" + v.toString() + " "
        if (ys.size() > 4) {
            break
        }
    }
    println(out)

    // The pointer form: each element's place, written through.
    out = ""
    for (*p in xs) {
        *p = *p + 5
        out = out + (*p * 2).toString() + " "
    }
    println(out)

    // Nested, both indexed: the inner loop iterates the outer's loop variable, so the
    // variable has to carry its type from the slot (`forItemMachine`).
    val grid: List<List<Int>> = listOf(listOf(1, 2), listOf(3, 4, 5))
    var cells: Int = 0
    for ((row, r) in grid) {
        for ((c, ci) in row) {
            cells = cells + c * 10 + r + ci
        }
    }
    println(cells.toString())

    println("done")
}
