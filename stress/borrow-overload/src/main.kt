package fixtures

// Auto-borrow's trust is per *name*, and a name is trusted only when every declaration of it
// is borrow-clean (src/parser/BorrowParams.kt). These two clean overloads prove together, so
// `use`'s `text` is read-only through them and its declaration takes `*Str` - the golden shows
// the borrowed signatures and the addresses at the call sites.

fun width(value: Str): Int {
    return value.size()
}

fun width(value: Str, extra: Int): Int {
    return value.size() + extra
}

// Only reads `text`, through names the fixpoint trusts: `text` becomes `*Str`.
fun use(text: Str): Int {
    return width(text) + width(text, 1)
}

fun main(): Int {
    var name: Str = "abcd"
    println(use(name).toString())
    println(width(name).toString())
    return 0
}
