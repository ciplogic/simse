package uninit

// `unInit` is a type's destructor: the emitted struct gets a real `~T()`, whose body is this
// method, and the language holds such a type only through a handle (`&T`) or a raw pointer
// (`*T`) - a *value* of it would be a copy, and every copy would run the destructor: that is
// what `stress/diagnostic-uninit-value` reports.
//
// `&T(...)` builds the box in place (`makeRef<T>(...)`, specs/memory-model.md), so there is no
// temporary: the destructor runs exactly once, when the box's last owner goes.

data class Res(var id: Int) {
    fun unInit(): Unit {
        println("close " + this.id.toString())
    }
}

// One owner: the box closes when the handle goes, at the end of the call.
fun oneOwner(): Unit {
    val a: &Res = &Res(7)
    println("open " + a.id.toString())
}

// Two owners of one box: the handle is copied, the box is not - the destructor still runs once.
fun twoOwners(): Unit {
    val b: &Res = &Res(9)
    val c: &Res = b
    println("share " + c.id.toString())
}

fun main(): Int {
    oneOwner()
    twoOwners()
    println("done")
    return 0
}
