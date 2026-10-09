# An express-like JSON server, on non-blocking sockets

The same server as [`examples/express`](../express/README.md) - the same routes, the same handlers,
the same JSON reader/writer - with one difference: the transport. Where the blocking server parks a
worker on a connection for the whole keep-alive session (so it needs at least as many workers as
open connections), this one parks nothing. It keeps every open connection in a table, waits on all
of them with a single `WSAPoll`, and only ever touches the ones that are ready.

It exists to answer one question: with the JSON/router layers untouched, how far does the blocking
transport's *worker-count ceiling* matter, and what does it cost to remove it?

## The one difference

The layers are shared, not copied. `layers/http.kt`, `layers/router.kt` and `layers/handlers.kt`
in `examples/express` are built as a module and imported here:

```
   blocking (examples/express)            non-blocking (this example)
   --------------------------             ---------------------------
   main.kt: accept loop                   src/main.kt: WSAPoll loop
     tasksEnqueue(handle(conn))             poll all conns, serve the ready ones
     worker: blocking recv/send             thread: non-blocking recv/send
   ------------------------------------   ------------------------------------
                    layers/http.kt   Request | Response | parseRequest | formatResponse
                    layers/router.kt Handler = Request -> Response, Route, App
                    layers/handlers.kt          /health /json /greet /echo /fetch
```

`parseRequest`, `app.route` and `formatResponse` are byte-for-byte the functions the blocking
server uses. The abstraction boundary is real: only the accept/read/write plumbing differs.

## How the loop works

Per iteration:

1. Fill the poll table: slot 0 the listener, slots 1..n the open connections. A connection asks
   to be polled for writing only while it still has an unsent tail of its response.
2. `WSAPoll` with a 1 s timeout.
3. Accept if the listener is readable and the table has room.
4. For each ready connection: read until `WSAEWOULDBLOCK`, dispatch every complete request
   (a request is complete once its headers and `Content-Length` body have arrived), then write as
   much of the pending response as the socket takes.
5. Drop closed connections and rebuild the table.

State is one `data class Conn` per connection - its socket, the bytes received, the bytes still to
send, and whether to close after those bytes. A half-sent response is *not* an error: `outAt`
tracks the offset, and the next iteration polls for writability and sends the tail.

`Content-Length` is what makes the parser incremental. The blocking server is one `recv` per
request; this one accumulates into `pending` until the framing says a whole request is there, then
dispatches and keeps whatever of the next request already arrived.

## Build and run

From the repository root (the compiler must be built first: `build.bat --release`):

```bat
bin\simse.exe --root examples/express-nonblocking/src ^
    --module examples/express/layers --module examples/http/sockets ^
    --module modules/http --module modules/jsonnode ^
    -o examples/express-nonblocking/out.cpp
bun build.js --release --cpp examples/express-nonblocking/out.cpp ^
    --exe examples/express-nonblocking/express-nonblocking.exe

:: foreground; port 8096, no limit
examples\express-nonblocking\express-nonblocking.exe 8096
```

`express-nonblocking.exe [port] [requestLimit]` defaults to port `8096` and no limit. The limit
counts accepted connections and is what lets a script run it without leaving a process behind.

Routes are the shared ones: `GET /health`, `GET /json`, `POST /greet`, `POST /echo`, `GET /fetch`,
and a `404` JSON error otherwise.

## Benchmark

`bench/compare.mjs` drives all three servers through one `fetch` pool; `build/bombardier.exe` is
the measurement below (it reaches the servers' real headroom, where the `fetch` client becomes the
bottleneck). 3 s per run, 0 errors throughout, Windows on ARM64 (18 logical cores):

| connections | this example, 1 thread | `examples/express`, 16 workers | `Bun.serve` |
| --- | --- | --- | --- |
| 16 | ~139,000 req/s | ~372,000 req/s | ~140,000 req/s |
| 64 | ~144,000 req/s | ~348,000 req/s | ~142,000 req/s |
| 128 | ~137,000 req/s | ~345,000 req/s | ~137,000 req/s |
| 256 | ~131,000 req/s | ~356,000 req/s | ~136,000 req/s |

Two things to read out of this:

- **The ceiling is gone.** The blocking server needs a worker per connection; drive it with more
  connections than workers and the surplus connections go unread and the client stalls (that is why
  `compare.mjs` gives it one worker per connection). This server holds 256 connections on **one**
  thread and stays flat - ~135k req/s from 16 to 256 connections, matching `Bun.serve` on the same
  machine, on one core.
- **Throughput is per-core.** The blocking server is faster in absolute terms only because it is
  using 16 cores; per core the two are close (the blocking figure is ~22k/core, this one ~135k on
  its single core). The event loop is the shape that scales across cores: run one loop per core,
  sharing the listener, and the 16-core figure follows. That replication is the next step, not
  this one.

### Memory

Under load (`bench/memory.mjs`, sampling the server process with `Get-Process`):

| | private bytes (idle -> peak) | working set (idle -> peak) |
| --- | --- | --- |
| this example, c=16 | ~1.0 MB -> ~1.2 MB | ~6.6 MB -> ~6.8 MB |
| this example, c=64 | ~1.0 MB -> ~1.5 MB | ~6.6 MB -> ~7.2 MB |

The connection table (one `Conn` per open connection) is the only thing that grows with
concurrency, and it is small: 64 connections add ~0.5 MB of private bytes.

## What to look at

- `src/main.kt` - the whole transport: the poll-table fill, the accept, the read/dispatch/write
  pass, and the close. `Conn` is the per-connection state.
- `examples/http/sockets/api.kt` - the non-blocking surface added to the blocking `sockets` module:
  `netSetNonBlocking`, `netRecvNonBlocking`, `netSendNonBlocking`, `netPoll`, and the poll-table
  setters. The `WSAPOLLFD` array itself is C++ (`examples/http/sockets/_res.md`), since Simse
  cannot spell the struct's layout.
- `examples/express/layers/*` - unchanged by this example, and that is the point.

## Caveats

- One poll loop, one thread. The design is deliberately one screen; see the benchmark note on
  replicating it per core.
- A fixed poll table (`netPollCapacity`, 1024 slots in `_res.md`). More open connections than slots
  are simply not polled - a production build would grow it.
- `Content-Length` framing only; no chunked transfer-encoding, and one request is dispatched once
  its body has fully arrived.
- `GET /fetch` still blocks the one thread for the duration of the outbound request - the one place
  this design is not non-blocking yet.
- Windows only (Winsock2). Build outputs (`out.cpp`, `express-nonblocking.exe`) are git-ignored.
