package fixtures

// The resources a program carries (`_res.md`, specs/resources.md): `src/_res.md` is read
// by the compiler, pooled into the program's string table, and reachable through the
// `Resources` API - a `StrView` over that table, so reading one copies nothing.
fun main(): Int {
    println(Resources.count())
    println(Resources.get("Greeting:Hello"))
    println(Resources.get("Greeting:Empty"))
    println(Resources.get("Greeting:Missing"))
    println(Resources.has("Template:Usage"))
    println(Resources.has("Template:Nothing"))
    println(Resources.get("Template:Usage"))
    println(Resources.get("Escapes:Quoted"))
    println(Resources.get("Escapes:Tabbed"))
    println(Resources.get("Escapes:Backslash end"))
    // The comparison a literal site makes reads the view directly, so no `Str` is built.
    if (Resources.get("Greeting:Hello") == "Hello, world!") {
        println("equal")
    }
    return 0
}
