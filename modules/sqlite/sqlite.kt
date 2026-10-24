package sqlite

// The friendly layer over the raw bindings (api.kt): every fallible operation is a
// `Res`, binds travel as `SqlValue`, rows reach a lambda as a borrowed `Row` or come
// back materialized as `RowValue` lists the `linq` module can pipeline over. The query
// builder is `builder.kt`, a layer of its own. The module carries no C++ at all: the
// `native` declarations in api.kt are the whole binding, and `rawPtr` spells the one
// pointer-sized constant the C API asks for (SQLITE_TRANSIENT).

// ---- the constants a caller spells -------------------------------------------

val SQLITE_OK: Int = 0
val SQLITE_ERROR: Int = 1
val SQLITE_ROW: Int = 100
val SQLITE_DONE: Int = 101

// sqlite3_column_type answers.
val SQLITE_INTEGER: Int = 1
val SQLITE_FLOAT: Int = 2
val SQLITE_TEXT: Int = 3
val SQLITE_BLOB: Int = 4
val SQLITE_NULL: Int = 5

// The destructor argument sqlite3_bind_text takes: SQLITE_TRANSIENT, `((void*)-1)` -
// the constant only `rawPtr` can spell. The statement copies the text, so the caller's
// `Str` may die first.
val SQLITE_TRANSIENT_ADDR: Int64 = -1

fun SQLITE_TRANSIENT(): RawPtr {
    return rawPtr(SQLITE_TRANSIENT_ADDR)
}

// sqlite3_open_v2 flags.
val SQLITE_OPEN_READONLY: Int = 1
val SQLITE_OPEN_READWRITE: Int = 2
val SQLITE_OPEN_CREATE: Int = 4
val SQLITE_OPEN_URI: Int = 64

// ---- the database ---------------------------------------------------------------

// Opens (creating if needed) and answers the handle, or the failure's message.
fun open(path: Str): Res<RawPtr> {
    val flags: Int = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE
    val opened: Res<RawPtr> = openFlags(path, flags)
    return opened
}

fun openFlags(path: Str, flags: Int): Res<RawPtr> {
    val opened: OpenedDb = OpenedDb(null)
    val rc: Int = sqlite3Open(path, *opened, flags, null)
    val db: RawPtr = opened.db
    if (rc != SQLITE_OK) {
        val message: Str = "sqlite: open failed (code " + rc.toString() + ")"
        if (db != null) {
            sqlite3Close(db)
            return Res<RawPtr>.err(message + ": " + sqlite3ErrMsg(db))
        }
        // OK-but-null is the loaded-nothing case: the thunk answered its default.
        return Res<RawPtr>.err(message + ": the sqlite3 library could not be loaded")
    }
    if (db == null) {
        return Res<RawPtr>.err("sqlite: the sqlite3 library could not be loaded")
    }
    return Res<RawPtr>.ok(db)
}

// Runs the script (any number of statements) and answers the rows the last of them
// touched. Schema setup, PRAGMAs and transactions go through here.
fun exec(db: RawPtr, sql: Str): Res<Int> {
    val rc: Int = sqlite3Exec(db, sql, null, null, null)
    if (rc != SQLITE_OK) {
        return Res<Int>.err(lastError(db))
    }
    return Res<Int>.ok(sqlite3Changes(db))
}

// The polite close: `sqlite3_close_v2` defers past the statements that are still in
// use, so a close failure has no better audience than a log - and reports none.
fun close(db: RawPtr): Unit {
    sqlite3Close(db)
}

// The message of the last failed operation on the handle - `sqlite3_errmsg` verbatim.
fun lastError(db: RawPtr): Str {
    return sqlite3ErrMsg(db)
}

// The engine's version string ("3.45.1"), for a banner or a log line.
fun version(): Str {
    return sqlite3LibVersion()
}

// ---- the statement ---------------------------------------------------------------

fun prepare(db: RawPtr, sql: Str): Res<RawPtr> {
    val prepared: PreparedStmt = PreparedStmt(null)
    val rc: Int = sqlite3Prepare(db, sql, sql.size(), *prepared, null)
    if (rc != SQLITE_OK) {
        if (prepared.stmt != null) {
            sqlite3Finalize(prepared.stmt)
        }
        return Res<RawPtr>.err(lastError(db))
    }
    return Res<RawPtr>.ok(prepared.stmt)
}

// Binds every value at its list position: the first `?` takes `values[0]`, and so on.
fun bindAll(stmt: RawPtr, values: List<SqlValue>): Res<Int> {
    var i: Int = 0
    while (i < values.size()) {
        val v: SqlValue = values[i]
        var rc: Int = SQLITE_ERROR
        when (v) {
            I -> {
                rc = sqlite3BindInt(stmt, i + 1, v.I)
            }

            F -> {
                rc = sqlite3BindDouble(stmt, i + 1, v.F)
            }

            T -> {
                rc = sqlite3BindText(stmt, i + 1, v.T, v.T.size(), SQLITE_TRANSIENT())
            }

            None -> {
                rc = sqlite3BindNull(stmt, i + 1)
            }
        }
        if (rc != SQLITE_OK) {
            return Res<Int>.err("sqlite: bind #" + (i + 1).toString() + " failed")
        }
        i++
    }
    return Res<Int>.ok(values.size())
}

// Prepares, binds and runs a statement to completion, calling `row` for every row of
// the result with a borrowed view. The statement is finalized on every path; the answer
// is the row count. The one call most readers want.
fun query(db: RawPtr, sql: Str, values: List<SqlValue>, row: (*Row) -> Unit): Res<Int> {
    val stmt: Res<RawPtr> = prepare(db, sql)
    if (!stmt.isOk()) {
        return Res<Int>.err(stmt.error())
    }
    val bound: Res<Int> = bindAll(stmt.value(), values)
    if (!bound.isOk()) {
        sqlite3Finalize(stmt.value())
        return bound
    }
    var rows: Int = 0
    while (true) {
        val rc: Int = sqlite3Step(stmt.value())
        if (rc == SQLITE_ROW) {
            var view: Row = Row(stmt.value(), sqlite3ColumnCount(stmt.value()))
            row(view)
            rows++
            continue
        }
        if (rc == SQLITE_DONE) {
            break
        }
        val message: Str = lastError(db)
        sqlite3Finalize(stmt.value())
        return Res<Int>.err(message)
    }
    sqlite3Finalize(stmt.value())
    return Res<Int>.ok(rows)
}

// One bound statement run to completion: INSERT / UPDATE / DELETE with `?` placeholders,
// answering the rows the statement touched.
fun execBind(db: RawPtr, sql: Str, values: List<SqlValue>): Res<Int> {
    val stmt: Res<RawPtr> = prepare(db, sql)
    if (!stmt.isOk()) {
        return Res<Int>.err(stmt.error())
    }
    val bound: Res<Int> = bindAll(stmt.value(), values)
    if (!bound.isOk()) {
        sqlite3Finalize(stmt.value())
        return bound
    }
    val rc: Int = sqlite3Step(stmt.value())
    if (rc != SQLITE_DONE) {
        val message: Str = lastError(db)
        sqlite3Finalize(stmt.value())
        return Res<Int>.err(message)
    }
    sqlite3Finalize(stmt.value())
    return Res<Int>.ok(sqlite3Changes(db))
}

// ---- the row views and values -----------------------------------------------------

// A borrowed view of the row `sqlite3_step` is sitting on: valid inside the `query`
// lambda, no copies. The getters convert the way SQLite does - an INTEGER column reads
// as text and a TEXT column reads as a number without complaint.
data class Row(var stmt: RawPtr, var width: Int)

fun Row.getInt(index: Int): Int64 {
    return sqlite3ColumnInt(this.stmt, index)
}

fun Row.getFloat(index: Int): Float64 {
    return sqlite3ColumnDouble(this.stmt, index)
}

fun Row.getStr(index: Int): Str {
    return sqlite3ColumnText(this.stmt, index)
}

fun Row.isNull(index: Int): Bool {
    return sqlite3ColumnType(this.stmt, index) == SQLITE_NULL
}

fun Row.columnCount(): Int {
    return this.width
}

// `SqlValue` is a bind value and a materialized cell: one arm live, `SqlValue()` the SQL
// NULL. Construction picks the arm by the value's type - `SqlValue(7)`,
// `SqlValue(2.5)`, `SqlValue("text")` - and a `when (v)` over `I`/`F`/`T`/`None`
// reads it back.
union class SqlValue(var I: Int64, var F: Float64, var T: Str)

// The cell as text: an integer or a float prints, a null is the empty string, a text is
// itself. What `Row.getStr` answers for any column.
fun SqlValue.asText(): Str {
    val tag: SmSqlValueTypes = this.getTypeOf()
    if (tag == SmSqlValueTypes.I) {
        return this.I.toString()
    }
    if (tag == SmSqlValueTypes.F) {
        return this.F.toString()
    }
    if (tag == SmSqlValueTypes.T) {
        return this.T
    }
    return ""
}

// A materialized row: the column names and the cells of one step, valid after the
// statement is gone. What `queryRows` answers a list of, and what the `linq` module
// pipelines over.
data class RowValue(var names: List<Str>, var values: List<SqlValue>)

fun RowValue.columnCount(): Int {
    return this.names.size()
}

// The cell of column `name`, or none: the by-name read.
fun RowValue.cell(name: Str): Opt<SqlValue> {
    var i: Int = 0
    while (i < this.names.size()) {
        if (this.names[i] == name) {
            return Opt<SqlValue>.some(this.values[i])
        }
        i++
    }
    return Opt<SqlValue>.none()
}

fun RowValue.text(name: Str): Str {
    val cell: Opt<SqlValue> = this.cell(name)
    if (cell.hasValue()) {
        return cell.value().asText()
    }
    return ""
}

// The by-name reads answer zero for a column that is not there - `cell(name)` is the
// checking read, these are the table-walking ones.
fun RowValue.int64(name: Str): Int64 {
    val cell: Opt<SqlValue> = this.cell(name)
    if (!cell.hasValue()) {
        return 0
    }
    val tag: SmSqlValueTypes = cell.value().getTypeOf()
    if (tag == SmSqlValueTypes.I) {
        return cell.value().I
    }
    if (tag == SmSqlValueTypes.F) {
        return Int64(cell.value().F)
    }
    return 0
}

fun RowValue.float64(name: Str): Float64 {
    val cell: Opt<SqlValue> = this.cell(name)
    if (!cell.hasValue()) {
        return 0.0
    }
    val tag: SmSqlValueTypes = cell.value().getTypeOf()
    if (tag == SmSqlValueTypes.F) {
        return cell.value().F
    }
    if (tag == SmSqlValueTypes.I) {
        return Float64(cell.value().I)
    }
    return 0.0
}

fun RowValue.isNull(name: Str): Bool {
    val cell: Opt<SqlValue> = this.cell(name)
    if (cell.hasValue()) {
        return cell.value().getTypeOf() == SmSqlValueTypes.None
    }
    return true
}

// The whole result set, materialized: rows outlive the statement and can be filtered,
// mapped and sorted by the `linq` operators. The breadth a `query` lambda does not need
// and a report does.
fun queryRows(db: RawPtr, sql: Str, values: List<SqlValue>): Res<List<RowValue>> {
    val stmt: Res<RawPtr> = prepare(db, sql)
    if (!stmt.isOk()) {
        return Res<List<RowValue>>.err(stmt.error())
    }
    val bound: Res<Int> = bindAll(stmt.value(), values)
    if (!bound.isOk()) {
        sqlite3Finalize(stmt.value())
        return Res<List<RowValue>>.err(bound.error())
    }
    var names: List<Str> = List<Str>()
    val width: Int = sqlite3ColumnCount(stmt.value())
    var i: Int = 0
    while (i < width) {
        names.append(sqlite3ColumnName(stmt.value(), i))
        i++
    }
    var rows: List<RowValue> = List<RowValue>()
    while (true) {
        val rc: Int = sqlite3Step(stmt.value())
        if (rc == SQLITE_ROW) {
            var cells: List<SqlValue> = List<SqlValue>()
            var c: Int = 0
            while (c < width) {
                val type: Int = sqlite3ColumnType(stmt.value(), c)
                if (type == SQLITE_INTEGER) {
                    val n: Int64 = sqlite3ColumnInt(stmt.value(), c)
                    cells.append(SqlValue(n))
                } else if (type == SQLITE_FLOAT) {
                    val x: Float64 = sqlite3ColumnDouble(stmt.value(), c)
                    cells.append(SqlValue(x))
                } else if (type == SQLITE_NULL) {
                    cells.append(SqlValue())
                } else {
                    val t: Str = sqlite3ColumnText(stmt.value(), c)
                    cells.append(SqlValue(t))
                }
                c++
            }
            rows.append(RowValue(names, cells))
            continue
        }
        if (rc == SQLITE_DONE) {
            break
        }
        val message: Str = lastError(db)
        sqlite3Finalize(stmt.value())
        return Res<List<RowValue>>.err(message)
    }
    sqlite3Finalize(stmt.value())
    return Res<List<RowValue>>.ok(rows)
}
