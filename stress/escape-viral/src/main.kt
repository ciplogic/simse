package escapeviral

// The auto-borrow proof growing virally through ordinary code (impl_specs/
// escape-analysis.md): a body that only reads its parameters - through the prelude's
// borrow-clean scans, the `Opt`/`Res` member surface, the `+` operator, or a walk over a
// container - proves borrow-clean, and every call stops copying what it hands it. The
// shapes here are the ones real code is made of, and none of them name `Str` only: what
// holds for a string holds for a `Point3D` and a list of them.

// ---- res-and-plus ----

// The `Res` member surface (`isOk`, `value`) is declared under collision-safe names the
// borrow fixpoint cannot see by name; the alias is what lets this body - the language's
// error path, in every real program - prove.
fun loadName(): Res<Str> {
    return Res<Str>.ok("Ada")
}

fun greet(prefix: Str): Str {
    val r: Res<Str> = loadName()
    if (!r.isOk()) {
        return "none"
    }
    return prefix + r.value()
}

// The `+` operator's declaration is marked `borrow` (it writes only its fresh result), so
// concatenation chains like this one prove too.
fun shout(base: Str, mark: Str): Str {
    return base + mark + "!"
}

// The `Opt` twin of the same surface, on the dictionary lookup that answers one.
fun lookup(d: Dictionary<Str, Str>, key: Str): Str {
    val found: Opt<Str> = d.get(key)
    if (found.hasValue()) {
        return found.value()
    }
    return ""
}

// ---- any-value-type ----

// What holds for `Str` holds for a user data class: the parameter is borrowed (the value
// is read, never written through), and the walk over the list of them binds each element
// as a place once the promotion fires (`List<T>` of a deep element).
data class Point3D(var x: Int, var y: Int, var z: Int)

fun shifted(p: Point3D, d: Int): Point3D {
    var out: Point3D = Point3D(p.x + d, p.y + d, p.z + d)
    return out
}

fun total(points: List<Point3D>): Int {
    var sum: Int = 0
    for (pt in points) {
        sum = sum + pt.x + pt.y + pt.z
    }
    return sum
}

// A deep element list walk in the value spelling, over strings: the read-only body is
// what lets the loop promotion take it.
fun joinedWords(words: List<Str>): Str {
    var out: Str = ""
    for (word in words) {
        out = out + word
    }
    return out
}

fun partViral(): Int {
    println(greet("hi "))
    println(greet("hey "))
    println(shout("Ada", "?"))
    val ages: Dictionary<Str, Str> = Dictionary<Str, Str>()
    ages.insert("ada", "36")
    println(lookup(ages, "ada"))
    println(lookup(ages, "grace"))

    val p: Point3D = Point3D(1, 2, 3)
    val q: Point3D = shifted(p, 10)
    println(q.x + q.y + q.z)
    var pts: List<Point3D> = List<Point3D>()
    pts.append(Point3D(1, 1, 1))
    pts.append(Point3D(2, 2, 2))
    println(total(pts))
    println(joinedWords(listOf<Str>("ab", "cd")))
    return 0
}

// ---- returns ----

// Build-and-return through the out-parameter ABI (`CgCore.kt`'s sret set): a free
// function returning one heavy value is emitted `void f(T* _sm_out, ...)`, its returns
// store into the parameter, and a call with a destination writes where the value is
// going to live - no copy crosses the boundary, for a `Str`, a `List` or any user data
// class alike (`Point3D` below is the same rule as `printName`).
fun printName(first: Str, second: Str): Str {
    return "$first $second"
}

fun buildList(): List<Int> {
    var out: List<Int> = List<Int>()
    out.append(1)
    out.append(2)
    return out
}

fun makePoint(n: Int): Point3D {
    return Point3D(n, n + 1, n + 2)
}

// Multiple returns, every path building: each stores at its own return.
fun classify(n: Int): Str {
    if (n < 0) {
        return "negative"
    }
    if (n == 0) {
        return "zero"
    }
    return "positive"
}

fun partReturns(): Int {
    println(printName("Ada", "Lovelace"))
    val nums: List<Int> = buildList()
    println(nums.size())
    println(printName("grace", "hop").size())
    val p: Point3D = makePoint(5)
    println(p.x + p.y + p.z)
    println(classify(0 - 3))
    println(classify(0))
    println(classify(3))
    return 0
}

fun main(): Int {
    partViral()
    partReturns()
    return 0
}
