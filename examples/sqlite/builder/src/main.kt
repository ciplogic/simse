package builder

// The query builder: a SELECT spelled as a chain, with the bound values beside its
// conditions, and the INSERT half (`insert`/`insertCols`) spelled like the reads.
//
// A `Query` is a mutable scratch value - the methods edit it and answer it - so build
// one per query, read the rows out, let it die. `Query.build()` hands the SQL text and
// the parameter list apart, which is also how a caller logs what ran.

import sqlite

fun main(): Int {
    val opened: Res<RawPtr> = open(":memory:")
    if (!opened.isOk()) {
        println("open failed: " + opened.error())
        return 1
    }
    val db: RawPtr = opened.value()

    exec(db, "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, year INTEGER, price REAL)")

    val seedTitles: List<Str> = listOf<Str>("Bound Values", "Chained Selects", "Where Clauses", "Order By", "Cheap Trick")
    val seedYears: List<Int64> = listOf<Int64>(2001, 2004, 2010, 2015, 2020)
    val seedPrices: List<Float64> = listOf<Float64>(30.0, 45.0, 28.0, 52.0, 9.5)
    var i: Int = 0
    while (i < seedTitles.size()) {
        val title: Str = seedTitles[i]
        val year: Int64 = seedYears[i]
        val price: Float64 = seedPrices[i]
        insertCols(db, "books", listOf<Str>("title", "year", "price"),
            listOf<SqlValue>(SqlValue(title), SqlValue(year), SqlValue(price)))
        i++
    }
    println("seeded " + select("books").count(db).value().toString() + " books")

    // The builder at its plainest: a filter the user could have typed, bound - not
    // interpolated - and the rows read by column name.
    val floor: Float64 = 29.0
    val pricier: Res<List<RowValue>> = select2("books", "title", "price").whereBind("price > ?", SqlValue(floor)).orderBy("price").rows(db)
    println("books over " + floor.toString() + ":")
    if (pricier.isOk()) {
        val rows: List<RowValue> = pricier.value()
        for (row in rows) {
            println("  " + row.text("title") + " at " + row.float64("price").toString())
        }
    }

    // Two bound conditions ANDed; the parameters land at their `?`s in order.
    val lo: Int64 = 2000
    val hi: Int64 = 2016
    val modern: Res<List<RowValue>> = select2("books", "title", "year").whereBind2("year >= ? AND year <= ?", SqlValue(lo), SqlValue(hi)).rows(db)
    println("between 2000 and 2016: " + modern.value().size().toString())

    // A literal condition - nothing to bind - and the SQL text apart from the values,
    // the shape a log line wants.
    val text: Res<SqlText> = select("books").where("price IS NOT NULL").orderBy("title").build()
    if (text.isOk()) {
        println("built: " + text.value().sql)
    }

    // The write half: one UPDATE with a bound value, the changed count answered.
    val renamed: Str = seedTitles[4]
    val newPrice: Float64 = 33.0
    val updated: Res<Int> = execBind(db, "UPDATE books SET price = ? WHERE title = ?",
        listOf<SqlValue>(SqlValue(newPrice), SqlValue(renamed)))
    println("(renamed " + renamed + ")")
    println("updated " + updated.value().toString() + " row(s)")

    // And the delete that undoes the seed's worst idea.
    val cheap: Float64 = 10.0
    val gone: Res<Int> = execBind(db, "DELETE FROM books WHERE price < ?", listOf<SqlValue>(SqlValue(cheap)))
    println("deleted " + gone.value().toString() + " row(s)")

    close(db)
    return 0
}
