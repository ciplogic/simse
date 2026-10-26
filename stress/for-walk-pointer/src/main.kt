package for_walk_pointer

// The for-walk's pointer form (impl_specs/for-walk.md, `--for-walk`): `for (*v in xs)` binds
// the element's *place* - a write through the loop variable reaches the container - and the
// walk binds it through `atPtr` (the machine's own spelling, correct for every element
// type). This case pins the write-through on scalars, structs and Strs, the indexed
// pointer form, and the mix with an ordinary value walk over the same container.

data class Cell(var value: Int, var tag: Str)

fun bumpScalar(vals: List<Int>): Int {
    for (*v in vals) {
        *v = *v + 1
    }
    return vals[0]
}

fun bumpCells(cells: List<Cell>): Str {
    for (*cell in cells) {
        cell.value = cell.value * 2
    }
    var out: Str = ""
    for ((cell, at) in cells) {
        out = out + cell.tag + Int(cell.value).toString() + ","
        if (at > 100) {
            break
        }
    }
    return out
}

fun bumpIndexed(vals: List<Int>): Int {
    // The indexed pointer form: the place *and* the walk's own counter, no separate
    // `_sm_index` slot.
    var scale: Int64 = 0
    for ((*v, i) in vals) {
        *v = *v * 3
        scale += Int64(i)
    }
    println(scale)
    return vals[2]
}

// A container element: the place is the string's own, not its first byte (the shape that
// caught the extracted-`ptr` index spelling reading `Str[i]` as the `Char`).
fun shoutAll(texts: List<Str>): Str {
    for (*text in texts) {
        *text = *text + "!"
    }
    var out: Str = ""
    for (t in texts) {
        out = out + t
    }
    return out
}

fun main(): Int {
    var vals: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 5) {
        vals.append(i)
        i++
    }
    println(bumpScalar(vals))
    println(bumpScalar(vals))

    var cells: List<Cell> = List<Cell>()
    cells.append(Cell(1, "a"))
    cells.append(Cell(2, "b"))
    cells.append(Cell(3, "c"))
    println(bumpCells(cells))

    var more: List<Int> = List<Int>()
    more.append(4)
    more.append(5)
    more.append(6)
    println(bumpIndexed(more))

    // The same container walked by value afterwards sees the writes.
    var total: Int = 0
    for (x in more) {
        total += x
    }
    println(total)

    var texts: List<Str> = List<Str>()
    texts.append("go")
    texts.append("sim")
    println(shoutAll(texts))
    return 0
}
