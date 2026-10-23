package linqdemo

// The sqlite rows and the linq operators in one pipeline: `queryRows` materializes the
// result set into `RowValue`s - values that outlive the statement - and the `linq`
// module's `where`/`select`/`take` walk them in place. One import each; the machine
// `List.iter` hands out is what the operators consume.
//
// The division of labor: SQL narrows the set (the WHERE the engine indexes), linq shapes
// what the program does with the rows it got - the split a report lives with.

import sqlite
import linq

fun main(): Int {
    val opened: Res<RawPtr> = open(":memory:")
    if (!opened.isOk()) {
        println("open failed: " + opened.error())
        return 1
    }
    val db: RawPtr = opened.value()

    exec(db, "CREATE TABLE books (id INTEGER PRIMARY KEY, title TEXT, year INTEGER, price REAL)")

    val seedTitles: List<Str> = listOf<Str>("Old Man and the Sea", "The Little Engine", "Modern Compilers", "Smalltalk Best Practices", "The Long View", "Patterns of Software")
    val seedYears: List<Int64> = listOf<Int64>(1952, 1993, 2011, 1996, 2024, 1996)
    val seedPrices: List<Float64> = listOf<Float64>(12.0, 8.5, 65.0, 25.0, 30.0, 19.0)
    var i: Int = 0
    while (i < seedTitles.size()) {
        val title: Str = seedTitles[i]
        val year: Int64 = seedYears[i]
        val price: Float64 = seedPrices[i]
        insertCols(db, "books", listOf<Str>("title", "year", "price"),
            listOf<SqlValue>(SqlValue(title), SqlValue(year), SqlValue(price)))
        i++
    }

    // SQL narrows; linq shapes: the rows under a bound filter, then the titles mapped
    // out of them lazily.
    val floor: Float64 = 10.0
    val priced: Res<List<RowValue>> = select2("books", "title", "price").whereBind("price >= ?", SqlValue(floor)).rows(db)
    if (!priced.isOk()) {
        println("rows failed: " + priced.error())
        return 1
    }
    val pricedRows: List<RowValue> = priced.value()
    val titles: List<Str> = pricedRows.iter().where((r: *RowValue) -> r.float64("price") < Float64(50.0)).select((r: *RowValue) -> r.text("title")).toList()
    println("priced 10..50:")
    var names: Str = Str()
    for (t in titles) {
        names.appendStr("  " + t + "\n")
    }
    print(names)

    // The rows a program re-reads: materialize once, walk twice - the second walk costs
    // no query.
    val all: Res<List<RowValue>> = select2("books", "title", "year").rows(db)
    if (!all.isOk()) {
        println("rows failed: " + all.error())
        return 1
    }
    val rows: List<RowValue> = all.value()

    val modern: List<Str> = rows.iter().where((r: *RowValue) -> r.int64("year") >= Int64(1996)).select((r: *RowValue) -> r.text("title")).toList()
    println("from 1996 on: " + modern.size().toString() + " titles")

    // A scalar fold the engine could have done (SELECT SUM(year)). A lambda captures by
    // value, so an accumulator written inside `forEach` stays inside - the honest fold
    // over materialized rows is a plain loop.
    var total: Int64 = 0
    for (r in rows) {
        total = total + r.int64("year")
    }
    println("sum of years: " + total.toString())

    // take: the first two rows the filter admits.
    val couple: List<Str> = rows.iter().where((r: *RowValue) -> r.int64("year") > Int64(1990)).select((r: *RowValue) -> r.text("title")).take(2).toList()
    println("first two after 1990: " + couple.size().toString())

    close(db)
    return 0
}
