package sqlite

// builder.kt - the query builder: a SELECT spelled as a chain, bound values as
// `SqlValue`s beside its conditions.
//
// ```kt
// val rows: Res<List<RowValue>> = select("users", "id", "name")
//     .whereBind("age > ?", SqlValue(30))
//     .whereBind("name LIKE ?", SqlValue("A%"))
//     .orderBy("name")
//     .limit(10)
//     .rows(db)
// ```
//
// A `Query` is a mutable scratch value: the methods edit it and answer it, so a chain
// mutates the value the chain starts from. Build one per query and let it die - the
// shape is for reading, not for keeping.

data class Query(
    var table: Str,
    var cols: List<Str>,
    var conds: List<Str>,
    var params: List<SqlValue>,
    var order: Str,
    var limitCount: Int,
    var offsetCount: Int
)

// `select("users")` - every column; `select2("users", "id", "name")` - the columns named.
// The arities are separate names, not overloads: an overloaded `select` made the emitter
// call its own chained methods wrongly (see limitations.md).
fun select(table: Str): Query {
    return Query(table, List<Str>(), List<Str>(), List<SqlValue>(), "", 0, 0)
}

fun select1(table: Str, c1: Str): Query {
    var cols: List<Str> = List<Str>()
    cols.append(c1)
    return Query(table, cols, List<Str>(), List<SqlValue>(), "", 0, 0)
}

fun select2(table: Str, c1: Str, c2: Str): Query {
    var cols: List<Str> = List<Str>()
    cols.append(c1)
    cols.append(c2)
    return Query(table, cols, List<Str>(), List<SqlValue>(), "", 0, 0)
}

fun select3(table: Str, c1: Str, c2: Str, c3: Str): Query {
    var cols: List<Str> = List<Str>()
    cols.append(c1)
    cols.append(c2)
    cols.append(c3)
    return Query(table, cols, List<Str>(), List<SqlValue>(), "", 0, 0)
}

// One condition, no parameters: a literal comparison the builder cannot inject into.
fun Query.where(cond: Str): Query {
    this.conds.append(cond)
    return this
}

fun Query.whereBind2(cond: Str, a: SqlValue, b: SqlValue): Query {
    this.conds.append(cond)
    this.params.append(a)
    this.params.append(b)
    return this
}


// A condition whose `?` takes one bound value - the form user input belongs in.
fun Query.whereBind(cond: Str, value: SqlValue): Query {
    this.conds.append(cond)
    this.params.append(value)
    return this
}

fun Query.orderBy(col: Str): Query {
    this.order = col
    return this
}

fun Query.limit(count: Int): Query {
    this.limitCount = count
    return this
}

fun Query.offset(count: Int): Query {
    this.offsetCount = count
    return this
}

// The SQL text and the values to bind at the `?`s, in order. Public so a caller can log
// the statement or run it through `execBind` itself.
fun Query.build(): Res<SqlText> {
    if (this.table.size() == 0) {
        return Res<SqlText>.err("sqlite: the query has no table")
    }
    var sql: Str = "SELECT "
    if (this.cols.size() == 0) {
        sql = sql + "*"
    } else {
        var i: Int = 0
        while (i < this.cols.size()) {
            if (i > 0) {
                sql = sql + ", "
            }
            sql = sql + this.cols[i]
            i++
        }
    }
    sql = sql + " FROM " + this.table
    if (this.conds.size() > 0) {
        sql = sql + " WHERE "
        var i: Int = 0
        while (i < this.conds.size()) {
            if (i > 0) {
                sql = sql + " AND "
            }
            sql = sql + this.conds[i]
            i++
        }
    }
    if (this.order.size() > 0) {
        sql = sql + " ORDER BY " + this.order
    }
    if (this.limitCount > 0) {
        sql = sql + " LIMIT " + this.limitCount.toString()
    }
    if (this.offsetCount > 0) {
        sql = sql + " OFFSET " + this.offsetCount.toString()
    }
    return Res<SqlText>.ok(SqlText(sql, this.params))
}

// The text of a built query with the values its `?`s take, in order.
data class SqlText(var sql: Str, var params: List<SqlValue>)

// Builds and runs: the materialized rows.
fun Query.rows(db: RawPtr): Res<List<RowValue>> {
    val built: Res<SqlText> = this.build()
    if (!built.isOk()) {
        return Res<List<RowValue>>.err(built.error())
    }
    return queryRows(db, built.value().sql, built.value().params)
}

// Builds and runs without keeping rows: the count a report usually prints anyway.
fun Query.count(db: RawPtr): Res<Int> {
    val built: Res<SqlText> = this.build()
    if (!built.isOk()) {
        return Res<Int>.err(built.error())
    }
    val rows: Res<List<RowValue>> = queryRows(db, built.value().sql, built.value().params)
    if (!rows.isOk()) {
        return Res<Int>.err(rows.error())
    }
    return Res<Int>.ok(rows.value().size())
}

// An INSERT naming its columns: `INSERT INTO t (a, b) VALUES (?, ?)`, one `?` per
// value, the form that survives a column being added later.
fun insertCols(db: RawPtr, table: Str, cols: List<Str>, values: List<SqlValue>): Res<Int> {
    if (values.size() == 0) {
        return Res<Int>.err("sqlite: an insert needs values")
    }
    if (cols.size() != values.size()) {
        return Res<Int>.err("sqlite: an insert needs one column per value")
    }
    var sql: Str = "INSERT INTO " + table + " ("
    var i: Int = 0
    while (i < cols.size()) {
        if (i > 0) {
            sql = sql + ", "
        }
        sql = sql + cols[i]
        i++
    }
    sql = sql + ") VALUES ("
    i = 0
    while (i < values.size()) {
        if (i > 0) {
            sql = sql + ", "
        }
        sql = sql + "?"
        i++
    }
    sql = sql + ")"
    return execBind(db, sql, values)
}

// An INSERT with one `?` per value: the write half, spelled like the reads.
fun insert(db: RawPtr, table: Str, values: List<SqlValue>): Res<Int> {
    if (values.size() == 0) {
        return Res<Int>.err("sqlite: an insert needs values")
    }
    var sql: Str = "INSERT INTO " + table + " VALUES ("
    var i: Int = 0
    while (i < values.size()) {
        if (i > 0) {
            sql = sql + ", "
        }
        sql = sql + "?"
        i++
    }
    sql = sql + ")"
    return execBind(db, sql, values)
}
