package httpdasync

import sockets

// The async twin of `../server`, on the task scheduler (impl_specs/async.md). The root task is
// `main` itself (a suspending `main` is the root), and it hands each accepted connection to a
// compute queue with `tasksSpawn(handleTask(conn))` - `handleTask` is the factory the compiler
// declares for the suspending `handle` - then steps aside, so the handle runs on a queue's thread
// while the root goes back to `accept`. The route dispatch is a suspension of its own (`bodyFor`),
// so a request crosses task boundaries; the blocking `netReceive`/`netSend` are ordinary code
// inside `handle`.
//
// Four compute queues (`tasksCompute(16, 1)` … `(19, 1)`) mean four handles run at once. The root
// runs on the caller's own queue, so its blocking `accept` parks only the caller's thread and the
// four compute queues stay free for handles. When the request limit is reached the root returns, and
// the runtime keeps running until the spawned handles have all finished (the `live` count).
//
//   GET /        -> text/plain        Hello, world!
//   GET /hello   -> the same
//   GET /json    -> application/json  {"name":"Hello world"}
//   anything els -> 404
//
// The port and the request limit are constants (8099, 2000) because a suspending `main(args)` is
// not lowered yet; the limit is what lets a benchmark run leave by itself.

fun firstLine(request: Str): Str {
    val at: Int = request.find("\r\n")
    if (at < 0) {
        return request
    }
    return request.substr(0, at)
}

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

// The routes: `suspend`, so building a body is a task round trip.
suspend fun routeHello(): Str {
    return "Hello, world!\n"
}

suspend fun routeJson(): Str {
    return "{\"name\":\"Hello world\"}"
}

// The dispatch is a suspension of its own - it calls a route - so a request crosses two task
// boundaries before a byte is written.
suspend fun bodyFor(path: Str): Str {
    if (path == "/json") {
        return routeJson()
    }
    if (path == "/" || path == "/hello") {
        return routeHello()
    }
    return "not found\n"
}

fun statusFor(path: Str): Str {
    if (path == "/json" || path == "/hello" || path == "/") {
        return "200 OK"
    }
    return "404 Not Found"
}

fun contentTypeFor(path: Str): Str {
    if (path == "/json") {
        return "application/json"
    }
    return "text/plain; charset=utf-8"
}

// One response, one `send` (the head is a fixed shape, and sending it apart from the body meets
// Nagle and the peer's delayed ACK - the sync server's lesson, kept). `keepAlive` says whether the
// connection stays up for another request.
fun respond(conn: Int64, status: Str, contentType: Str, body: Str, keepAlive: Bool): Unit {
    var header: Str = "close"
    if (keepAlive) {
        header = "keep-alive"
    }
    val response: Str = fmtStr(
        "HTTP/1.1 |\r\nContent-Type: |\r\nContent-Length: |\r\nConnection: |\r\n\r\n|",
        status, contentType, body.size().toString(), header, body
    )
    netSend(conn, *response)
}

// One connection, on a compute queue's thread: the blocking receive and send are ordinary code
// around the suspended dispatch. HTTP/1.1 keep-alive is honoured - a client that asks for `close`
// (our own client does) gets one request per connection, and one that does not (a benchmark tool)
// reuses the connection, which is what keeps it from burning ephemeral ports. The task closes its
// own connection; the root handed it off, so the root must not.
suspend fun handle(conn: Int64): Unit {
    val buffer: List<Int8> = netBuffer(netRecvCap())
    var keepAlive: Bool = true
    while (keepAlive) {
        val count: Int = netReceive(conn, *buffer)
        if (count <= 0) {
            break
        }
        val request: Str = netReceivedText(*buffer, count)
        val path: Str = targetPath(firstLine(request))
        keepAlive = request.find("Connection: close") < 0
        val body: Str = bodyFor(path)
        respond(conn, statusFor(path), contentTypeFor(path), body, keepAlive)
    }
    netClose(conn)
}

// The root task: accept a connection, hand it out, repeat. `tasksSpawn` suspends this task - so the
// handle gets a turn - and the loop resumes it on its own queue; the handle it handed out is a
// separate task on a compute queue.
suspend fun main(): Int {
    val port: Int = 8099
    val limit: Int = 2000

    // Four compute queues, one worker each: four requests in flight.
    tasksCompute(16, 1)
    tasksCompute(17, 1)
    tasksCompute(18, 1)
    tasksCompute(19, 1)

    if (!netStartup()) {
        println("httpd-async: WSAStartup failed")
        return 1
    }
    val server: Int64 = netListen(port)
    if (server == 0 - 1) {
        println(fmtStr("httpd-async: cannot listen on port |", port.toString()))
        return 1
    }
    println(fmtStr("httpd-async: listening on http://127.0.0.1:|", netLocalPort(server).toString()))

    val started: Int64 = nowMicros()
    var served: Int = 0
    while (served < limit) {
        val conn: Int64 = netAccept(server)
        if (conn != 0 - 1) {
            tasksSpawn(handleTask(conn))
        }
        served = served + 1
    }

    netClose(server)
    // No `netCleanup()`: the spawned handles are still using Winsock, and the runtime keeps going
    // until they have finished - the process exit is what tears it down.

    val elapsed: Int64 = nowMicros() - started
    val microsEach: Int64 = elapsed / served
    var perSecond: Int64 = 0
    if (microsEach > 0) {
        perSecond = 1000000 / microsEach
    }
    println(fmtStr(
        "httpd-async: served | requests in | us (| us/request, ~| req/s)",
        served.toString(), elapsed.toString(), microsEach.toString(), perSecond.toString()
    ))
    return 0
}
