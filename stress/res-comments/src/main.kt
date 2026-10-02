package fixtures

// A resource section's C++ carries comments for the reader of the `_res.md`: the `res`
// generator drops them as the text enters the program (src/compiler/ResComments.kt). The
// section below holds a comment that must be gone, and a `//` inside a string, a character
// and a raw string that must not be - those are data, and the three checks in the body each
// add one.
@SmGen("res", "rescom", "fixtures_resCom")
fun resCom(value: Int): Int

fun main(): Int {
    println(resCom(14).toString())
    return 0
}
