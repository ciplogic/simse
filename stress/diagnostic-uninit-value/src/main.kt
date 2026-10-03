package app

// A type that declares `unInit` has a real C++ destructor, so it may not be held by value: a
// value copy would run the destructor for both the original and the copy - the same resource
// closed twice. `&T` (counted) and `*T` (raw) are the holders that are allowed, and the
// declaration must be a `ref class` (a `data class` with an `unInit` is its own diagnostic).

ref class Res(var id: Int) {
    fun unInit(): Unit {
        println("close " + this.id.toString())
    }
}

fun main(): Int {
    val r: Res = Res(1)
    println(r.id)
    return 0
}
