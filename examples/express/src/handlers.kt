package express

import jsonnode
import http

// The handlers: each is an ordinary `Request -> Response` function, and each shows one step of
// the pipeline. `greet` and `echo` are the request->JSON->transform->response loop; `fetch`
// adds an outbound web request (`http`'s `httpGetText`) whose JSON is decoded and re-shaped.
// A handler never touches the socket, a header, or a byte - `http.kt` owns those boundaries.
//
// A response object is built with `jsonField` (one key and a node) into a `List<&JsonMember>`,
// then `jsonObject`; the recursive storage is handles, and the call site stays value-shaped.

// GET /health -> {"status":"ok"}
fun healthHandler(req: Request): Response {
    var members: List<&JsonMember> = List<&JsonMember>()
    members.append(jsonField("status", jsonText("ok")))
    return jsonResponse(200, jsonObject(members))
}

// GET /json -> {"message":"Hello, World!"} - the constant JSON a hello-world throughput
// benchmark hits (`bench/`).
fun jsonHandler(req: Request): Response {
    var members: List<&JsonMember> = List<&JsonMember>()
    members.append(jsonField("message", jsonText("Hello, World!")))
    return jsonResponse(200, jsonObject(members))
}

// POST /greet with {"name":"Ada"} -> {"greeting":"Hello, Ada!"}. This is the whole point of the
// abstraction: `req.json()` reads the body to a `JsonNode`, the handler reaches in with
// `get`/`text`, and `jsonResponse` writes a new node back.
fun greetHandler(req: Request): Response {
    val parsed: Res<JsonNode> = req.json()
    if (!parsed.isOk()) {
        return jsonError(400, "body is not json: " + parsed.error)
    }
    val node: JsonNode = parsed.value
    val name: JsonNode = node.get("name")
    if (!name.isText()) {
        return jsonError(400, "expected a string 'name'")
    }
    var members: List<&JsonMember> = List<&JsonMember>()
    members.append(jsonField("greeting", jsonText("Hello, " + name.text() + "!")))
    return jsonResponse(200, jsonObject(members))
}

// POST /echo -> {"received":<body>,"kind":"<shape>"}. The transformation adds a field and
// reports the decoded shape, so the round trip through `parseJson`/`toJson` is visible.
fun echoHandler(req: Request): Response {
    val parsed: Res<JsonNode> = req.json()
    if (!parsed.isOk()) {
        return jsonError(400, "body is not json: " + parsed.error)
    }
    val node: JsonNode = parsed.value
    var members: List<&JsonMember> = List<&JsonMember>()
    members.append(jsonField("received", node))
    members.append(jsonField("kind", jsonText(kindName(node))))
    return jsonResponse(200, jsonObject(members))
}

// GET /fetch -> the one web request this example makes, decoded and re-shaped. The upstream is
// a single constant URL (`upstreamUrl`); replacing it with a route parameter is the next step,
// not this one. A failure to reach or parse it is a 502, so the failure mode is visible.
fun fetchHandler(req: Request): Response {
    val body: Res<Str> = httpGetText(upstreamUrl())
    if (!body.isOk()) {
        return jsonError(502, "upstream request failed: " + body.error)
    }
    val parsed: Res<JsonNode> = parseJson(body.value)
    if (!parsed.isOk()) {
        return jsonError(502, "upstream was not json: " + parsed.error)
    }
    val node: JsonNode = parsed.value
    var members: List<&JsonMember> = List<&JsonMember>()
    members.append(jsonField("upstream", jsonText(upstreamUrl())))
    members.append(jsonField("kind", jsonText(kindName(node))))
    members.append(jsonField("size", jsonNumber(node.size())))
    members.append(jsonField("body", node))
    return jsonResponse(200, jsonObject(members))
}

// The one URL the example reaches out to: a small JSON document from a public test API.
fun upstreamUrl(): Str {
    return "https://jsonplaceholder.typicode.com/todos/1"
}

// The name of a node's live shape - the reader's own classification, used by `echo`/`fetch`.
fun kindName(node: JsonNode): Str {
    if (node.isNull()) {
        return "null"
    }
    if (node.isBool()) {
        return "boolean"
    }
    if (node.isNumber()) {
        return "number"
    }
    if (node.isText()) {
        return "string"
    }
    if (node.isArray()) {
        return "array"
    }
    if (node.isObject()) {
        return "object"
    }
    return "unknown"
}
