package fixtures

// The counted reference's *sharing* (`&T`, specs/memory-model.md): `&value` boxes a copy,
// copying a handle adds an owner of the same box, a write through one handle is seen
// through the other, and a handle that ends does not free a box another one holds. That
// counting is what the RTL implements (`cppsrc/rtl/ref.hpp`: `SmRef`, or the
// `std::shared_ptr` shim) and what nothing else in the corpus observes.
//
// `stress/language-tour` covers the rest of the operator: null handles, the null tests, and
// `*T` taken from a handle. This case also pins the `Ref<T>` spelling the emitter writes for
// `&T` in a *program* (its `expected.cpp`), which no other case does.

data class Counter(var value: Int) {
    fun bump(): Int {
        this.value = this.value + 1
        return this.value
    }
}

fun read(box: &Counter): Int {
    return box.value
}

// A handle taken as a parameter is a second owner of the box, and one taken again inside is
// a third: the box outlives this call because `bumpThrough`'s caller still holds one.
fun bumpThrough(box: &Counter): Int {
    val again: &Counter = box
    return again.bump()
}

fun main(): Int {
    val plain: Counter = Counter(10)

    // `&plain` boxes a *copy*: the box and `plain` are two values, so a write through the
    // handle is not visible in `plain`.
    val first: &Counter = &plain
    println(read(first))
    val bumped: Int = first.bump()
    println(bumped)
    println(plain.value)

    // A copy of the handle is another owner of the same box.
    println(first.value)
    println(bumpThrough(first))

    // The handles taken above have ended; the box has not.
    println(first.value)
    return 0
}
