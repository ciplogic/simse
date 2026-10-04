package fixtures

import linq

// The LINQ-style pipeline (src/modules/linq/linq.kt): every operator takes and hands out
// a `..*T` machine, so a chain copies no element and each lambda reads the element where
// it lives. The entry is the list's own `iter()` - the same pointer walk the `for` rewrite
// spells `spanOf(xs).iter()` - and a chain ends in a `for`, whose variable binds each
// element's place, or in `toList`.
//
// `toValues()` and `toPtrs()` (the prelude's two conversions) switch a machine's element
// between a copy and a place: `select(...).toValues()` lets the loop read an `Int`, while
// `spanOf(xs).iterValues().toPtrs()` is the value walk again as places, ready for `where`
// and `select`.
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

// One `|`-separated row of split parts, so an empty part is visible (`a||b`).
fun printWords(values: *List<Str>): Unit {
    var i: Int = 0
    while (i < values.size()) {
        if (i > 0) {
            print("|")
        }
        print(values[i])
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
    for (y in xs.iter().select((x: *Int) -> *x * 2)) {
        if (!first) {
            print(" ")
        }
        print((*y).toString())
        first = false
    }
    println("")

    // where + take: stop at the first accepted element.
    for (y in xs.iter().where((x: *Int) -> *x > 2).take(1)) {
        println((*y).toString())
    }

    // skip + where + select, drained by `toList`.
    val picked: List<Int> = xs.iter().skip(1).where((x: *Int) -> *x % 2 == 0).select((x: *Int) -> *x * 10).toList()
    printRow(*picked)

    // A `*Str` element read in place: `w.size()` copies nothing; the chain's own
    // elements are places too, so the drain copies once, at the end.
    printRow(*makeWords().iter().select((w: *Str) -> w.size()).toList())

    // A view's machine: the same operators over a string's bytes.
    val text = "a b c"
    var letters: Int = 0
    for (ch in spanOfStr(text).iter().where((c: *Char) -> *c != ' ')) {
        letters = letters + 1
    }
    println(letters.toString())

    // An array's block through its span, and a `take` that runs past the end.
    var arr: Array<Int> = xs.toArray()
    var total: Int = 0
    for (y in spanOfArray(arr).iter().select((x: *Int) -> *x + 1).take(9)) {
        total = total + *y
    }
    println(total.toString())

    // An empty chain: a filter nothing passes.
    val none: List<Int> = xs.iter().where((x: *Int) -> *x > 100).toList()
    println(none.size().toString())

    // `toValues`: the chain's places as copies. `v` is an `Int`, so the loop adds values,
    // not places - one copy per element, taken where the machine yielded it.
    var sum: Int = 0
    for (v in xs.iter().select((x: *Int) -> *x * 2).toValues()) {
        sum = sum + v
    }
    println(sum.toString())

    // `iterValues` walks a span as values; `toPtrs` recovers the places, so the operators
    // take them again (`where` is `..*T`). One element at a time, no list in between.
    var odd: Int = 0
    for (p in spanOf(xs).iterValues().toPtrs().where((x: *Int) -> *x % 2 == 1)) {
        odd = odd + 1
    }
    println(odd.toString())
    // `splitIter`: the same split as `split`, one part at a time, and each part is a
    // `StrView` into the source - only `toString()` at the drain copies. Nothing is built
    // between the source and the drain, so a `where` upstream reads parts the drain never sees.
    printWords(*"a,bb,,ccc".splitIter(",").select((p: *StrView) -> p.toString()).toList())
    printWords(*"a,bb,,ccc".splitIter(",").where((p: *StrView) -> p.size() > 0).select((p: *StrView) -> p.toString()).toList())
    printWords(*"a,,b,".splitIter(",").select((p: *StrView) -> p.toString()).toList())
    printWords(*"solo".splitIter(",").select((p: *StrView) -> p.toString()).toList())
    printWords(*"abc".splitIter("").select((p: *StrView) -> p.toString()).toList())
    printWords(*"".splitIter(",").select((p: *StrView) -> p.toString()).toList())
    printWords(*"a::b::c".splitIter("::").select((p: *StrView) -> p.toString()).toList())
    var sizes: Int = 0
    for (n in "one,two,three".splitIter(",").select((p: *StrView) -> p.size())) {
        sizes = sizes + *n
    }
    println(sizes.toString())
    printWords(*"a,b,c,d".splitIter(",").take(2).select((p: *StrView) -> p.toString()).toList())

    // The byte-separator overload: one byte compare per position, no needle view at all.
    printWords(*"1;2;;3".splitIter(';').select((p: *StrView) -> p.toString()).toList())
    printWords(*"a;b;".splitIter(';').select((p: *StrView) -> p.toString()).toList())
    printWords(*"abc".splitIter(';').select((p: *StrView) -> p.toString()).toList())
    printWords(*"".splitIter(';').select((p: *StrView) -> p.toString()).toList())

    // ... and the lazy chain agrees element for element with `split`'s list.
    val fromSplit: List<Str> = "x,,y,".split(",")
    val fromIter: List<Str> = "x,,y,".splitIter(",").select((p: *StrView) -> p.toString()).toList()
    var same: Bool = fromSplit.size() == fromIter.size()
    var at: Int = 0
    while (at < fromSplit.size() && at < fromIter.size()) {
        if (fromSplit[at] != fromIter[at]) {
            same = false
        }
        at = at + 1
    }
    println(same.toString())

    // `forEach`: the effect terminal. Nothing is built or drained; the lambda receives each
    // element in place. Captures are by value, so the accumulator is a counted reference:
    // the copy shares its storage, so the appends are visible outside.
    xs.iter().where((x: *Int) -> *x > 3).forEach((x: *Int) -> println((*x).toString()))
    var sink: &List<Int> = &List<Int>()
    xs.iter().where((x: *Int) -> *x % 2 == 0).forEach((x: *Int) -> {
        sink.append(*x * 100)
    })
    printRow(*sink)

    return 0
}
