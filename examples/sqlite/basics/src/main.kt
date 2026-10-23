package basics

// The sqlite module's basics: open a database (here the in-memory one, so the example
// always starts empty), create a schema, insert with bound parameters, read rows back
// through a lambda, and watch an error come back as a value.
//
// Bound parameters are the point of the INSERTs: the values travel beside the SQL and
// never into it, which is what makes user input safe - the `?`s are the whole story.

import sqlite

fun main(): Int {
    println("engine: sqlite " + version())

    val opened: Res<RawPtr> = open(":memory:")
    if (!opened.isOk()) {
        println("open failed: " + opened.error())
        return 1
    }
    val db: RawPtr = opened.value()

    // A script through `exec`: any number of statements, no results.
    val schema: Res<Int> = exec(db, "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, year INTEGER, price REAL)")
    if (!schema.isOk()) {
        println("schema failed: " + schema.error())
        return 1
    }

    // Inserts with the values bound at their `?`s, in order.
    val titles: List<Str> = listOf<Str>("The Pragmatic Programmer", "Structure and Interpretation", "The Art of Naming", "Refactorer's Companion")
    val years: List<Int64> = listOf<Int64>(1999, 1985, 2018, 2021)
    val prices: List<Float64> = listOf<Float64>(39.95, 54.5, 24.0, 42.0)
    var i: Int = 0
    while (i < titles.size()) {
        val title: Str = titles[i]
        val year: Int64 = years[i]
        val price: Float64 = prices[i]
        val written: Res<Int> = execBind(db, "INSERT INTO books (title, year, price) VALUES (?, ?, ?)",
            listOf<SqlValue>(SqlValue(title), SqlValue(year), SqlValue(price)))
        if (!written.isOk()) {
            println("insert failed: " + written.error())
            return 1
        }
        i++
    }

    // A transaction through `exec`: two statements, one BEGIN/COMMIT pair.
    val lateTitle: Str = "Committed Late"
    val lateYear: Int64 = 2024
    val latePrice: Float64 = 10.0
    exec(db, "BEGIN")
    execBind(db, "INSERT INTO books (title, year, price) VALUES (?, ?, ?)",
        listOf<SqlValue>(SqlValue(lateTitle), SqlValue(lateYear), SqlValue(latePrice)))
    exec(db, "COMMIT")

    // Reads through a lambda: one call per row, the row borrowed, nothing copied. The
    // lambda body is the loop body a `for` would carry.
    val listed: Res<Int> = query(db, "SELECT id, title, year, price FROM books ORDER BY id", List<SqlValue>(), (r: *Row) -> {
        println("  #" + r.getInt(0).toString() + " " + r.getStr(1) + " (" + r.getInt(2).toString() + ") " + r.getFloat(3).toString())
    })
    if (listed.isOk()) {
        println("listed " + listed.value().toString() + " books")
    } else {
        println("listing failed: " + listed.error())
    }

    // An aggregate: the single-row reads a scalar query answers.
    query(db, "SELECT COUNT(*), AVG(price) FROM books", List<SqlValue>(), (r: *Row) -> {
        println("count: " + r.getInt(0).toString() + ", average price: " + r.getFloat(1).toString())
    })

    // The error path: a typo is a `Res` value, not a crash - the message is SQLite's own.
    val broken: Res<Int> = query(db, "SELECT * FROM bookshelf", List<SqlValue>(), (r: *Row) -> {
        println("never reached")
    })
    if (!broken.isOk()) {
        println("the broken query answered: " + broken.error())
    }

    close(db)
    return 0
}
