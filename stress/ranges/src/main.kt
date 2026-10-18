package ranges

// ---- for-over-range ----

// `a..b` is `a.rangeTo(b)` (src/rtl/Range.kt): an inclusive ascending `Range` value, and
// `for (i in 1..5)` iterates it through the same `iterValues` convention a container's
// span walk takes (impl_specs/for.md). The range words - `until`, `downTo`, `step` - are
// the same sugar: `a until b`, `a downTo b`, `r step n`, each the extension method the
// parser spells, so a copied Kotlin `for` header needs no rewriting.

fun partForOverRange(): Int {
    var total: Int = 0
    for (i in 1..5) {
        total = total + i
    }
    println(total)

    // `until` excludes its upper bound: 0, 1, 2.
    var count: Int = 0
    for (i in 0 until 3) {
        count = count + 1
    }
    println(count)

    // `downTo` is inclusive on both ends: 5, 4, 3, 2, 1.
    var descending: Str = ""
    for (i in 5 downTo 1) {
        descending = descending + i.toString()
    }
    println(descending)

    // `step` widens either direction: 0, 2, 4, 6, 8 ...
    var evens: Int = 0
    for (i in 0 until 10 step 2) {
        evens = evens + 1
    }
    println(evens)

    // ... and keeps the direction a `downTo` range gave it: 5, 3, 1.
    var oddSteps: Str = ""
    for (i in 5 downTo 1 step 2) {
        oddSteps = oddSteps + i.toString()
    }
    println(oddSteps)

    // A single bound is a range too: `3..3` is one iteration.
    var one: Int = 0
    for (i in 3..3) {
        one = i
    }
    println(one)

    // An empty range walks nothing, in either direction.
    var walked: Int = 0
    for (i in 4..1) {
        walked = walked + 1
    }
    for (i in 1 downTo 4) {
        walked = walked + 1
    }
    println(walked)
    return 0
}

// ---- range-value ----

// A range is a value: bind it, read its bounds, iterate it twice. `for` over a
// bound range walks the same machine the literal spelling does.

fun partRangeValue(): Int {
    val r: Range = 1..4
    println(r.from)
    println(r.to)

    var first: Int = 0
    for (i in r) {
        first = first + i
    }
    println(first)

    // A stepped range keeps its own bounds and step as fields.
    val s: SteppedRange = 10 downTo 0 step 5
    println(s.from)
    println(s.to)
    println(s.step)

    var sum: Int = 0
    for (i in s) {
        sum = sum + i
    }
    println(sum)

    // The words compose with `..` as the inner bound: `1..9 step 3` is 1, 4, 7.
    var threes: Str = ""
    for (i in 1..9 step 3) {
        threes = threes + i.toString()
    }
    println(threes)
    return 0
}

// ---- for-indexed-range ----

// The indexed `for` form counts the range's iterations: `(v, 0)`, `(v, 1)`, ...

fun partForIndexedRange(): Int {
    for ((v, index) in 7..9) {
        println(v * 10 + index)
    }
    var steps: Int = 0
    for ((v, index) in 1 until 9 step 4) {
        steps = v + index
    }
    println(steps)
    return 0
}

// ---- range-in-expressions ----

// A range is built where any expression is: a call argument, a conditional's arm.

fun sumRange(r: Range): Int {
    var sum: Int = 0
    for (i in r) {
        sum = sum + i
    }
    return sum
}

fun partRangeInExpressions(): Int {
    println(sumRange(1..10))
    val half: Range = 1..5
    println(sumRange(half))
    var built: Int = 0
    var n: Int = 4
    for (i in 1..n) {
        built = built + i
    }
    println(built)
    // The right side of `..` binds tighter than `+` (Kotlin's order): `1..(n + 1)`.
    var withSum: Int = 0
    for (i in 1..n + 1) {
        withSum = withSum + i
    }
    println(withSum)
    return 0
}

// ---- in-membership ----

// `x in c` / `x !in c` is `c.contains(x)`, the parser's sugar: a range (plain or
// stepped, the test on the step grid), a list, a string (a `Char` byte walk or a
// substring search), an array through its span, and a dictionary's keys.

fun partInMembership(): Int {
    println(3 in 1..5)
    println(6 in 1..5)
    println(6 !in 1..5)
    println(4 in 1..9 step 2)
    println(5 in 1..9 step 2)
    println(5 in 5 downTo 1 step 2)
    println(4 in 5 downTo 1 step 2)

    val words: List<Str> = listOf<Str>("one", "two")
    println("two" in words)
    println("three" !in words)

    println('b' in "abc")
    println('z' in "abc")
    println("bc" in "abc")
    println("bd" !in "abc")

    var numbers: List<Int> = List<Int>()
    numbers.append(10)
    numbers.append(20)
    val arr: Array<Int> = numbers.toArray()
    println(20 in arr)
    println(30 !in arr)

    val d: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    d.insert("k", 1)
    println("k" in d)
    println("z" !in d)
    return 0
}

fun main(): Int {
    partForOverRange()
    partRangeValue()
    partForIndexedRange()
    partRangeInExpressions()
    partInMembership()
    return 0
}
