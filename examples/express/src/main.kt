package express

import sockets

// An express-like JSON server: the composition root. It wires the routes (the handlers from
// `handlers.kt`) into the `App`, then runs one blocking accept loop over the `sockets` module.
// The layers below are `router.kt` (Request -> Response dispatch), `http.kt` (the wire and the
// JSON boundary), and the `jsonnode` module (the intermediate `JsonNode` reader/writer).
//
// One connection at a time, `Connection: close` - the same single-threaded, fully blocking model
// `examples/http/server` uses, which is the simplest correct thing and keeps this file to the
// pipeline. `examples/http/async-server` is the same server on the task scheduler
// (`tasksSpawn(handleTask(conn))` per connection); the JSON and router layers above are
// unchanged by that, because they know nothing about the transport.
//
//   GET  /health          -> {"status":"ok"}
//   GET  /json            -> {"message":"Hello, World!"}
//   POST /greet           <- {"name":"..."}   -> {"greeting":"Hello, ...!"}
//   POST /echo            <- any JSON         -> {"received":...,"kind":"..."}
//   GET  /fetch           -> the one upstream URL, decoded and re-shaped
//   anything else         -> 404 {"error":"..."}
//
//   express.exe [port] [requestLimit]     (defaults: 8095, unlimited)

fun parsePort(args: List<Str>): Int {
    if (args.size() > 0) {
        val parsed: Opt<Int> = args[0].toInt()
        if (parsed.hasValue()) {
            return parsed.value()
        }
    }
    return 8095
}

fun parseLimit(args: List<Str>): Int {
    if (args.size() > 1) {
        val parsed: Opt<Int> = args[1].toInt()
        if (parsed.hasValue()) {
            return parsed.value()
        }
    }
    return 0 - 1
}

// One connection: read once, parse the raw bytes into a `Request`, dispatch, write the
// `Response` back. The whole request->handler->response flow is these five lines.
fun handle(conn: Int64, app: App): Unit {
    val buffer: List<Int8> = netBuffer(netRecvCap())
    val count: Int = netReceive(conn, *buffer)
    if (count <= 0) {
        return
    }
    val raw: Str = netReceivedText(*buffer, count)
    val req: Request = parseRequest(raw)
    val res: Response = app.route(req)
    netSend(conn, *formatResponse(res))
}

fun main(args: List<Str>): Int {
    val app: App = App(List<Route>())
    app.get("/health", healthHandler)
    app.get("/json", jsonHandler)
    app.post("/greet", greetHandler)
    app.post("/echo", echoHandler)
    app.get("/fetch", fetchHandler)

    val port: Int = parsePort(args)
    val limit: Int = parseLimit(args)

    if (!netStartup()) {
        println("express: WSAStartup failed")
        return 1
    }
    val server: Int64 = netListen(port)
    if (server == 0 - 1) {
        println(fmtStr("express: cannot listen on port |", port.toString()))
        return 1
    }
    println(fmtStr("express: listening on http://127.0.0.1:|", netLocalPort(server).toString()))

    var served: Int = 0
    var running: Bool = true
    while (running) {
        val conn: Int64 = netAccept(server)
        if (conn == 0 - 1) {
            continue
        }
        handle(conn, app)
        netClose(conn)
        served = served + 1
        if (limit > 0 && served >= limit) {
            running = false
        }
    }

    netClose(server)
    netCleanup()
    println(fmtStr("express: served | requests", served.toString()))
    return 0
}
