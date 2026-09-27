package fixtures

import sockets

// The `sockets` module's own end-to-end check: a loopback connection inside one process. Listen on
// an ephemeral port (0), read the chosen port back, connect to it, accept, then push a line each
// way. One thread, every call blocking, so the order is fixed and the output is deterministic -
// no second process and no timing. The module is named by `compiler-args`
// (`--module docs/examples/http/sockets`).

fun main(): Int {
    if (!netStartup()) {
        println("startup failed")
        return 1
    }
    val server: Int64 = netListen(0)
    if (server == 0 - 1) {
        println("listen failed")
        return 1
    }
    val port: Int = netLocalPort(server)
    println(port > 0)

    val client: Int64 = netConnect(netLoopback(), port)
    if (client == 0 - 1) {
        println("connect failed")
        return 1
    }
    val conn: Int64 = netAccept(server)
    if (conn == 0 - 1) {
        println("accept failed")
        return 1
    }

    // client -> server
    var request: Str = "ping"
    println(netSend(client, *request))
    val serverBuf: List<Int8> = netBuffer(netRecvCap())
    val count: Int = netReceive(conn, *serverBuf)
    println(netReceivedText(*serverBuf, count))

    // server -> client
    var answer: Str = "pong"
    println(netSend(conn, *answer))
    val clientBuf: List<Int8> = netBuffer(netRecvCap())
    val back: Int = netReceive(client, *clientBuf)
    println(netReceivedText(*clientBuf, back))

    netClose(client)
    netClose(conn)
    netClose(server)
    netCleanup()
    return 0
}
