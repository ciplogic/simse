package fixtures

// A counted reference (`&T`) as a container *element*: the receiver `List<&Box>` binds the
// prelude's `append<T>(this: List<T>, value: T)` type parameter `T` to `&Box` - the handle,
// not the `Box` inside it. Binding once stripped the actual's reference (`T` came out as
// `Box`), so `append(&x)` read through the box and emitted a `Box` value where the list
// held `&Box`: the C++ did not compile. This pins the binding, both for an existing value
// and for a box built in the argument (`&Box(2)`), and that the handles stay live.

union class Box(var I: Int, var S: Str)

fun describe(b: &Box): Str {
    val i = (*b).getI()
    if (i != null) {
        return "I=" + (*i).toString()
    }
    return "S=" + *(*b).getS()
}

fun main(): Int {
    var items: List<&Box> = List<&Box>()

    // A named value boxed into the list.
    var a: Box = Box(1)
    items.append(&a)

    // A box built in the argument.
    items.append(&Box(2))
    items.append(&Box("text"))

    println(items.size().toString())
    var i: Int = 0
    while (i < items.size()) {
        println(describe(items[i]))
        i = i + 1
    }
    return 0
}
