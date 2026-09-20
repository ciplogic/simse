package fixtures

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

fun main(): Int {
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
        total = total + byOne.value()
    }
    println(total.toString())
    println(byOne.advance().toString())
    return 0
}
