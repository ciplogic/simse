// bun-server.mjs - the baseline the express example is measured against.
//
// Bun's built-in HTTP server (`Bun.serve`), the framework Bun ships: the same
// `{"message":"Hello, World!"}` on `/json`, serialized per request so it does the same work a
// JSON route does rather than serving one precomputed string.
//
//   bun examples/express/bench/bun-server.mjs [port]     (default 8098)

const port = Number(process.argv[2] ?? 8098);

Bun.serve({
  port,
  fetch(req) {
    const path = new URL(req.url).pathname;
    if (path === "/json" || path === "/health") {
      return Response.json({ message: "Hello, World!" });
    }
    return Response.json({ error: "not found" }, { status: 404 });
  },
});

console.log(`bun-server: listening on http://127.0.0.1:${port}`);
