package fixtures

// A marker attribute is inert in an ordinary build (specs/attributes.md, "Marker
// attributes"): `@Test` parses, the function keeps its body, nothing calls it, and the
// program runs exactly as it would without the mark. The suite that *does* call the
// marked functions is a `--tests` build (specs/testing.md).

@Test fun smoke(): Unit {
    println("the mark does not run the function")
}

fun main(): Int {
    println("ran")
    return 0
}
