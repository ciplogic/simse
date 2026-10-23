package sqlite

// The `sqlite` module: a binding to SQLite built on the `res` generator
// (impl_specs/native-interop.md, modules/sqlite/_res.md). The module owns no SQL engine -
// the glue resolves the sqlite3 API out of the platform's shared library at run time
// (`dlopen`/`dlsym`, `LoadLibraryA` on Windows), so nothing is linked and no sqlite header
// is needed: the library only has to be installed where the program runs (on Linux the
// runtime `libsqlite3.so.0` almost always is).
//
// This file is the *raw* layer: one declaration per C function the module reaches, with the
// C ABI written in Simse types. The friendly layer above it - `open`/`exec`/`query`/`Row`,
// the `SqlValue` bind values and the query builder - is `sqlite.kt` and `builder.kt`, and
// is what a program should import for. The raw layer stays public because the C surface is
// bigger than any wrapper: a program that needs `sqlite3_backup_*` writes its declarations
// beside these and lands them in the same glue.
//
// Conventions, mirroring the C API:
//   - a `RawPtr` is an opaque handle (`sqlite3*`, `sqlite3_stmt*`) - a `void*` the glue
//     casts on the way in and out;
//   - error codes come back as `Int` (SQLITE_OK, SQLITE_ROW, SQLITE_DONE, ... - the
//     constants in `sqlite.kt`), and the message for the *last* failure is
//     `sqliteLastError`, which reads `sqlite3_errmsg` or, before any handle exists, the
//     error the loader itself hit;
//   - a null handle is a valid value: `sqliteOpen` answers null when the open fails, and
//     the message is waiting in `sqliteLastError(null)`.

// ---- the database handle ---------------------------------------------------

// `sqlite3_open_v2`. Null on failure (the error message is in `sqliteLastError(null)`);
// on success the handle must reach `sqliteClose`.
@SmGen("res", "sqliteglue", "simse_sqlite_open")
fun sqliteOpen(path: Str, flags: Int): RawPtr

// `sqlite3_errmsg` for an open handle; the glue's own stored error when `db` is null - the
// open failure and the loader failure both report here.
@SmGen("res", "sqliteglue", "simse_sqlite_lastError")
fun sqliteLastError(db: RawPtr): Str

// `sqlite3_close_v2`.
@SmGen("res", "sqliteglue", "simse_sqlite_close")
fun sqliteClose(db: RawPtr): Int

// `sqlite3_exec` with no callback: the whole script runs, statement after statement
// (schema setup, BEGIN/COMMIT, PRAGMA). `sqlite3_changes` is the row count of the last
// statement, which `exec` in `sqlite.kt` returns.
@SmGen("res", "sqliteglue", "simse_sqlite_exec")
fun sqliteExec(db: RawPtr, sql: Str): Int

// `sqlite3_changes`.
@SmGen("res", "sqliteglue", "simse_sqlite_changes")
fun sqliteChanges(db: RawPtr): Int

// ---- the statement lifecycle -------------------------------------------------

// `sqlite3_prepare_v2` over the first statement of `sql`. Null on failure; the message is
// in `sqliteLastError(db)`. The v2 form keeps the prepared statement valid across schema
// changes (it re-prepares itself), which is what a long-lived program wants.
@SmGen("res", "sqliteglue", "simse_sqlite_prepare")
fun sqlitePrepare(db: RawPtr, sql: Str): RawPtr

// `sqlite3_step`: SQLITE_ROW while rows come, SQLITE_DONE when the statement is finished.
@SmGen("res", "sqliteglue", "simse_sqlite_step")
fun sqliteStep(stmt: RawPtr): Int

// `sqlite3_reset` / `sqlite3_clear_bindings`: run the same statement again with new binds.
@SmGen("res", "sqliteglue", "simse_sqlite_reset")
fun sqliteReset(stmt: RawPtr): Int

@SmGen("res", "sqliteglue", "simse_sqlite_clearBindings")
fun sqliteClearBindings(stmt: RawPtr): Int

// `sqlite3_finalize`: every prepared statement reaches this exactly once.
@SmGen("res", "sqliteglue", "simse_sqlite_finalize")
fun sqliteFinalize(stmt: RawPtr): Int

// `sqlite3_bind_parameter_count`.
@SmGen("res", "sqliteglue", "simse_sqlite_bindCount")
fun sqliteBindCount(stmt: RawPtr): Int

// ---- the binds ----------------------------------------------------------------

// `sqlite3_bind_*`. Indexes are 1-based, as in C. Text binds with SQLITE_TRANSIENT, so
// the statement owns its copy and the caller's `Str` may die first.
@SmGen("res", "sqliteglue", "simse_sqlite_bindNull")
fun sqliteBindNull(stmt: RawPtr, index: Int): Int

@SmGen("res", "sqliteglue", "simse_sqlite_bindInt")
fun sqliteBindInt(stmt: RawPtr, index: Int, value: Int64): Int

@SmGen("res", "sqliteglue", "simse_sqlite_bindDouble")
fun sqliteBindDouble(stmt: RawPtr, index: Int, value: Float64): Int

@SmGen("res", "sqliteglue", "simse_sqlite_bindText")
fun sqliteBindText(stmt: RawPtr, index: Int, value: Str): Int

// ---- the columns ---------------------------------------------------------------

// `sqlite3_column_count` / `sqlite3_column_name`: the shape of the current result set.
@SmGen("res", "sqliteglue", "simse_sqlite_columnCount")
fun sqliteColumnCount(stmt: RawPtr): Int

@SmGen("res", "sqliteglue", "simse_sqlite_columnName")
fun sqliteColumnName(stmt: RawPtr, index: Int): Str

// `sqlite3_column_type` for the current row: SQLITE_INTEGER, SQLITE_FLOAT, SQLITE_TEXT,
// SQLITE_BLOB (read the bytes you did not plan for as text) or SQLITE_NULL.
@SmGen("res", "sqliteglue", "simse_sqlite_columnType")
fun sqliteColumnType(stmt: RawPtr, index: Int): Int

// `sqlite3_column_int64` / `_double` / `_text` - each reads the value in its own type,
// converting the way SQLite does. A null column reads as 0 / 0.0 / "".
@SmGen("res", "sqliteglue", "simse_sqlite_columnInt")
fun sqliteColumnInt(stmt: RawPtr, index: Int): Int64

@SmGen("res", "sqliteglue", "simse_sqlite_columnDouble")
fun sqliteColumnDouble(stmt: RawPtr, index: Int): Float64

@SmGen("res", "sqliteglue", "simse_sqlite_columnText")
fun sqliteColumnText(stmt: RawPtr, index: Int): Str

// ---- the library itself ----------------------------------------------------------

// `sqlite3_libversion`: which engine answered, worth printing once.
@SmGen("res", "sqliteglue", "simse_sqlite_libVersion")
fun sqliteLibVersion(): Str
