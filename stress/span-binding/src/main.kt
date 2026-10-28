package spanbinding

// The span binding (impl_specs/for-walk.md, "The span binding"): a storage-stable loop
// gets its container's span bound once in the preheader - and a *pointer* root may bind
// only when the whole loop is immobile: no growing call anywhere (the `grows` marks,
// whatever receiver), no write through a deref. The emission golden pins the four
// shapes: `fill`'s pointer-root store loop and `drain`'s read loop bind (`_sm_span<n>`
// derived once before the loop); `growy`'s append on another container and `rebindy`'s
// write through the deref keep the per-access `spanOfStr` spelling.

fun fill(dst: *Str, w: Int): Unit {
    var x: Int = 1
    while (x < w - 1) {
        dst[x] = Char(x & 127)
        x++
    }
}

fun drain(src: *Str): Int {
    var total: Int = 0
    var k: Int = 0
    while (k < src.size()) {
        total = total + Int(src[k])
        k++
    }
    return total
}

fun growy(dst: *Str, w: Int): Int {
    var junk: List<Int> = List<Int>()
    var x: Int = 0
    while (x < w) {
        dst[x] = Char(1)
        junk.append(x)
        x++
    }
    return junk.size()
}

data class Cell(var text: Str)

// A write through the pointer into a field rebinds the buffer a bound span would view:
// the member-write arm of the storage-stability rule refuses it before the binding is
// even weighed, and the golden pins that refusal standing (no `_sm_span` in `rebindy`).
fun rebindy(box: *Cell, w: Int): Unit {
    var x: Int = 0
    while (x < w) {
        box.text = "fresh"
        x++
    }
}

fun main(): Int {
    var a: Str = Str()
    var i: Int = 0
    while (i < 64) {
        a.append(Char(i & 127))
        i++
    }
    fill(a, 64)
    println(drain(a))
    var b: Str = Str("seed")
    println(growy(b, 3))
    var cell: Cell = Cell("seed")
    rebindy(cell, 2)
    println(cell.text)
    return 0
}
