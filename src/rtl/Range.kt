// Range.kt
//
// Ranges (specs/built-in-types.md): `a..b`, `a until b`, `a downTo b` and `r step n` are
// parser-level sugar for the extension methods below (`a.rangeTo(b)`, `a.until(b)`,
// `a.downTo(b)`, `r.step(n)`), so a range is a value like any other and everything
// downstream - sema, the linear pass, the emitters - sees the member call the sugar stands
// for. Both types are generated from their declarations like any other data class, and
// `iterValues` makes each one iterable by the `for` convention (impl_specs/for.md): a
// `for (i in 1..5)` walks `(1.rangeTo(5)).iterValues()`, the same machine shape a
// container's span walk takes.

package rtl

// An inclusive ascending range of `Int`: `1..5` is 1, 2, 3, 4, 5, and `1 until 5` is
// `1..4`. The bounds are copied into the value, so a range is self-contained.
data class Range(var from: Int, var to: Int) {
}

// A range with an explicit step, ascending or descending by the step's sign: `5 downTo 1
// step 2` is 5, 3, 1. `step` keeps the direction it is given (`downTo` made it negative),
// the way Kotlin's progressions do.
data class SteppedRange(var from: Int, var to: Int, var step: Int) {
}

fun Int.rangeTo(other: Int): Range {
    return Range(this, other)
}

// `a until b`: the half-open spelling - ascending, `b` itself excluded.
fun Int.until(other: Int): Range {
    return Range(this, other - 1)
}

// `a downTo b`: descending, both ends inclusive, stepping down by one until a `step`
// widens it.
fun Int.downTo(other: Int): SteppedRange {
    return SteppedRange(this, other, -1)
}

// `r step n`: the same bounds walked `n` at a time. Applied to a `downTo` range the step
// stays negative, so `5 downTo 1 step 2` walks 5, 3, 1.
fun Range.step(by: Int): SteppedRange {
    return SteppedRange(this.from, this.to, by)
}

fun SteppedRange.step(by: Int): SteppedRange {
    if (this.step < 0 && by > 0) {
        return SteppedRange(this.from, this.to, -by)
    }
    return SteppedRange(this.from, this.to, by)
}

fun Range.iterValues(): ..Int {
    var i: Int = this.from
    while (i <= this.to) {
        yield i
        i++
    }
}

fun SteppedRange.iterValues(): ..Int {
    if (this.step > 0) {
        var i: Int = this.from
        while (i <= this.to) {
            yield i
            i = i + this.step
        }
    } else {
        var i: Int = this.from
        while (i >= this.to) {
            yield i
            i = i + this.step
        }
    }
}

// The membership tests (`x in 1..5` is `contains` on the range, the parser's `in` sugar).
fun Range.contains(value: Int): Bool {
    return value >= this.from && value <= this.to
}

fun SteppedRange.contains(value: Int): Bool {
    if (this.step > 0) {
        if (value < this.from || value > this.to) {
            return false
        }
        return (value - this.from) % this.step == 0
    }
    if (value > this.from || value < this.to) {
        return false
    }
    return (this.from - value) % (0 - this.step) == 0
}
