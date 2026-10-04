# An express-like JSON server

A small server where a handler is `Request -> Response`, the request body is decoded from JSON
into an intermediate `JsonNode`, the handler transforms it, and the response is encoded back. It
is the shape `impl_specs/user-language-roadmap.md` calls "the request->handler->response flow
feels like Node", built from parts the language already has: the `sockets` module for the
transport, the `http` module for the outbound request, and a `JsonNode` tagged union for the
payload.

## The layers

```
              bytes                    Request/Response            JsonNode
  client ───────────────►  http.kt  ───────────────────►  router.kt  ─────────►  handlers.kt
         ◄───────────────            ◄───────────────────            ◄─────────
              bytes                    Response                     JsonNode
```

| file | what it owns |
| --- | --- |
| `src/main.kt` | the composition root: wire the routes into the `App`, run the blocking accept loop |
| `src/router.kt` | `Handler = Request -> Response`, `Route`, `App` (`get`/`post`/`route`) |
| `src/http.kt` | `Request`/`Response`, `parseRequest`/`formatResponse`, and the JSON boundary (`Request.json()`, `jsonResponse`) |
| `src/handlers.kt` | the four route handlers - the actual transformations |
| `src/modules/jsonnode` | the intermediate `JsonNode` union and its reader (`parseJson`) and writer (`JsonNode.toJson()`) |

A handler sees only `Request` and `Response`. It reaches into the payload with `get`/`at`/`text`/
`number`/`boolean`, builds a new node with `jsonObject`/`jsonArray`/`jsonText`/`jsonNumber`, and
never touches a socket, a header, or a byte. Swapping the transport (the async scheduler,
`examples/http/async-server`) or the payload (a typed `data class`) changes one layer and not the
others.

## Routes

| request | response |
| --- | --- |
| `GET /health` | `200`, `{"status":"ok"}` |
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

`express.exe [port] [requestLimit]` defaults to port `8095` and no limit; the limit is what lets
a script run it without leaving a process behind.

## Benchmark

`bench/` compares the example's `GET /json` against Bun's built-in HTTP server (`Bun.serve`),
both driven by the same `fetch` worker pool:

```bat
bun examples/express/bench/run.mjs 32 10000 500
```

Release build, loopback, Windows on ARM64:

| server | concurrency 32 | concurrency 1 |
| --- | --- | --- |
| this example (`Connection: close`) | ~12,200 req/s | ~6,200 req/s |
| `Bun.serve` (keep-alive) | ~118,000 req/s | ~17,000 req/s |
| `Bun.serve` forced `Connection: close` | ~12,700 req/s | ~6,000 req/s |

The third row is the point: **when both pay a fresh connection per request, the two are within a
few percent.** The ~10x is not framework overhead - it is connection reuse plus async I/O. This
example closes every connection (one request per socket, the simplest correct model), so it pays
the full connect/close per request; `Bun.serve` keeps the connection alive and multiplexes. That
is the lesson `examples/http/async-server` already measured - with keep-alive and four task
queues it reaches ~146k req/s - and only the transport layer changes.

## What to look at

- `src/router.kt` - the whole express-like idea is a `List<Route>` of `(method, path, handler)`
  and a first-match loop. A handler is an ordinary callable value (a named function or a lambda),
  which is why a route table is just a list and dispatch is a call - no vtable, no registration
  magic.
- `src/http.kt` - `parseRequest` is the only place a raw byte becomes a `Request`, and
  `formatResponse` the only place a `Response` becomes bytes.
- `src/modules/jsonnode/jsonnode.kt` - `JsonNode` is a `union class` (one shape live at a time,
  `None` is JSON `null`), `parseJson` is the reader, `JsonNode.toJson()` the writer. The round
  trip is `{"a":1}` -> `JsonNode` -> `{"a":1}`, and it is what makes a handler shape-agnostic.

## Caveats

- Windows only (the `sockets` and `http` modules are Winsock2/WinINet).
- Single-threaded and blocking: one connection at a time, one `recv` per request. That is the
  design (and why the file is small), not an oversight; `examples/http/async-server` is the same
  routes on the task scheduler, and only `main.kt` differs.
- `GET /fetch` reaches one constant URL (`upstreamUrl` in `handlers.kt`). Reaching a
  caller-supplied URL is the next step, not this one; a failure to reach or parse the upstream is
  a `502` so the failure mode is visible.
- The `jsonnode` reader is a subset: standard escapes and `\uXXXX` up to ASCII; no surrogate
  pairs, no UTF-8 translation.
- Build outputs (`out.cpp`, `express.exe`) are git-ignored.
