package fixtures

// A union class is matched by its arms, not destructured (specs/declarations.md,
// "Destructuring bind"): its storage is one live arm under a tag, and a field read of a
// sleeping arm is `null`.

union class DoubleOrFloat(var IntValue: Int, var DoubleValue: Float64)

fun main(): Int {
    val (v) = DoubleOrFloat(5)
    println(v.toString())
    return 0
}
