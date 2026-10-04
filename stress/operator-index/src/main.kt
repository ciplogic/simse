package fixtures

// `operator fun get`/`set` (specs/functions.md, "Operator functions"): the Kotlin indexer
// convention. `x[i]` is `x.get(i)` and `x[i] = v` is `x.set(i, v)` when the receiver's type
// declares the operator - in the class body (`Span2`) or as an extension (`Point`), which
// are the two spellings. `Span2<T>` also proves the generic substitution: the index reads
// and writes a `T`, and `get`'s return type is what an unannotated `val` infers.

data class Span2<T>(var items: List<T>) {
    operator fun get(index: Int): T {
        return this.items[index]
    }

    operator fun set(index: Int, value: T): Unit {
        this.items[index] = value
    }

    fun size(): Int {
        return this.items.size()
    }
}

data class Point(var x: Int, var y: Int)

operator fun Point.get(index: Int): Int {
    if (index == 0) {
        return this.x
    }
    return this.y
}

operator fun Point.set(index: Int, value: Int): Unit {
    if (index == 0) {
        this.x = value
    } else {
        this.y = value
    }
}

fun main(): Int {
    val numbers: Span2<Int> = Span2<Int>(listOf<Int>(1, 2, 3))
    // An unannotated local: the read's type is `get`'s return type, substituted to `Int`.
    val first = numbers[0]
    println("first=" + first.toString())
    numbers[1] = 42
    numbers[2] = numbers[2] + 1
    numbers[0] += 10
    var i: Int = 0
    while (i < numbers.size()) {
        println("numbers[" + i.toString() + "]=" + numbers[i].toString())
        i = i + 1
    }

    val p: Point = Point(1, 2)
    println("p[0]=" + p[0].toString() + " p[1]=" + p[1].toString())
    p[0] = p[1] + 5
    p[1] = 7
    println("p[0]=" + p[0].toString() + " p[1]=" + p[1].toString())

    // A `Str` element: the write copies the value in, the reads come back owned.
    val texts: Span2<Str> = Span2<Str>(listOf<Str>("a", "b"))
    texts[1] = "bee"
    println(texts[0] + texts[1])
    return 0
}
