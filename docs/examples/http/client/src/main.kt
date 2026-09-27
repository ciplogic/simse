package httpclient

import sockets

// A minimal HTTP/1.1 client over the same `sockets` module: connect, send one `GET` with
// `Connection: close`, read until the peer closes, print the response. It is the counterpart the
// server is tested against (and why the module exposes connect/send/recv, not just the server
// side) - a browser or `curl` would do as well.
//
//   httpclient.exe [port] [path] [requests]
//
// One request (the default) prints the response. More than one repeats it - a *fresh* connection
// each time, since the server answers `Connection: close` - and prints a throughput line instead:
// `requests` is the only knob, so the same client is the server's request-rate measurement
// (`docs/examples/http/README.md`).

// One request/response exchange on its own connection. `connectTries` retries a failed connect
// every 50 ms, so a client started just behind the server still works; a server already up is
// reached on the first try, which is what keeps a timed run free of sleeps. "" means it could not
// be reached (or the response was empty).
fun exchange(port: Int, request: *Str, connectTries: Int): Str {
    var conn: Int64 = 0 - 1
    var tries: Int = 0
    while (conn == 0 - 1 && tries < connectTries) {
        conn = netConnect(netLoopback(), port)
        if (conn == 0 - 1) {
            netSleep(50)
            tries = tries + 1
        }
    }
    if (conn == 0 - 1) {
        return ""
    }
    netSend(conn, request)
    // The body arrives in as many reads as it takes and its length is not known ahead, so this is
    // the one place to append rather than `fmtStr` (each append grows the same buffer; `out = out +
    // part` would rebuild it).
    var response: Str = ""
    val buffer: List<Int8> = netBuffer(netRecvCap())
    var n: Int = netReceive(conn, *buffer)
    while (n > 0) {
        response.appendStr(netReceivedText(*buffer, n))
        n = netReceive(conn, *buffer)
    }
    netClose(conn)
    return response
}

fun main(args: List<Str>): Int {
    var port: Int = 8080
    var path: Str = "/"
    var requests: Int = 1
    if (args.size() > 0) {
        val parsed: Opt<Int> = args[0].toInt()
        if (parsed.hasValue()) {
            port = parsed.value()
        }
    }
    if (args.size() > 1) {
        path = args[1]
    }
    if (args.size() > 2) {
        val parsed: Opt<Int> = args[2].toInt()
        if (parsed.hasValue()) {
            requests = parsed.value()
        }
    }

    if (!netStartup()) {
        println("client: WSAStartup failed")
        return 1
    }
    val request: Str = fmtStr(
        "GET | HTTP/1.1\r\nHost: 127.0.0.1:|\r\nConnection: close\r\n\r\n", path, port.toString()
    )

    if (requests <= 1) {
        val response: Str = exchange(port, *request, 40)
        if (response.size() == 0) {
            println(fmtStr("client: cannot reach 127.0.0.1:|", port.toString()))
            netCleanup()
            return 1
        }
        print(response)
        netCleanup()
        return 0
    }

    // The timed run: `requests` exchanges, each a connect + send + read-to-close + close, so the
    // micros cover the whole request, not just the server's handler. Micros per request is the
    // rate's own inverse (the division is exact enough that the pair is self-consistent).
    val started: Int64 = nowMicros()
    var bytes: Int = 0
    var i: Int = 0
    while (i < requests) {
        val response: Str = exchange(port, *request, 40)
        if (response.size() == 0) {
            println(fmtStr("client: request | of | failed", i.toString(), requests.toString()))
            netCleanup()
            return 1
        }
        bytes = bytes + response.size()
        i = i + 1
    }
    val elapsed: Int64 = nowMicros() - started
    val microsEach: Int64 = elapsed / requests
    var perSecond: Int64 = 0
    if (microsEach > 0) {
        perSecond = 1000000 / microsEach
    }
    netCleanup()
    println(fmtStr(
        "bench: | requests in | us (| us/request, ~| req/s, | bytes)",
        requests.toString(), elapsed.toString(),
        microsEach.toString(), perSecond.toString(), bytes.toString()
    ))
    return 0
}
