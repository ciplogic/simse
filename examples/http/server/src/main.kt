package httpd

import sockets

// A single-threaded, fully blocking HTTP/1.1 server over the `sockets` module: one connection at
// a time, `netAccept` -> `netRecv` -> `netSend` -> close. No authentication, no keep-alive
// (`Connection: close`), and no parsing beyond the request line - the point is the socket library
// underneath, not an HTTP implementation.
//
//   GET /        -> text/plain        Hello, world!
//   GET /hello   -> the same
//   GET /json    -> application/json  {"name":"Hello world"}
//   anything els -> 404
//
//   httpd.exe [port] [requestLimit]      (defaults: 8080, unlimited)

// Everything up to the first CRLF: the request line, which is all this server reads.
fun firstLine(request: Str): Str {
    val at: Int = request.find("\r\n")
    if (at < 0) {
        return request
    }
    return request.substr(0, at)
}

// The path of the request line ("GET /json HTTP/1.1" -> "/json"), with any query string dropped.
fun targetPath(line: Str): Str {
    val parts: List<Str> = line.split(" ")
    if (parts.size() < 2) {
        return ""
    }
    var target: Str = parts[1]
    val query: Int = target.find("?")
    if (query >= 0) {
        target = target.substr(0, query)
    }
    return target
}

// One response: status line, the two headers a body needs, and the body - then the connection is
// closed (`Connection: close` is why the length is the only framing a client needs).
//
// The whole thing is one `fmtStr` and one `send`. The head is a fixed shape, so building it with a
// run of appends would rebuild it; and sending the head and the body separately costs a second
// small segment per request, which meets Nagle's algorithm and the peer's delayed ACK head on.
// The body is a format *item*, so its bytes are never scanned for a `|`.
fun respond(conn: Int64, status: Str, contentType: Str, body: Str): Unit {
    val response: Str = fmtStr(
        "HTTP/1.1 |\r\nContent-Type: |\r\nContent-Length: |\r\nConnection: close\r\n\r\n|",
        status, contentType, body.size().toString(), body
    )
    netSend(conn, *response)
}

fun handle(conn: Int64): Unit {
    val buffer: List<Int8> = netBuffer(netRecvCap())
    val count: Int = netReceive(conn, *buffer)
    if (count <= 0) {
        return
    }
    val path: Str = targetPath(firstLine(netReceivedText(*buffer, count)))
    if (path == "/json") {
        respond(conn, "200 OK", "application/json", "{\"name\":\"Hello world\"}")
    } else if (path == "/" || path == "/hello") {
        respond(conn, "200 OK", "text/plain; charset=utf-8", "Hello, world!\n")
    } else {
        respond(conn, "404 Not Found", "text/plain; charset=utf-8", "not found\n")
    }
}

fun main(args: List<Str>): Int {
    var port: Int = 8080
    var limit: Int = 0 - 1
    if (args.size() > 0) {
        val parsed: Opt<Int> = args[0].toInt()
        if (parsed.hasValue()) {
            port = parsed.value()
        }
    }
    if (args.size() > 1) {
        val parsed: Opt<Int> = args[1].toInt()
        if (parsed.hasValue()) {
            limit = parsed.value()
        }
    }

    if (!netStartup()) {
        println("httpd: WSAStartup failed")
        return 1
    }
    val server: Int64 = netListen(port)
    if (server == 0 - 1) {
        println(fmtStr("httpd: cannot listen on port |", port.toString()))
        return 1
    }
    println(fmtStr("httpd: listening on http://127.0.0.1:|", netLocalPort(server).toString()))

    // Server-side timing, so a run's request rate is reported however many clients drove it: one
    // blocking client is the request latency, ten are the aggregate (and, on this single-threaded
    // server, ten are also where they queue). The clock is the RTL's (`src/rtl/rtl.kt`).
    val started: Int64 = nowMicros()

    var served: Int = 0
    var running: Bool = true
    while (running) {
        val conn: Int64 = netAccept(server)
        if (conn == 0 - 1) {
            continue
        }
        handle(conn)
        netClose(conn)
        served = served + 1
        if (limit > 0 && served >= limit) {
            running = false
        }
    }

    netClose(server)
    netCleanup()

    val elapsed: Int64 = nowMicros() - started
    val microsEach: Int64 = elapsed / served
    var perSecond: Int64 = 0
    if (microsEach > 0) {
        perSecond = 1000000 / microsEach
    }
    println(fmtStr(
        "httpd: served | requests in | us (| us/request, ~| req/s)",
        served.toString(), elapsed.toString(), microsEach.toString(), perSecond.toString()
    ))
    return 0
}
