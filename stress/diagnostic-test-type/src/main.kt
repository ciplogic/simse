package fixtures

// A marker attribute marks a function (specs/attributes.md, "Marker attributes") - a
// type has nothing to annotate.

@Test data class Marked(var x: Int)
