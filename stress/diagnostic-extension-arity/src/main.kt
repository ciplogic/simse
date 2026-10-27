package fixtures

// An arity mismatch on an extension is a diagnostic at the call, not a silent
// mis-resolution onto a same-named neighbour (limitations.md's `whereBind` shape): the
// call spelling `whereBind` with three arguments must not resolve `whereBind2`'s
// signature while emitting `whereBind`'s name.

data class Query(var sql: Str)

fun Query.whereBind(cond: Str, value: Str): Query {
    return this
}

fun Query.whereBind2(cond: Str, a: Str, b: Str): Query {
    return this
}

fun main(): Int {
    val q: Query = Query("select")
    val r: Query = q.whereBind("x = ?", "1", "2")
    return 0
}
