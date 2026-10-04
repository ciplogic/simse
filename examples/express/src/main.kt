package express

import sockets

// An express-like JSON server: the composition root. It wires the routes (the handlers from
// `handlers.kt`) into the `App`, then runs the transport - an accept loop that hands each
// connection to the task scheduler. The layers below are `router.kt` (Request -> Response
// dispatch), `http.kt` (the wire and the JSON boundary), and the `jsonnode` module (the
// intermediate `JsonNode` reader/writer).
//
// The concurrency is one line and one constant: `tasksCompute(16, 16)` makes queue 16 a compute
// queue with 16 workers, and `tasksEnqueue(handleTask(conn, *app))` hands each connection to it.
// A blocking `netReceive`/`netSend` holds its worker, so 16 workers are 16 requests in flight -
// the worker count is the whole knob. The root keeps accepting while the handles run; when its
// loop ends, `tasksDrain()` waits for them. That is the same shape
// `examples/http/async-server` uses; here the JSON/router layers above it are untouched, because
// they know nothing about the transport.
//
// `handle` carries the `suspend` modifier only so the compiler declares its task factory
// (`handleTask`); its body never suspends, so the task is a straight run on a worker thread.
//
//   GET  /health          -> {"status":"ok"}
//   GET  /json            -> {"message":"Hello, World!"}
//   POST /greet           <- {"name":"..."}   -> {"greeting":"Hello, ...!"}
//   POST /echo            <- any JSON         -> {"received":...,"kind":"..."}
//   GET  /fetch           -> the one upstream URL, decoded and re-shaped
//   anything else         -> 404 {"error":"..."}
//
//   express.exe [port] [requestLimit] [workers]   (defaults: 8095, unlimited, 16)

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

fun parseWorkers(args: List<Str>): Int {
    if (args.size() > 2) {
        val parsed: Opt<Int> = args[2].toInt()
        if (parsed.hasValue() && parsed.value() > 0) {
            return parsed.value()
        }
    }
    return 16
}

// One connection, on a compute queue's worker: read, parse the raw bytes into a `Request`,
// dispatch, write the `Response` back, and keep the socket for the next request unless the
// client asked to close. The whole request->handler->response flow is these lines; only the
// transport made it a task, and the keep-alive loop is what lets 16 workers hold 16 connections.
suspend fun handle(conn: Int64, app: *App): Unit {
    val buffer: List<Int8> = netBuffer(netRecvCap())
    var keepAlive: Bool = true
    while (keepAlive) {
        val count: Int = netReceive(conn, *buffer)
        if (count <= 0) {
            break
        }
        val raw: Str = netReceivedText(*buffer, count)
        val req: Request = parseRequest(raw)
        val res: Response = app.route(req)
        keepAlive = req.keepAlive
        netSend(conn, *formatResponse(res, keepAlive))
    }
    netClose(conn)
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
    val workers: Int = parseWorkers(args)

    // The whole concurrency: one compute queue, `workers` worker threads. A second call would
    // only add workers; the ids 16 and up are the program's own by convention.
    tasksCompute(16, workers)

    if (!netStartup()) {
        println("express: WSAStartup failed")
        return 1
    }
    val server: Int64 = netListen(port)
    if (server == 0 - 1) {
        println(fmtStr("express: cannot listen on port |", port.toString()))
        return 1
    }
    println(fmtStr(
        "express: listening on http://127.0.0.1:| (| workers)",
        netLocalPort(server).toString(), workers.toString()
    ))

    var served: Int = 0
    var running: Bool = true
    while (running) {
        val conn: Int64 = netAccept(server)
        if (conn != 0 - 1) {
            // Hand the connection out and step aside; the handle runs on a worker.
            tasksEnqueue(handleTask(conn, *app))
        }
        served = served + 1
        if (limit > 0 && served >= limit) {
            running = false
        }
    }

    netClose(server)
    // Wait for the handles still in flight before tearing Winsock down (the sync-root join).
    tasksDrain()
    netCleanup()
    println(fmtStr("express: served | requests", served.toString()))
    return 0
}
