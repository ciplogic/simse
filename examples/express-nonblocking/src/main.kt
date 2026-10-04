package expressapp

import sockets
import express

// The same express-like server as `examples/express`, on **non-blocking sockets**: one thread,
// one `WSAPoll` loop, many connections. Where the blocking version parks a worker on a socket
// for the whole keep-alive session (so more connections than workers starve), this keeps every
// open connection in a table, polls them, and only touches the ones that are ready - a thread
// never waits on a peer.
//
// The layers above the transport are unchanged: `parseRequest`/`app.route`/`formatResponse`
// are the exact functions `examples/express` uses (`examples/express/layers`); only this file -
// the accept/read/write plumbing - differs. It is the shape a TechEmpower-style build wants: a
// thread-per-core event loop, not a thread-per-connection.
//
// Caveats, deliberately: one thread (so one core's throughput; run one loop per core with
// socket-sharing for more), a fixed poll table (`netPollCapacity`), and a request is dispatched
// once its headers and `Content-Length` body have arrived (no chunked encoding).
//
//   express-nonblocking.exe [port] [requestLimit]     (defaults: 8096, unlimited)

data class Conn(var sock: Int64, var pending: Str, var out: Str, var outAt: Int, var closeAfter: Bool)

fun parsePort(args: List<Str>): Int {
    if (args.size() > 0) {
        val parsed: Opt<Int> = args[0].toInt()
        if (parsed.hasValue()) {
            return parsed.value()
        }
    }
    return 8096
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

// The `Content-Length` of the request whose headers are in `pending`, or `0` when there is none.
fun contentLength(pending: *Str): Int {
    val headerEnd: Int = pending.find("\r\n\r\n")
    if (headerEnd < 0) {
        return 0
    }
    val headers: Str = pending.substr(0, headerEnd).toLower()
    val at: Int = headers.find("content-length:")
    if (at < 0) {
        return 0
    }
    var i: Int = at + 15
    var value: Int = 0
    while (i < headers.size()) {
        val ch: Char = headers.charAt(i)
        if (ch >= '0' && ch <= '9') {
            value = value * 10 + (ch - '0')
        } else if (ch != ' ' && ch != '\t') {
            break
        }
        i = i + 1
    }
    return value
}

// Whether `pending` holds a whole request: the headers, and then the body the length names.
fun requestComplete(pending: *Str): Bool {
    val headerEnd: Int = pending.find("\r\n\r\n")
    if (headerEnd < 0) {
        return false
    }
    return pending.size() - (headerEnd + 4) >= contentLength(pending)
}

// One request from `pending`, dispatched; the response goes into `c.out`, and `c.pending` keeps
// whatever of the next request already arrived.
fun dispatch(c: *Conn, app: *App): Unit {
    val headerEnd: Int = c.pending.find("\r\n\r\n")
    val bodyStart: Int = headerEnd + 4
    val length: Int = contentLength(*c.pending)
    val requestText: Str = c.pending.substr(0, bodyStart + length)
    c.pending = c.pending.substr(bodyStart + length, c.pending.size() - bodyStart - length)
    val req: Request = parseRequest(requestText)
    val res: Response = app.route(req)
    c.out = formatResponse(res, req.keepAlive)
    c.outAt = 0
    if (!req.keepAlive) {
        c.closeAfter = true
    }
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
        println("express-nonblocking: WSAStartup failed")
        return 1
    }
    val server: Int64 = netListen(port)
    if (server == 0 - 1) {
        println(fmtStr("express-nonblocking: cannot listen on port |", port.toString()))
        return 1
    }
    netSetNonBlocking(server)
    println(fmtStr(
        "express-nonblocking: listening on http://127.0.0.1:| (poll, 1 thread)",
        netLocalPort(server).toString()
    ))

    // One receive buffer for the whole loop: the connections are read one at a time, so they
    // never need a buffer each.
    val buffer: List<Int8> = netBuffer(netRecvCap())
    val capacity: Int = netPollCapacity()
    var conns: List<Conn> = List<Conn>()
    var served: Int = 0
    var accepting: Bool = true

    while (accepting || conns.size() > 0) {
        // Slot 0 is the listener; slots 1..n are the open connections. A connection asks to be
        // polled for writing only while it still has unsent output.
        netPollSet(0, server, netPollReadEvent())
        var slots: Int = 1
        var i: Int = 0
        while (i < conns.size() && slots < capacity) {
            var events: Int = netPollReadEvent()
            if (conns[i].outAt < conns[i].out.size()) {
                events = events | netPollWriteEvent()
            }
            netPollSet(slots, conns[i].sock, events)
            slots = slots + 1
            i = i + 1
        }
        val polled: Int = slots - 1

        val ready: Int = netPoll(slots, 1000)
        if (ready <= 0) {
            continue
        }

        // Accept: the listener was polled readable, and there is room in the table. On the
        // request limit we stop accepting and let the open connections drain their response.
        if (accepting && netPollCanRead(netPollRevents(0)) && conns.size() + 1 < capacity) {
            val client: Int64 = netAccept(server)
            if (client != 0 - 1) {
                netSetNonBlocking(client)
                conns.append(Conn(client, Str(), Str(), 0, false))
                served = served + 1
                if (limit > 0 && served >= limit) {
                    accepting = false
                }
            }
        }

        // Serve the ready connections, then rebuild the table without the closed ones.
        var next: List<Conn> = List<Conn>()
        i = 0
        while (i < conns.size()) {
            var re: Int = 0
            if (i < polled) {
                re = netPollRevents(i + 1)
            }
            var closed: Bool = netPollError(re)
            if (!closed && netPollCanRead(re)) {
                while (true) {
                    val got: Int = netRecvNonBlocking(conns[i].sock, spanOf(buffer).atPtr(0), buffer.size())
                    if (got > 0) {
                        conns[i].pending.appendStr(netReceivedText(*buffer, got))
                    } else if (got == 0) {
                        closed = true
                        break
                    } else {
                        // -1 (WouldBlock) or -2 (a real error we drop the connection on).
                        if (got == 0 - 2) {
                            closed = true
                        }
                        break
                    }
                }
                while (!closed && requestComplete(*conns[i].pending)) {
                    dispatch(*conns[i], *app)
                }
            }
            if (!closed && netPollCanWrite(re) && conns[i].out.size() > 0) {
                val sent: Int = netSendNonBlocking(conns[i].sock, *conns[i].out, conns[i].outAt)
                if (sent > 0) {
                    conns[i].outAt = conns[i].outAt + sent
                } else if (sent == 0 - 2) {
                    closed = true
                }
            }
            if (conns[i].closeAfter && conns[i].outAt >= conns[i].out.size()) {
                closed = true
            }
            // Shutting down: once we stop accepting, a connection that has answered at least
            // once (`out` non-empty) and has nothing in flight is let go. A just-accepted
            // connection is not "idle" yet - it has not been read - so it is spared; otherwise
            // the limit would close it before its first request ever arrived.
            if (!accepting && conns[i].out.size() > 0 && conns[i].pending.size() == 0
                && conns[i].outAt >= conns[i].out.size()) {
                closed = true
            }
            if (closed) {
                netClose(conns[i].sock)
            } else {
                next.append(conns[i])
            }
            i = i + 1
        }
        conns = next
    }

    // Close whatever is left.
    var j: Int = 0
    while (j < conns.size()) {
        netClose(conns[j].sock)
        j = j + 1
    }
    netClose(server)
    netCleanup()
    println(fmtStr("express-nonblocking: served | connections", served.toString()))
    return 0
}
