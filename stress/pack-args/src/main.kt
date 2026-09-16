package fixtures

// List literals and packed calls (specs/functions.md, specs/containers.md).
//
// - `listOf<T>(a, b, c)` builds a list from its values in ONE instruction (`Pack`),
//   and up to four elements live in the list itself, so a short literal allocates
//   nothing. Written without type arguments, `T` is the type of the first value.
// - `List<T>(n)` and `List<T>(n, value)` stay the RTL's *count* constructions: a
//   literal is never mistaken for a size.
// - A call packs its trailing arguments into a last parameter that is a `List<T>`
//   (by value) or a `*List<T>` (borrowed). The counted `&List<T>` is deliberately not
//   a pack target (stress/diagnostic-pack-counted) - it takes a boxed copy instead.
// - A parameter that wants a handle accepts a value of the same type: `*T` takes the
//   argument's address at the call, a by-value `T` reads through a `*T`, and `&T`
//   boxes a copy. Types that differ (`List<Int>` against `*List<Str>`) are refused.

// The list borrowed: the packed list is a slot of the *caller's* frame and the
// parameter is its address, so nothing but the elements themselves is copied.
fun addAll(values: *List<Int>): Int {
    var total: Int = 0
    for (*value in values) {
        total = total + * value
    }
    return total
}

// The list by value: the callee owns a copy. Called with a `*List<Int>` argument the
// compiler reads through the pointer; called with values it packs them.
fun sumValues(values: List<Int>): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < values.size()) {
        total = total + values[i]
        i = i + 1
    }
    return total
}

// The counted reference: a value argument is *boxed* - a copy in a `std::shared_ptr`,
// which is what `&x` means everywhere else in the language.
fun countBoxed(values: &List<Int>): Int {
    return values.size()
}

// The template-and-items shape: everything after the template packs.
fun format(shape: Str, items: *List<Str>): Str {
    var out: Str = ""
    var i: Int = 0
    while (i < items.size()) {
        out = out + shape + "[" + i.toString() + "]=" + items[i] + " "
        i = i + 1
    }
    return out
}

fun count(values: *List<Str>): Int {
    return values.size()
}

fun main(): Int {
    // The borrow form: any number of trailing values, including none.
    println(addAll(1, 2, 3).toString())
    println(addAll(10).toString())
    println(addAll().toString())

    // A variable of the pointee's type is passed as its address - no `*` to write.
    val numbers: List<Int> = listOf<Int>(4, 5)
    println(addAll(numbers).toString())
    println(addAll(*numbers).toString())

    // The by-value form packs too, and reads through a pointer when given one.
    println(sumValues(6, 7, 8).toString())
    println(sumValues(numbers).toString())
    println(sumValues(*numbers).toString())

    // A counted parameter takes a boxed copy of a value.
    println(countBoxed(numbers).toString())

    // A literal list, in a variable, in an argument and in a call.
    val keywords: List<Str> = listOf<Str>("static", "var", "val")
    println(keywords.size().toString())
    println(keywords[2])
    println(count(*keywords))
    println(format("k", "a", "b"))
    println(count(*listOf<Str>()))          // the empty list, written out

    // A literal without type arguments takes its element type from the first value.
    val primes: List<Int> = listOf(2, 3, 5, 7)
    println(addAll(*primes).toString())

    // The count constructions are the RTL's, and are not literals.
    val zeros: List<Int> = List<Int>(3)
    println(addAll(*zeros).toString())
    val falses: List<Bool> = List<Bool>(4, false)
    println(falses.size().toString())
    return 0
}
