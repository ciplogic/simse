# Sockets and an HTTP/1.1 server in Simse

What lives here:

```
sockets/     the `sockets` module (package `sockets`): a minimalist *blocking* socket library
  api.kt       a P/Invoke wrapper over Winsock2 (ws2_32.dll), built on the `native` generator
  _res.md      the ABI glue: SOCKADDR_IN/WSADATA layout, the byte swap, the receive buffer
server/src/  `httpd` - a single-threaded, fully blocking HTTP/1.1 server over `sockets`
async-server/  the same routes on the task scheduler (`suspend`, `tasksSpawn`, keep-alive)
  README.md     its benchmark: throughput and memory against a minimal ASP.NET Core server
client/src/  `httpclient` - a minimal HTTP/1.1 client (or throughput driver) over the same
demo.bat        build, start the server, and fetch three routes
bench.bat       build, start a server, and measure requests/second
```

The socket library came first, and the server is what it was written for. Nothing is linked and
no import library is used: `ws2_32.dll` is resolved at run time on first call, exactly as
`../sdl2` does for SDL2. The model is one thread and fully blocking calls (`accept`, `recv`,
`send`) - for a single-threaded server that is the simplest correct thing, and there is no async
I/O here because none is needed for one connection at a time.

## Build and run

From the repository root (the compiler must be built first: `build.bat --release`):

```bat
:: 1. transpile the server (and the client), each with the sockets module
simse.exe --root examples/http/server/src --module examples/http/sockets -o examples/http/server/out.cpp
simse.exe --root examples/http/client/src --module examples/http/sockets -o examples/http/client/out.cpp

:: 2. compile the two amalgamations
bun build.js --release --cpp examples/http/server/out.cpp --exe examples/http/server/httpd.exe
bun build.js --release --cpp examples/http/client/out.cpp --exe examples/http/client/httpclient.exe

:: 3. run the server (foreground; Ctrl+C stops it), then hit it
docs\examples\http\server\httpd.exe 8080
:: ... in another terminal, with the Simse client or anything else:
docs\examples\http\client\httpclient.exe 8080 /
curl http://127.0.0.1:8080/json
```

`demo.bat` in this folder does the build, starts the server on 8099 (limited to three requests,
so it exits on its own), and runs the client against `/`, `/json` and a missing path.

`bench.bat [connections] [requestsEach] [path]` is the throughput measurement - see below.

The server takes `httpd.exe [port] [requestLimit]` - the limit is what lets a script run it
without leaving a process behind, and the server prints a served-requests summary when it reaches
it.

## Throughput

One blocking client, and then ten concurrent ones. Both sides report - each client its own round
trip, the server the aggregate - and the server's line is the one that matters as the connection
count grows.

```bat
bench.bat 1 10000 /     :: one connection, 10000 requests
bench.bat 10 1000 /     :: ten connections, 1000 requests each (10000 total)
```

Release build (`bun build.js --release`), this machine, loopback, a fresh connection per request
(the server answers `Connection: close`, so every request pays the TCP handshake and the close):

| connections | requests | per client | server |
| --- | --- | --- | --- |
| 1 | 10,000 | 109 us/request (~9,174 req/s) | 10,000 in 1.097 s - ~9,174 req/s |
| 10 | 1,000 each | ~270 us/request (~3,700 req/s) | 10,000 in 0.306 s - **~33,333 req/s** |

(A second run, after the glue below was cut back to the platform's structs, measured 111 us and
29 us per request - ~9,009 and ~34,482 req/s; a few percent is the run-to-run spread.)

So **~9k requests/second with one connection and ~33k with ten**. The reason is where the time
goes: at one connection the 109 us is the *client's* round trip (connect, send, wait, read,
close) and the server sits idle between requests; at ten, the accept loop never waits and the
server's own cost - handshake, read, route, format, write, close - is the ~30 us that sets the
aggregate. The two sides agree (10 x 3,700 is about 33,000). A single thread is not the
bottleneck at one connection; it is at ten.

With `connections` 1, raise `requestsEach` by 10x until a run passes ~500 ms: at 100, 1000 and
10000 requests the per-request cost is 133, 121 and 109 us, so the fixed cost is small and the
per-request one is what the table above reports (`bench.bat 1 100`).

## Routes

| request | response |
| --- | --- |
| `GET /` or `GET /hello` | `200`, `text/plain`, `Hello, world!` |
| `GET /json` | `200`, `application/json`, `{"name":"Hello world"}` |
| anything else | `404`, `text/plain`, `not found` |

Each response is `Connection: close`, so the connection carries exactly one request - which is
also why the single-threaded, one-connection-at-a-time loop is honest rather than a shortcut.

## What to look at

- `sockets/api.kt` - the Winsock exports as `@SmGen("native", "ws2_32.dll", ...)` declarations,
  and the small Simse surface on top (`netStartup`, `netListen`, `netAccept`, `netSend`,
  `netReceive`, ...). The raw form is there too (`netRecv` with a caller's buffer, `wsaConnect`),
  because a wrapper that hides everything is not a wrapper anyone can debug against.
- `sockets/_res.md` - the only C++, and it is deliberately small: the platform's `SOCKADDR_IN`
  and `WSADATA` (their size, their layout, and the scratch the platform writes through), the
  host/network byte swap, the one receive buffer, and the one `memcpy` that turns read bytes into
  a `Str`. Everything that is only a *value* stays in Simse: the option `int` and the name-length
  are locals the call takes the address of (`*reuse`, `*nameLength`), and a `Str`'s bytes are
  reached as the language's own view (`spanOfStr(text).atPtr(sent)`). No function there calls
  Winsock, which is why the program links nothing - and the byte swap is written out rather than
  calling `htons`, for the same reason.
- `server/src/main.kt` - the whole server is four small functions and one blocking loop. The route
  table is a chain of `if`s on the request's path; there is no HTTP library underneath it.

## Caveats

- Windows only: the library is Winsock2 (`ws2_32.dll`), reached through `LoadLibraryA`.
- Single-threaded and blocking: a second client waits until the first connection is closed. That
  is the design (and why the server is small), not an oversight.
- There is no read timeout, so a client that connects and then says nothing holds the server -
  one thread, one connection. `SO_RCVTIMEO` through `wsaSetSockOpt` would be the fix; the
  benchmark sidesteps it by starting a fresh server per run and giving it a request limit.
- The request is read with one `recv` into the library's buffer; a request whose headers do not
  arrive in that one read would be truncated. The first line - all the routing needs - is there.
- Build outputs (`out.cpp`, the `.exe`s, `httpd.log`) are git-ignored.
