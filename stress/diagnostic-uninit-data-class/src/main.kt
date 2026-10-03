package app

// A destructor makes a class handle-only, and `ref class` is the word that says so: a
// `data class` that declares `unInit` is a diagnostic. The legal spelling is
// `stress/uninit`'s.

data class Res(var id: Int) {
    fun unInit(): Unit {
        println("close " + this.id.toString())
    }
}

fun main(): Int {
    val r: &Res = &Res(1)
    println(r.id.toString())
    return 0
}
