package express

import jsonnode

// The transport-neutral request and response, and the one function on each side that crosses
// the wire: `parseRequest` reads a raw HTTP/1.1 request into a `Request`, `formatResponse`
// writes a `Response` back to bytes. Everything above this file works with the two shapes and
// never sees a socket or a header.
//
// The JSON boundary lives here too: `Request.json()` is the *reader* the handlers call, and
// `jsonResponse` runs the *writer* (`JsonNode.toJson()`) on the way out. A handler therefore
// transforms a `JsonNode` and knows nothing about bytes.

data class Request(var method: Str, var path: Str, var query: Str, var body: Str, var keepAlive: Bool) {
    // The body decoded to the intermediate `JsonNode`. A malformed body is a `Res` error the
    // handler turns into a 400, not a crash (there are no exceptions).
    fun json(): Res<JsonNode> {
        return parseJson(this.body)
    }
}

data class Response(var status: Int, var contentType: Str, var body: Str)

fun statusText(status: Int): Str {
    if (status == 200) {
        return "200 OK"
    }
    if (status == 201) {
        return "201 Created"
    }
    if (status == 400) {
        return "400 Bad Request"
    }
    if (status == 404) {
        return "404 Not Found"
    }
    if (status == 405) {
        return "405 Method Not Allowed"
    }
    if (status == 502) {
        return "502 Bad Gateway"
    }
    return status.toString() + " Status"
}

fun textResponse(status: Int, body: Str): Response {
    return Response(status, "text/plain; charset=utf-8", body)
}

// The *writer* boundary: a `JsonNode` becomes the response body's bytes.
fun jsonResponse(status: Int, node: JsonNode): Response {
    return Response(status, "application/json", node.toJson())
}

// `{"error": message}` - the one shape every failing handler answers with.
fun jsonError(status: Int, message: Str): Response {
    var members: List<&JsonMember> = List<&JsonMember>()
    members.append(jsonField("error", jsonText(message)))
    return jsonResponse(status, jsonObject(members))
}

// Everything up to the first CRLF.
fun requestLine(raw: Str): Str {
    val at: Int = raw.find("\r\n")
    if (at < 0) {
        return raw
    }
    return raw.substr(0, at)
}

// "GET /greet?name=ada HTTP/1.1" -> method, path and query, and the body past the blank line.
// The request is one `recv` at the call site, so a body that arrived split across reads is out
// of scope, exactly as in `examples/http/server`. `keepAlive` is the one header the server
// reads: a client that says `Connection: close` gets one request per connection, anything else
// keeps the socket for the next request (which is what lets the workers hold connections).
fun parseRequest(raw: Str): Request {
    val line: Str = requestLine(raw)
    val parts: List<Str> = line.split(" ")
    var method: Str = ""
    var target: Str = ""
    if (parts.size() > 0) {
        method = parts[0]
    }
    if (parts.size() > 1) {
        target = parts[1]
    }
    var path: Str = target
    var query: Str = ""
    val q: Int = target.find("?")
    if (q >= 0) {
        path = target.substr(0, q)
        query = target.substr(q + 1, target.size() - q - 1)
    }
    var body: Str = ""
    var headers: Str = raw
    val headerEnd: Int = raw.find("\r\n\r\n")
    if (headerEnd >= 0) {
        headers = raw.substr(0, headerEnd)
        body = raw.substr(headerEnd + 4, raw.size() - headerEnd - 4)
    }
    val keepAlive: Bool = headers.toLower().find("connection: close") < 0
    return Request(method, path, query, body, keepAlive)
}

// The head is a fixed shape, so it is one `fmtStr`; the length frames the body, which is what
// the next request on a kept-alive connection needs to find its start.
fun formatResponse(res: Response, keepAlive: Bool): Str {
    var connection: Str = "close"
    if (keepAlive) {
        connection = "keep-alive"
    }
    return fmtStr(
        "HTTP/1.1 |\r\nContent-Type: |\r\nContent-Length: |\r\nConnection: |\r\n\r\n|",
        statusText(res.status), res.contentType, res.body.size().toString(), connection, res.body
    )
}
