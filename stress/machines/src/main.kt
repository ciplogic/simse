package machines

// ---- dead-code ----

fun compute(): Int {
    println("called")
    return 3
}

fun partDeadCode(): Int {
    var unusedText: Str = "gone"
    var b: Int = 1
    var c: Int = 2
    var a: Int = b + c
    var dropped: Int = compute()
    var live: Int = 5
    println(live)
    return 0
}

// ---- flat-blocks ----

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

fun partFlatBlocks(): Int {
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

// ---- generic-yield ----

// T41: a *generic* function can yield. The machine is a class, and for a generic
// function that class is a template: its fields are typed with the function's own type
// parameters, so `T` is a real type in the emitted C++ rather than a name out of scope.
//
// The receiver form is the one `iter` will use (`impl_specs/for.md`): the type pass
// binds `T` from the receiver, so the loop variable is typed and `toString()` picks the
// right overload.

fun List<T>.everyNth<T>(step: Int): ..T {
    var i: Int = 0
    while (i < this.size()) {
        yield this[i]
        i = i + step
    }
}

fun partGenericYield(): Int {
    val numbers: List<Int> = List<Int>()
    numbers.append(1)
    numbers.append(2)
    numbers.append(3)
    numbers.append(4)
    numbers.append(5)
    numbers.append(6)

    // The plain form: `Int` yields, so `value.toString()` is the integer spelling.
    for (value in numbers.everyNth(2)) {
        println(value.toString())
    }

    // The indexed form over the same machine, on a different `T`.
    val words: List<Str> = List<Str>()
    words.append("alpha")
    words.append("beta")
    words.append("gamma")

    for ((word, index) in words.everyNth(2)) {
        println(index.toString() + ":" + word)
    }

    // A machine built by a generic function is a value like any other: it can be held,
    // advanced by hand, and advanced again after it is finished.
    val byOne = numbers.everyNth(1)
    var total: Int = 0
    while (byOne.advance()) {
        total = total + byOne.current
    }
    println(total.toString())
    println(byOne.advance().toString())
    return 0
}

// ---- yield ----

// `yield` (impl_specs/yield.md): a body that yields is a state machine, and the
// function that builds it returns one on the stack. `..Int` in the signature says
// what the machine hands out; there is no other semantic to it - the compiler
// replaces every `yield` with a branch, a return and the label that resumes there.
//
//   val evens = everyOther(10)     // a machine, built by value
//   while (evens.advance()) {
//       println(evens.current.toString())
//   }
//
// `advance()` steps the machine, leaves what it yielded in its `current` field and
// answers whether there was a value; the reader reads the field out, so nothing is
// built per element - no `Opt` to construct and unwrap, and no `value()` call to
// copy the element through on the way out.
//
// A machine is also what `for` iterates (specs/functions.md): `for (v in m)` and
// `for ((v, i) in m)` are the `while` above with the advance as their first
// statement, so `continue` still moves the machine on and still counts.

// A function that yields, with a `while` loop around the yield: the loop becomes
// labels and gotos first, and the yield becomes the resumption point inside them.
fun everyOther(n: Int): ..Int {
    var i: Int = 0
    while (i < n) {
        if (i % 2 == 0) {
            yield i
        }
        i = i + 1
    }
}

// The same machine, advanced by hand: `advance()` takes no argument and the yielded
// value stays in the machine's `current` field until the reader reads it. The local is
// called `value`, which was the protocol's method name until the `for` template began
// reading `current` itself: the field is emitted under the local's own name now.
fun countdown(from: Int): ..Int {
    var value: Int = from
    while (value > 0) {
        yield value
                value = value - 1
    }
    // A `return` (or the end of the body) finishes the machine: `yield break`.
}

// Every name the machine itself uses, taken by the body: `branch` and `current` (the
// lowering's fields) and `advance` (the protocol's method). Each that collides becomes
// `_sm_f_<name>`; `value` is an ordinary body name again.
fun shadowing(base: Int): ..Int {
    var current: Int = base
    var advance: Int = 1
    var branch: Int = 2
    var value: Int = 3
    while (value > 0) {
        yield current +advance + branch + value
        value = value - 1
    }
}

fun partYield(): Int {
    println("every other, up to 10:")
    val evens = everyOther(10)
    while (evens.advance()) {
        println(evens.current.toString())
    }

    println("countdown from 3:")
    val down = countdown(3)
    while (down.advance()) {
        println(down.current.toString())
    }

    // A machine can be advanced after it is finished; it stays finished.
    println("after the end:")
    println(down.advance().toString())

    // A body's own names are its fields even when they are the protocol's own.
    println("shadowing the protocol:")
    for (total in shadowing(10)) {
        println(total.toString())
    }

    // `for` over a machine: the plain form. The loop variable is a fresh `val`
    // per iteration, and the machine is created once, before the loop.
    println("with for:")
    for (value in everyOther(10)) {
        println(value.toString())
    }

    // The second form, with the index the compiler counts for you (from 0).
    println("with for and an index:")
    for ((value, index) in everyOther(10)) {
        println(index.toString() + ":" + value.toString())
    }

    // `continue` is the loop's own: because the advance and the index are the first
    // thing in the body, a skipped iteration still moves the machine on and still
    // counts (note the jump from 1 to 3 in the index). `break` leaves the loop.
    println("skip 4, stop after 8:")
    for ((value, index) in everyOther(10)) {
        if (value == 4) {
            continue
        }
        if (value > 8) {
            break
        }
        println(index.toString() + "=" + value.toString())
    }
    return 0
}

// ---- the category's entry ----
fun main(): Int {
    partDeadCode()
    partFlatBlocks()
    partGenericYield()
    partYield()
    return 0
}
