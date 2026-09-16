package fixtures

// Packing the trailing arguments (specs/functions.md). A call packs its trailing
// values into its callee's *last* parameter when that parameter is a by-value
// `List<T>` or a borrowed `*List<T>`, and the list is built by one `Pack`
// instruction: `addAll(1, 2, 3)` is one instruction where three `append` calls
// would be three.
//
// `List<T>(a, b, c)` is the same instruction - the element form of a list - which is
// why `List<T>` is the type of a packed temporary: up to four elements live in its
// inline buffer, so a short literal allocates nothing.
//
// The counted forms (`&List<T>`, `PList<T>`) are deliberately *not* pack targets: a
// packed list is a throwaway temporary, and a control block with a reference count
// that drops at the end of the same statement would be cost with no use
// (stress/diagnostic-pack-counted).

// The list by value: the callee owns a copy, and the caller's argument is one
// instruction.
fun sumValues(values: List<Int>): Int {
    var total: Int = 0
    var i: Int = 0
    while (i < values.size()) {
        total = total + values[i]
        i = i + 1
    }
    return total
}

// The list borrowed: the packed list is a slot of the *caller's* frame and the
// parameter is its address, so nothing but the elements themselves is copied.
fun addAll(values: *List<Int>): Int {
    var total: Int = 0
    for (*value in values) {
        total = total + * value
    }
    return total
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

// A pack with no elements at all: still one (empty) list.
fun count(values: *List<Str>): Int {
    return values.size()
}

fun main(): Int {
    // The borrow form: any number of trailing values, including none.
    println(addAll(1, 2, 3).toString())
    println(addAll(10).toString())
    println(addAll().toString())

    // One argument for one parameter is the list itself, not a list of a list.
    val numbers: List<Int> = List<Int>()
    numbers.append(4)
    numbers.append(5)
    println(addAll(*numbers).toString())

    // The by-value form packs too.
    println(sumValues(6, 7, 8).toString())

    // A literal list, in a variable and in an argument.
    val keywords: List<Str> = List<Str>("static", "var", "val")
    println(keywords.size().toString())
    println(keywords[2])
    println(count(*keywords).toString())
    println(format("k", "a", "b"))
    println(count(*List<Str>()))

    // Up to four elements stay in the list's inline buffer.
    val four: List<Int> = List<Int>(1, 2, 3, 4)
    println(addAll(*four).toString())
    val five: List<Int> = List<Int>(1, 2, 3, 4, 5)
    println(addAll(*five).toString())

    // The count constructions are named, never packed: a size cannot be mistaken
    // for an element.
    val zeros: List<Int> = listOfCount<Int>(3)
    println(addAll(*zeros).toString())
    val falses: List<Bool> = listOfFilled<Bool>(4, false)
    println(falses.size().toString())
    return 0
}
