package forderef

// A member call on an explicit deref as the iterated expression (`(*text).split(",")`):
// `iteratedType` had no deref arm, so the `for` machinery's inference saw no type, the
// span rewrite and the not-iterable diagnostic both silently skipped, and the emitted
// machine was a member `iterValues()` the C++ cannot compile - a transpile-clean
// miscompile. The deref arm resolves the pointee, `spanFor` fires, and the loop is the
// span's own machine like any other container `for`.

fun countWords(text: *Str): Int {
    var total: Int = 0
    for (word in (*text).split(",")) {
        total = total + word.size()
    }
    return total
}

fun main(): Int {
    var s: Str = "a,bb,ccc"
    println(countWords(s))
    var t: Str = "one,two"
    println(countWords(t))
    return 0
}
