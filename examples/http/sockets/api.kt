package sockets

// A minimalist **blocking** socket library: a P/Invoke wrapper over Winsock2 (`ws2_32.dll` -
// present on every Windows install, ARM64 included) built on the `native` generator
// (impl_specs/native-interop.md). Nothing is linked and no import library is needed; the DLL is
// resolved on first call, exactly as `examples/sdl2` does for SDL2.
//
// The model is one thread and fully blocking calls - `accept`, `recv`, `send` - which is what a
// single-threaded server wants. There is deliberately no async I/O here: one connection at a
// time keeps a server small, and IOCP/`select` can be a later addition without changing this
// surface.
//
// The ABI structs (`SOCKADDR_IN`, `WSADATA`) and the byte-order swap are C++ (`_res.md`), since
// Simse can neither lay a struct out at the platform's alignment nor reinterpret a pointer. Every
// function in that glue is pure layout/pointer work: it calls no Winsock function, so nothing has
// to be linked.

// ---- the Winsock exports, resolved from ws2_32.dll ----
//
// The Simse names carry a `wsa`/`net` prefix so they cannot collide with a program's own words;
// the second attribute argument is the exported symbol.

@SmGen("native", "ws2_32.dll", "WSAStartup")
fun wsaStartup(versionRequested: Int, wsaData: *Int8): Int

@SmGen("native", "ws2_32.dll", "WSACleanup")
fun wsaCleanup(): Int

@SmGen("native", "ws2_32.dll", "socket")
fun wsaSocket(family: Int, type: Int, protocol: Int): Int64

@SmGen("native", "ws2_32.dll", "bind")
fun wsaBind(s: Int64, name: *Int8, nameLength: Int): Int

@SmGen("native", "ws2_32.dll", "listen")
fun wsaListen(s: Int64, backlog: Int): Int

@SmGen("native", "ws2_32.dll", "accept")
fun wsaAccept(s: Int64, address: *Int8, addressLength: *Int8): Int64

@SmGen("native", "ws2_32.dll", "connect")
fun wsaConnect(s: Int64, name: *Int8, nameLength: Int): Int

@SmGen("native", "ws2_32.dll", "send")
fun wsaSend(s: Int64, bytes: *Char, length: Int, flags: Int): Int

@SmGen("native", "ws2_32.dll", "recv")
fun wsaRecv(s: Int64, bytes: *Int8, length: Int, flags: Int): Int

@SmGen("native", "ws2_32.dll", "closesocket")
fun wsaClose(s: Int64): Int

@SmGen("native", "ws2_32.dll", "getsockname")
fun wsaGetSockName(s: Int64, name: *Int8, nameLength: *Int): Int

@SmGen("native", "ws2_32.dll", "setsockopt")
fun wsaSetSockOpt(s: Int64, level: Int, optionName: Int, optionValue: *Int, optionLength: Int): Int

// A little `Sleep`, so a client can wait for a server that is still starting. It is not a socket
// call, but it belongs to the same "reach the platform" story and keeps the module dependency
// free (kernel32.dll is always loadable).
@SmGen("native", "kernel32.dll", "Sleep")
fun netSleep(milliseconds: Int): Unit

// ---- the ABI glue (the module's `_res.md`): structs and pointer plumbing only ----

@SmGen("res", "netglue", "simse_net_wsaData")
fun netWsaData(): *Int8

@SmGen("res", "netglue", "simse_net_addr")
fun netAddr(): *Int8

@SmGen("res", "netglue", "simse_net_addrSize")
fun netAddrSize(): Int

@SmGen("res", "netglue", "simse_net_fillAddr")
fun netFillAddr(addr: *Int8, ip: Int, port: Int): Int

@SmGen("res", "netglue", "simse_net_readPort")
fun netReadPort(addr: *Int8): Int

@SmGen("res", "netglue", "simse_net_bytesToStr")
fun netBytesToStr(bytes: *Int8, count: Int): Str

// The receive buffer a caller owns: a heap `List<Int8>` of `size` bytes. Two queue threads can each
// read a connection at once, because the library keeps no buffer of its own.
@SmGen("res", "netglue", "simse_net_buffer")
fun netBuffer(size: Int): List<Int8>

// The conventional read size a caller's `netBuffer` uses: a plain Simse function, not a resource -
// a constant needs no C++. A caller that needs more reads another chunk into the same buffer.
fun netRecvCap(): Int {
    return 2048
}

// ---- the library surface ----
//
// The Winsock constants are written where they are used (AF_INET 2, SOCK_STREAM 1, IPPROTO_TCP 6)
// rather than exposed, since a program that needs a raw socket can still reach `wsaSocket`.

// Winsock 2.2; `true` when it initialized. `WSAStartup` writes its `WSADATA` into the glue's
// scratch, which is why the call takes a pointer the caller never sees.
fun netStartup(): Bool {
    return wsaStartup(514, netWsaData()) == 0
}

fun netCleanup(): Unit {
    wsaCleanup()
}

// 127.0.0.1 in host order, for `netConnect` (see `netFillAddr`).
fun netLoopback(): Int {
    return 2130706433
}

// A listening TCP socket on every interface at `port`, or -1. `port == 0` asks the system to
// choose one; read it back with `netLocalPort`.
fun netListen(port: Int): Int64 {
    val s: Int64 = wsaSocket(2, 1, 6)
    if (s == 0 - 1) {
        return 0 - 1
    }
    // SO_REUSEADDR (SOL_SOCKET 0xFFFF, SO_REUSEADDR 4), so a server restarted within the two
    // minutes a closed connection sits in TIME_WAIT can still bind the port. The option value is
    // one `int` the call must be able to write-read, so it is a plain Simse local and the call
    // takes its address - no C++ needed for a word.
    var reuse: Int = 1
    wsaSetSockOpt(s, 65535, 4, *reuse, 4)
    val addrLength: Int = netFillAddr(netAddr(), 0, port)
    if (wsaBind(s, netAddr(), addrLength) != 0) {
        wsaClose(s)
        return 0 - 1
    }
    if (wsaListen(s, 128) != 0) {
        wsaClose(s)
        return 0 - 1
    }
    return s
}

// The port a socket is actually bound to, or -1.
fun netLocalPort(s: Int64): Int {
    // `getsockname` takes the buffer's length in and writes the used length out, so it is a local
    // whose address the call takes; the glue supplies the size, Simse the storage.
    var nameLength: Int = netAddrSize()
    if (wsaGetSockName(s, netAddr(), *nameLength) != 0) {
        return 0 - 1
    }
    return netReadPort(netAddr())
}

// Blocks until a client connects; the connection socket, or -1. The peer address is not asked
// for - a request tells a server everything it needs.
fun netAccept(server: Int64): Int64 {
    return wsaAccept(server, null, null)
}

// Blocks until the connection to `ip:port` (host order) is up; the socket, or -1.
fun netConnect(ip: Int, port: Int): Int64 {
    val s: Int64 = wsaSocket(2, 1, 6)
    if (s == 0 - 1) {
        return 0 - 1
    }
    val addrLength: Int = netFillAddr(netAddr(), ip, port)
    if (wsaConnect(s, netAddr(), addrLength) != 0) {
        wsaClose(s)
        return 0 - 1
    }
    return s
}

// Every byte of `text`; `false` when the peer is gone. One `send` may take only part of it, so the
// bytes go out in a loop. The bytes are the `Str`'s own, reached as the language's view of them
// (`spanOfStr` is `Span<Char>`, and `atPtr` is the element's place) - no C++ to find the address.
fun netSend(s: Int64, text: *Str): Bool {
    val total: Int = text.size()
    val bytes: StrView = spanOfStr(text)
    var sent: Int = 0
    while (sent < total) {
        val n: Int = wsaSend(s, bytes.atPtr(sent), total - sent, 0)
        if (n <= 0) {
            return false
        }
        sent = sent + n
    }
    return true
}

// One blocking read of up to `capacity` bytes into a caller's `buffer`; the byte count, 0 when the
// peer closed, -1 on error. This is the raw form - `buffer` is a pointer a caller gets from a
// `List<Int8>` with `spanOf(*bytes).atPtr(0)` - and `netReceive` is the one a server usually wants.
fun netRecv(s: Int64, buffer: *Int8, capacity: Int): Int {
    return wsaRecv(s, buffer, capacity, 0)
}

// One blocking read into a caller-owned heap buffer; the byte count, 0 when the peer closed, -1 on
// error. Read the bytes with `netReceivedText`. The buffer is the caller's - a `List<Int8>` from
// `netBuffer`, usually a task's own field - so two threads serving two connections never share one.
fun netReceive(s: Int64, buffer: *List<Int8>): Int {
    return wsaRecv(s, spanOf(buffer).atPtr(0), buffer.size(), 0)
}

// The `count` bytes just read into `buffer`, as an owned `Str`.
fun netReceivedText(buffer: *List<Int8>, count: Int): Str {
    return netBytesToStr(spanOf(buffer).atPtr(0), count)
}

fun netClose(s: Int64): Unit {
    wsaClose(s)
}
