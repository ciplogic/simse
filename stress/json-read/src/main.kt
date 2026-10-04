package fixtures

import jsonnode

// The `jsonnode` module's *dynamic* reader and writer (`src/modules/jsonnode/jsonnode.kt`),
// beside the generator's typed writer pinned by `stress/json`: `parseJson` is the reader
// (`text -> Res<JsonNode>`) and `JsonNode.toJson()` the writer (`JsonNode -> text`).
//
// The case round-trips a nested object - an integral and a fractional number, an array, a
// boolean, null, and a string that needs escaping on the way out - reads it through the
// accessors, builds one with `jsonObject`/`jsonText`/`jsonNumber`/`jsonArray`, and pins the
// parse error's position.

fun main(): Int {
    val input: Str = "{\"name\":\"Ada\",\"age\":36,\"frac\":1.25,\"tags\":[\"x\",\"y\"],\"ok\":true,\"note\":null}"
    val parsed: Res<JsonNode> = parseJson(input)
    if (!parsed.isOk()) {
        println("parse error: " + parsed.error)
        return 1
    }
    val node: JsonNode = parsed.value
    println(node.get("name").text())
    println(node.get("age").number().toString())
    println(node.get("tags").size().toString())
    println(node.get("tags").at(1).text())
    println(node.get("ok").boolean().toString())
    println(node.get("note").isNull().toString())
    println(node.toJson())

    var members: List<JsonMember> = List<JsonMember>()
    members.append(JsonMember("hello", jsonText("a\"b")))
    members.append(JsonMember("n", jsonNumber(3.0)))
    var empty: List<JsonNode> = List<JsonNode>()
    members.append(JsonMember("empty", jsonArray(empty)))
    println(jsonObject(members).toJson())

    val bad: Res<JsonNode> = parseJson("{\"a\": }")
    println(bad.isOk().toString())
    println(bad.error)
    return 0
}
