package fixtures

// The sample for "no braces in the emitted C++": the three shapes whose temporary
// had no type to declare it with, so the emitter had to keep it in place - and a
// declaration a jump crosses then needed a block (C2362).
//
//   pick     - two early returns of the *same* value, which the fold merges into one
//              tail, so a jump crosses the tail's `Opt<Int>.none()` temporary;
//   describe - a `Res<T>` field read (`Value` / `Error`);
//   countUntil - a member call's result (`size()`), read where a jump crosses it.

fun pick(i: Int): Opt<Int> {
    if (i < 0) {
        return Opt<Int>.none()
    }
    if (i >= 10) {
        return Opt<Int>.none()
    }
    return Opt<Int>.some(i)
}

fun parse(n: Int): Res<Str> {
    if (n < 0) {
        return Res<Str>.err("negative")
    }
    return Res<Str>.ok("parsed")
}

fun describe(n: Int): Str {
    val r: Res<Str> = parse(n)
    if (!r.isOk()) {
        return r.Error
    }
    return r.Value
}

fun countUntil(names: List<Str>, limit: Int): Int {
    var i: Int = 0
    while (i < names.size()) {
        if (i >= limit) {
            return i
        }
        i = i + 1
    }
    return names.size()
}

fun main(): Int {
    println(pick(3).value())
    println(pick(-1).hasValue())
    println(describe(2))
    println(describe(-2))
    var names: List<Str> = List<Str>()
    names.append("a")
    names.append("b")
    names.append("c")
    println(countUntil(names, 2))
    return 0
}
