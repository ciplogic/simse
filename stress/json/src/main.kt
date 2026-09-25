package fixtures

import json

// The `json` module's generator (cppsrc/modules/json/generators/JsonGen.kt): `value.toJson()`
// serializes a data class to JSON, recursively - a class is an object of its fields' serializers,
// a scalar a leaf. The module is named here rather than carried by the case
// (`compiler-args` holds `--module cppsrc/modules/json`), which is what makes it reusable;
// `import json` is what makes the generator act on this program's classes.
//
// `expected.cpp` is the point: `Point` is named by two classes and `Int`/`Str` are fields of
// three, and each serializer appears **once** however many classes name it.

data class Point(var x: Int, var y: Int)
data class Label(var text: Str, var flagged: Bool)
data class Shape(var name: Str, var corner: Point, var tag: Label)

fun main(): Int {
    val p: Point = Point(1, 2)
    println(p.toJson())

    // The escapes JSON needs on the way in: `"` and `\`.
    val label: Label = Label("a\"b\\c", true)
    println(label.toJson())

    // A nested class and a shared one: `corner` is a `Point`, whose serializer is already emitted.
    val shape: Shape = Shape("box", Point(3, 4), Label("plain", false))
    println(shape.toJson())
    return 0
}
