# An express-like JSON server

A small server where a handler is `Request -> Response`, the request body is decoded from JSON
into an intermediate `JsonNode`, the handler transforms it, and the response is encoded back. It
is the shape `impl_specs/user-language-roadmap.md` calls "the request->handler->response flow
feels like Node", built from parts the language already has: the `sockets` module under a task
scheduler for the transport, the `http` module for the outbound request, and a `JsonNode` tagged
union for the payload.

## The layers

```
              bytes                    Request/Response            JsonNode
  client ───────────────►  http.kt  ───────────────────►  router.kt  ─────────►  handlers.kt
         ◄───────────────            ◄───────────────────            ◄─────────
              bytes                    Response                     JsonNode
```

| file | what it owns |
| --- | --- |
| `src/main.kt` | the composition root: wire the routes into the `App`, accept and fan each connection to a 16-worker compute queue |
| `src/router.kt` | `Handler = Request -> Response`, `Route`, `App` (`get`/`post`/`route`) |
| `src/http.kt` | `Request`/`Response`, `parseRequest`/`formatResponse`, and the JSON boundary (`Request.json()`, `jsonResponse`) |
| `src/handlers.kt` | the four route handlers - the actual transformations |
| `src/modules/jsonnode` | the intermediate `JsonNode` union and its reader (`parseJson`) and writer (`JsonNode.toJson()`) |

A handler sees only `Request` and `Response`. It reaches into the payload with `get`/`at`/`text`/
`number`/`boolean`, builds a new node with `jsonObject`/`jsonArray`/`jsonText`/`jsonNumber`, and
never touches a socket, a header, or a byte. The concurrency is one line in `main.kt`:
`tasksCompute(16, workers)` makes a compute queue of workers and
`tasksEnqueue(handleTask(conn, *app))` hands each connection to it - the same shape
`examples/http/async-server` uses, and the JSON/router layers above are untouched by it.

## Routes

| request | response |
| --- | --- |
| `GET /health` | `200`, `{"status":"ok"}` |
| `GET /json` | `200`, `{"message":"Hello, World!"}` |
| `POST /greet` with `{"name":"Ada"}` | `200`, `{"greeting":"Hello, Ada!"}` |
| `POST /echo` with any JSON | `200`, `{"received":<body>,"kind":"object"}` |
| `GET /fetch` | `200`, the one upstream URL decoded and re-shaped |
| anything else | `404`, `{"error":"no route for ..."}` |

## Build and run

From the repository root (the compiler must be built first: `build.bat --release`):

```bat
simse.exe --root examples/express/src ^
    --module examples/http/sockets --module src/modules/http --module src/modules/jsonnode ^
    -o examples/express/out.cpp
bun build.js --release --cpp examples/express/out.cpp --exe examples/express/express.exe

:: foreground; the request limit makes it leave on its own after that many connections
examples\express\express.exe 8095 8

:: in another terminal:
curl -X POST -d "{\"name\":\"Ada\"}" http://127.0.0.1:8095/greet
curl -X POST -d "{\"a\":1,\"b\":[true,null]}" http://127.0.0.1:8095/echo
curl http://127.0.0.1:8095/health
curl http://127.0.0.1:8095/fetch
```

`express.exe [port] [requestLimit] [workers]` defaults to port `8095`, no limit, and `16`
workers. The limit is what lets a script run it without leaving a process behind; the worker
count is the concurrency (see below).

## Benchmark

`bench/` compares the example's `GET /json` against Bun's built-in HTTP server (`Bun.serve`),
both driven by the same `fetch` worker pool. The Simse server is given as many workers as the
client has connections, so the comparison is at equal concurrency:

```bat
bun examples/express/bench/run.mjs 16 30000 1000     :: concurrency, requests, warmup
```

Release build, loopback, Windows on ARM64 (18 logical cores):

| connections | this example | `Bun.serve` | `Bun.serve` + `Connection: close` |
| --- | --- | --- | --- |
| 1 | ~20,700 req/s | ~18,800 req/s | ~6,000 req/s |
| 4 | ~93,000 req/s | ~64,200 req/s | ~11,600 req/s |
| 8 | ~121,100 req/s | ~80,200 req/s | ~11,600 req/s |
| 16 | ~127,300 req/s | ~92,000 req/s | ~11,700 req/s |
| 32 | ~126,400 req/s | ~111,300 req/s | ~11,900 req/s |

The example leads `Bun.serve` at every concurrency and saturates around **127k req/s** at 16
workers - the 18-core machine. It is the same lesson `examples/http/async-server` measured
(~146k req/s against ASP.NET), and it comes from two things that are cheap in this language:
keep-alive (a worker holds its connection, so the per-request cost is the handler, not a TCP
handshake) and one compute queue of workers (`tasksCompute`, one line).

The last column is the control: force `Connection: close` and `Bun.serve` falls to the
connection-setup cost (~12k) exactly as the example would - the ~10x there was never request
handling.

Two design notes the numbers make concrete:

- **The worker count is the ceiling.** With keep-alive a blocking `netReceive` holds its worker
  for the whole connection, so a 16-worker server serving 32 connections leaves 16 of them in the
  queue - and a client waiting on them sees stalled or reset sockets. Match workers to expected
  concurrent connections (here, one per connection; the machine has 18 cores). Non-blocking I/O
  (IOCP), not more workers, is what would lift the per-core ceiling.
- **`Bun.serve` scales past the worker count** because it is event-driven: at 32 connections it
  reaches ~111k on the same machine. This example trades that for straight-line handler code and
  a tiny transport.

## What to look at

- `src/router.kt` - the whole express-like idea is a `List<Route>` of `(method, path, handler)`
  and a first-match loop. A handler is an ordinary callable value (a named function or a lambda),
  which is why a route table is just a list and dispatch is a call - no vtable, no registration
  magic.
- `src/main.kt` - the transport: one `tasksCompute(16, workers)` call and one `tasksEnqueue`
  per accepted connection, then `tasksDrain()` when the accept loop ends.
- `src/http.kt` - `parseRequest` is the only place a raw byte becomes a `Request`, and
  `formatResponse` the only place a `Response` becomes bytes.
- `src/modules/jsonnode/jsonnode.kt` - `JsonNode` is a `union class` (one shape live at a time,
  `None` is JSON `null`), `parseJson` is the reader, `JsonNode.toJson()` the writer. The round
  trip is `{"a":1}` -> `JsonNode` -> `{"a":1}`, and it is what makes a handler shape-agnostic.

## Caveats

- Windows only (the `sockets` and `http` modules are Winsock2/WinINet).
- The request is one `recv`; a body split across reads, or pipelined requests on a kept-alive
  connection, are out of scope (the `examples/http/server` caveat, kept).
- `GET /fetch` reaches one constant URL (`upstreamUrl` in `handlers.kt`). Reaching a
  caller-supplied URL is the next step, not this one; a failure to reach or parse the upstream is
  a `502` so the failure mode is visible.
- The `jsonnode` reader is a subset: standard escapes and `\uXXXX` up to ASCII; no surrogate
  pairs, no UTF-8 translation.
- Build outputs (`out.cpp`, `express.exe`) are git-ignored.
