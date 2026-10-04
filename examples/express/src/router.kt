package express

// The express-like router: a `Handler` is `Request -> Response`, a `Route` pairs a method and
// an exact path with one, and `App` is the table. `get`/`post` register a handler (a named
// function or a lambda) and `route` dispatches the first match.
//
// It is deliberately the smallest useful shape - an exact method and path, no middleware chain,
// no path parameters, first-match wins. A handler is an ordinary callable value, so a route
// table is a `List<Route>` and dispatch is a call; that is what keeps the model one screen and
// lets the same handlers serve the JSON routes in `handlers.kt`.

typealias Handler = (Request) -> Response

data class Route(var method: Str, var path: Str, var handler: Handler)

data class App(var routes: List<Route>) {
    fun get(path: Str, handler: Handler): App {
        this.routes.append(Route("GET", path, handler))
        return this
    }

    fun post(path: Str, handler: Handler): App {
        this.routes.append(Route("POST", path, handler))
        return this
    }

    // The first route whose method and path match the request, or a 404.
    fun route(req: Request): Response {
        var i: Int = 0
        while (i < this.routes.size()) {
            val r: Route = this.routes[i]
            if (r.method == req.method && r.path == req.path) {
                return r.handler(req)
            }
            i = i + 1
        }
        return jsonError(404, "no route for " + req.method + " " + req.path)
    }
}
