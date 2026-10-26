package for_walk

// ---- walk-value / walk-indexed / walk-continue / walk-nested / walk-text / walk-array ----

// The for-walk (impl_specs/for-walk.md, `--for-walk`): a `for` whose machine is the span's
// own lowers as the flat counter walk - the span bound once, a counter from 0, the element
// read through `span.ptr[i]` before the counter steps. This case runs it over every
// receiver shape the rewrite reaches (List, Str, Array, a Span outright, a for inside an
// `if` arm, nested fors) and every binding form (value, indexed, `continue` inside), with
// outputs the machine path must agree with byte for byte.

fun partWalkValue(): Int {
    var vals: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 8) {
        vals.append(i * 3)
        i++
    }
    var total: Int = 0
    for (x in vals) {
        total += x
    }
    println(total)
    return vals.size()
}

fun partWalkIndexed(): Int {
    var vals: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 6) {
        vals.append(i * i)
        i++
    }
    var total: Int64 = 0
    for ((x, at) in vals) {
        total += Int64(x) ^ Int64(at)
    }
    println(total)
    return vals.size()
}

// `continue` lands after the step: a skipped iteration still moves the walk on (`for.md`'s
// own lesson, the reason the step is not the body's last statement).
fun partWalkContinue(): Int {
    var vals: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 10) {
        vals.append(i)
        i++
    }
    var seen: Int = 0
    var oddSum: Int = 0
    for ((x, at) in vals) {
        if (x % 2 == 0) {
            continue
        }
        seen++
        oddSum += x
        if (at > 100) {
            break
        }
    }
    println(seen)
    println(oddSum)
    return 0
}

fun partWalkNested(): Int {
    var rows: List<List<Int>> = List<List<Int>>()
    var r: Int = 0
    while (r < 3) {
        var row: List<Int> = List<Int>()
        var c: Int = 0
        while (c < 4) {
            row.append(r * 10 + c)
            c++
        }
        rows.append(row)
        r++
    }
    var total: Int = 0
    for (row in rows) {
        for (x in row) {
            total += x
        }
    }
    println(total)
    return 0
}

fun partWalkText(): Int {
    var text: Str = "for-walk walks text"
    var count: Int = 0
    for (ch in text) {
        if (ch == 'w') {
            count++
        }
    }
    println(count)

    // A view is the same span the walk takes outright.
    val view: StrView = spanOfStr(text)
    var first: Char = '.'
    for (ch in view) {
        first = ch
        break
    }
    println(first)
    return 0
}

fun partWalkArray(): Int {
    var vals: List<Int> = List<Int>()
    vals.append(5)
    vals.append(6)
    vals.append(7)
    vals.append(8)
    val arr: Array<Int> = vals.toArray()
    var total: Int = 0
    for (x in arr) {
        total += x
    }
    println(total)
    return 0
}

fun partWalkInIf(): Int {
    var vals: List<Int> = List<Int>()
    var i: Int = 0
    while (i < 5) {
        vals.append(i + 1)
        i++
    }
    var total: Int = 0
    if (vals.size() > 2) {
        var doubled: Int = 0
        for (x in vals) {
            doubled = x * 2
            total += doubled
        }
        println(doubled)
    }
    println(total)
    return 0
}

// ---- walk-range ----

// A `Range` is spelled through the yieldable machinery but is semantically the counter:
// the walk is the machine's own body - bounds copied into the slot, the counter from
// `from`, the condition against `to` (inclusive; `until` folds to it), no machine class.
fun partWalkRange(): Int {
    var total: Int64 = 0
    for (i in 0 until 8) {
        total += Int64(i) * Int64(i)
    }
    println(total)

    var picked: Str = ""
    for (i in 2..6) {
        picked = picked + Int(i).toString()
    }
    println(picked)

    // The indexed range form: the walk's counter is the index, as over a span.
    var scale: Int64 = 0
    for ((i, k) in 0 until 5) {
        scale += Int64(i) * Int64(k)
    }
    println(scale)

    // `continue` lands after the step, as over a span.
    var odds: Int64 = 0
    for (i in 0 until 10) {
        if (i % 2 == 0) {
            continue
        }
        odds += Int64(i)
    }
    println(odds)
    return 0
}

fun main(): Int {
    partWalkValue()
    partWalkIndexed()
    partWalkContinue()
    partWalkNested()
    partWalkText()
    partWalkArray()
    partWalkInIf()
    partWalkRange()
    return 0
}
