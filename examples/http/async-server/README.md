# The async server, and how it compares

`../server` is one thread and one blocking loop. This is the same routes on the task scheduler
(`impl_specs/async.md`): a suspending `main` is the root task, and each accepted connection is
handed to a compute queue with `tasksSpawn(handleTask(conn))` - `handleTask` is the factory the
compiler declares for the suspending `handle` - and the root steps aside, so the handle runs on a
queue's thread while the root goes back to `accept`. Four compute queues (`tasksCompute(16, 1)` ...
`(19, 1)`) mean four requests in flight. The blocking `netReceive`/`netSend` are ordinary code
inside `handle`; the task is *suspended* while it waits, which is the whole point.

```simse
suspend fun handle(conn: Int64): Unit {
    ...
    val body: Str = bodyFor(path)          // a suspension: the dispatch is a task of its own
    respond(conn, statusFor(path), contentTypeFor(path), body, keepAlive)
}

suspend fun main(): Int {                   // the root task
    tasksCompute(16, 1) ... tasksCompute(19, 1)
    while (served < limit) {
        val conn: Int64 = netAccept(server)
        if (conn != 0 - 1) {
            tasksSpawn(handleTask(conn))     // enqueue the handle, yield the root
        }
        served = served + 1
    }
    ...
}
```

Unlike `../server`, this one **keeps a connection alive**: a request asking for
`Connection: close` (our own client does) gets one request per connection, and anything else
reuses it. That is not a nicety - a benchmarking tool that opened a fresh connection per request
would exhaust the client's ephemeral ports within seconds (measured: the run collapses after the
first burst, `WSAEADDRINUSE`). Keep-alive is what makes the handler, rather than TCP setup, the
thing being measured.

## Build and run

From the repository root (the compiler must be built first: `build.bat --release`):

```bat
simse.exe --root examples/http/async-server/src --module examples/http/sockets -o examples/http/async-server/out.cpp
bun build.js --release --cpp examples/http/async-server/out.cpp --exe examples/http/async-server/httpd_async.exe

:: foreground; the limit makes it leave on its own once this client has been served
docs\examples\http\async-server\httpd_async.exe
docs\examples\http\client\httpclient.exe 8099 /json 2000
```

The port and the request limit are constants in `src/main.kt` (`8099`, `2000`) because a suspending
`main(args)` is not lowered yet. The limit counts *connections*; with keep-alive a benchmark opens
four, so a benchmarked server runs until it is stopped (the scripts below kill it).

## Routes

| request | response |
| --- | --- |
| `GET /` or `GET /hello` | `200`, `text/plain`, `Hello, world!` |
| `GET /json` | `200`, `application/json`, `{"name":"Hello world"}` |
| anything else | `404`, `text/plain`, `not found` |

## The benchmark

`bombardier` is a small cross-platform HTTP benchmarking tool with a native `windows-arm64` build:

```bat
curl -L -o build\bombardier.exe https://github.com/codesenberg/bombardier/releases/download/v2.0.2/bombardier-windows-arm64.exe
build\bombardier.exe -c 4 -d 10s -l http://127.0.0.1:8099/json
```

`-c` is concurrent connections, `-d` the duration; keep-alive is on by default (do **not** pass
`-a`, which would reintroduce the port exhaustion above).

For the comparison, the same problem was written as a **minimal ASP.NET Core** server, in the same
shape: one route, no HTTPS, no logging, no `Server:` header, Kestrel limits off, and an `async`
handler (so it is scheduled on tasks too). It answers the identical 22-byte body on port 8098.

| | **Simse** (this server, 4 queues) | **C# ASP.NET** (JIT) | **C# ASP.NET** (AOT) |
| --- | --- | --- | --- |
| concurrency 1 | **45,900** | 36,770 | 33,420 |
| concurrency 2 | **89,300** | 76,394 | 72,680 |
| **concurrency 4** | **146,100** | 107,876 | 106,521 |
| concurrency 16 | 141,600 | 236,905 | **243,315** |
| working set at c=4 | **~7.4 MB** (flat) | ~48-55 MB | ~24 MB (flat) |

req/s, `GET /json`, 5 s runs, loopback, release builds, 0 errors on every line; the working set is
`tasklist` sampled through a 12 s run at c=4 (Simse 7,420 K throughout; AOT 23,984 K; JIT 47,876 -
55,164 K). A 12 s run at c=4 agrees: Simse 149,714, JIT 105,969, AOT 107,309.

**At the same concurrency this server is ~40% faster and uses a third to a sixth of the memory.**
The shape is what says why: Simse climbs 1 -> 2 -> 4 and then goes flat, because its four queue
threads are saturated; .NET keeps climbing past 4 because the thread pool spreads the work over
every core - so its c=16 figure is a *thread* budget, not a cheaper request. Compare like for like:
four Simse threads hold ~146 K, and reaching ~240 K costs .NET roughly the whole machine.

The per-request work is small on both sides (a read, a formatted 172-byte response, a write), which
is why the difference is visible at all: Simse pays no framework pipeline and no per-request
allocation, and its four threads run the handler as ordinary straight-line code.

## What to look at

- `src/main.kt` - the whole server: `handle` (one connection, a `while` on keep-alive, a
  suspension on `bodyFor`), `main` (the root task's accept loop), and the four route functions.
  `bodyFor` calls a `suspend` route, so a request crosses two task boundaries before a byte is
  written - the scheduler's cost is in the numbers above, not hidden.
- `impl_specs/async.md` - the queue model, `tasksSpawn`, the `live` count and the exit rule.
- The C# side is deliberately local, in `build/csharp-http/` (git-ignored). It is reproducible from
  these files:

  ```csharp
  // build/csharp-http/Program.cs  (dotnet publish -c Release -o pub)
  const string JsonBody = "{\"name\":\"Hello world\"}";
  var builder = WebApplication.CreateSlimBuilder(args);
  builder.Logging.ClearProviders();
  builder.WebHost.ConfigureKestrel(k => { k.AddServerHeader = false; k.Limits.MaxConcurrentConnections = null; });
  var app = builder.Build();
  app.MapGet("/json", static async (HttpContext ctx) => {
      ctx.Response.ContentType = "application/json";
      ctx.Response.ContentLength = JsonBody.Length;
      await ctx.Response.WriteAsync(JsonBody);
  });
  app.Run("http://127.0.0.1:8098");
  ```

  ```bat
  :: JIT
  dotnet publish -c Release -o pub
  :: native AOT
  dotnet publish -c Release -r win-arm64 -p:PublishAot=true -p:StripSymbols=true -o pub-aot
  ```

## Caveats

- One machine, Windows on ARM64, loopback: both servers and both C# builds are native to it. These
  are round numbers from a few runs, not a controlled study - a few percent of run-to-run spread,
  more on the 5 s runs than the 12 s ones.
- The C# figures are JIT-warmed with a 2 s run before measuring; AOT starts faster but is warmed the
  same way. Simse is native ahead-of-time from the first byte.
- Kestrel adds a `Date` header the Simse server does not; the Simse response carries
  `Content-Type`, `Content-Length` and `Connection`.
- The Simse numbers are with **four** `tasksCompute` queues. More queues would raise its c=16 figure
  - the ceiling is the queue count, not the design - and the root's `accept` (and one `tasksSpawn`
  task per request) is the last serialization.
- Request parsing stays minimal: one `recv` into the handler's own 2 KB heap buffer, the first line
  is all that is routed. A request split across reads, or headers that do not fit, is out of scope
  (the sync server's caveat, kept).
- Build outputs (`out.cpp`, the `.exe`s, `bombardier.exe`, `csharp-http/`) are git-ignored.
