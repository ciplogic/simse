package fixtures

import linq

// The LINQ-style pipeline (src/modules/linq/linq.kt): every operator takes and hands out
// a `..*T` machine, so a chain copies no element and each lambda reads the element where
// it lives. The entry is the list's own `iterPtr()` - the same walk the `for` rewrite
// spells `spanOf(xs).iterPtr()` - and a chain ends in a `for`, whose variable binds each
// element's place, or in `toList`.
//
// Lambdas are the point of the case: `select`'s result type is the one the lambda's body
// infers, so the call spells its template arguments (`select<Int, Int, ...>`) with
// nothing to deduce through a `std::function`.

fun makeWords(): List<Str> {
    var out: List<Str> = List<Str>()
    out.append("alpha")
    out.append("beta")
    out.append("gamma")
    return out
}

// One space-separated row, no trailing space: a golden is easier to read that way.
fun printRow(values: *List<Int>): Unit {
    var i: Int = 0
    while (i < values.size()) {
        if (i > 0) {
            print(" ")
        }
        print(values[i].toString())
        i = i + 1
    }
    println("")
}

fun main(): Int {
    var xs: List<Int> = List<Int>()
    xs.append(1)
    xs.append(2)
    xs.append(3)
    xs.append(4)
    xs.append(5)

    // select: a lambda from `*Int` to `Int`; the loop variable is the place.
    var first: Bool = true
    for (y in xs.iterPtr().select((x: *Int) -> *x * 2)) {
        if (!first) {
            print(" ")
        }
        print((*y).toString())
        first = false
    }
    println("")

    // where + take: stop at the first accepted element.
    for (y in xs.iterPtr().where((x: *Int) -> *x > 2).take(1)) {
        println((*y).toString())
    }

    // skip + where + select, drained by `toList`.
    val picked: List<Int> = xs.iterPtr().skip(1).where((x: *Int) -> *x % 2 == 0).select((x: *Int) -> *x * 10).toList()
    printRow(*picked)

    // A `*Str` element read in place: `w.size()` copies nothing; the chain's own
    // elements are places too, so the drain copies once, at the end.
    printRow(*makeWords().iterPtr().select((w: *Str) -> w.size()).toList())

    // A view's machine: the same operators over a string's bytes.
    val text = "a b c"
    var letters: Int = 0
    for (ch in spanOfStr(text).iterPtr().where((c: *Char) -> *c != ' ')) {
        letters = letters + 1
    }
    println(letters.toString())

    // An array's block through its span, and a `take` that runs past the end.
    var arr: Array<Int> = xs.toArray()
    var total: Int = 0
    for (y in spanOfArray(arr).iterPtr().select((x: *Int) -> *x + 1).take(9)) {
        total = total + *y
    }
    println(total.toString())

    // An empty chain: a filter nothing passes.
    val none: List<Int> = xs.iterPtr().where((x: *Int) -> *x > 100).toList()
    println(none.size().toString())
    return 0
}
