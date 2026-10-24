package sqlite

// The `sqlite` module: a binding to SQLite built entirely on the `native` generator
// (impl_specs/native-interop.md) - one body-less declaration per C function, no C++ glue,
// no `_res.md`, nothing linked and no sqlite header needed. The library only has to
// exist where the program runs: on Linux the runtime `libsqlite3.so.0` (the loader falls
// back to the macOS `libsqlite3.dylib` spelling of the same name), on Windows
// `sqlite3.dll` beside the executable.
//
// The shapes the C API needs, spelled with the language's own words:
//   - an *out-parameter* (`sqlite3_open_v2`'s `sqlite3**`) is a `native class` whose
//     first field is the handle - the library writes through the address the borrow
//     hands it, and the field reads back (`stress/native-struct-pointer`'s pattern);
//   - an untyped, possibly-null pointer slot (the vfs name, `sqlite3_exec`'s callback
//     and error-out) is a `RawPtr`, passed `null`;
//   - the constants the C API spells as pointer-sized integers - `SQLITE_TRANSIENT` is
//     `((void*)-1)` - are `rawPtr(Int64(-1))`, the prelude's escape hatch
//     (src/rtl/rtl.kt).
//
// This file is the *raw* layer: the C ABI written in Simse types. The friendly layer
// above it - `open`/`exec`/`query`/`Row`, the `SqlValue` bind values and the query
// builder - is `sqlite.kt` and `builder.kt`, and is what a program should import for.
// The raw layer stays public because the C surface is bigger than any wrapper: a program
// that needs `sqlite3_backup_*` writes its declarations beside these in the same style.
//
// One behavior to know: a library that could not be loaded answers the thunk's default
// value, so a failed load looks like `SQLITE_OK` with a null handle - which is why
// `open` in `sqlite.kt` treats "ok but null" as the loader's failure, not a success.

// The out-parameter shapes. One field, at offset zero: what the library's own
// `sqlite3*` write lands in.
native class OpenedDb(var db: RawPtr)

native class PreparedStmt(var stmt: RawPtr)

// ---- the database handle ---------------------------------------------------

// `sqlite3_open_v2`. SQLITE_OK with a null handle means the library never loaded; any
// other code is SQLite's own failure (the message is `sqlite3ErrMsg`).
@SmGen("native", "libsqlite3.so.0", "sqlite3_open_v2")
fun sqlite3Open(path: Str, db: *OpenedDb, flags: Int, vfs: RawPtr): Int

// `sqlite3_close_v2`.
@SmGen("native", "libsqlite3.so.0", "sqlite3_close_v2")
fun sqlite3Close(db: RawPtr): Int

// `sqlite3_errmsg`: the message of the last failed operation on the handle. A null
// handle answers "not an error" - which is why the open failure says so itself.
@SmGen("native", "libsqlite3.so.0", "sqlite3_errmsg")
fun sqlite3ErrMsg(db: RawPtr): Str

// `sqlite3_exec` with no callback and no error-out: the whole script runs, statement
// after statement (schema setup, BEGIN/COMMIT, PRAGMA). The three trailing slots the C
// signature wants are untyped pointers passed null.
@SmGen("native", "libsqlite3.so.0", "sqlite3_exec")
fun sqlite3Exec(db: RawPtr, sql: Str, callback: RawPtr, arg: RawPtr, errmsg: RawPtr): Int

// `sqlite3_changes`.
@SmGen("native", "libsqlite3.so.0", "sqlite3_changes")
fun sqlite3Changes(db: RawPtr): Int

// ---- the statement lifecycle -------------------------------------------------

// `sqlite3_prepare_v2` over the first statement of `sql`. The tail pointer the C
// signature wants is untyped and null - a module call prepares one statement, and a
// script goes through `sqlite3Exec`, which walks its statements internally. The v2 form
// keeps the prepared statement valid across schema changes (it re-prepares itself),
// which is what a long-lived program wants.
@SmGen("native", "libsqlite3.so.0", "sqlite3_prepare_v2")
fun sqlite3Prepare(db: RawPtr, sql: Str, nByte: Int, stmt: *PreparedStmt, tail: RawPtr): Int

// `sqlite3_step`: SQLITE_ROW while rows come, SQLITE_DONE when the statement is finished.
@SmGen("native", "libsqlite3.so.0", "sqlite3_step")
fun sqlite3Step(stmt: RawPtr): Int

// `sqlite3_reset` / `sqlite3_clear_bindings`: run the same statement again with new binds.
@SmGen("native", "libsqlite3.so.0", "sqlite3_reset")
fun sqlite3Reset(stmt: RawPtr): Int

@SmGen("native", "libsqlite3.so.0", "sqlite3_clear_bindings")
fun sqlite3ClearBindings(stmt: RawPtr): Int

// `sqlite3_finalize`: every prepared statement reaches this exactly once.
@SmGen("native", "libsqlite3.so.0", "sqlite3_finalize")
fun sqlite3Finalize(stmt: RawPtr): Int

// `sqlite3_bind_parameter_count`.
@SmGen("native", "libsqlite3.so.0", "sqlite3_bind_parameter_count")
fun sqlite3BindCount(stmt: RawPtr): Int

// ---- the binds ----------------------------------------------------------------

// `sqlite3_bind_*`. Indexes are 1-based, as in C. Text binds with SQLITE_TRANSIENT
// (`rawPtr(Int64(-1))`, in `sqlite.kt`), so the statement owns its copy and the
// caller's `Str` may die first.
@SmGen("native", "libsqlite3.so.0", "sqlite3_bind_null")
fun sqlite3BindNull(stmt: RawPtr, index: Int): Int

@SmGen("native", "libsqlite3.so.0", "sqlite3_bind_int64")
fun sqlite3BindInt(stmt: RawPtr, index: Int, value: Int64): Int

@SmGen("native", "libsqlite3.so.0", "sqlite3_bind_double")
fun sqlite3BindDouble(stmt: RawPtr, index: Int, value: Float64): Int

@SmGen("native", "libsqlite3.so.0", "sqlite3_bind_text")
fun sqlite3BindText(stmt: RawPtr, index: Int, value: Str, n: Int, destructor: RawPtr): Int

// ---- the columns ---------------------------------------------------------------

// `sqlite3_column_count` / `sqlite3_column_name`: the shape of the current result set.
@SmGen("native", "libsqlite3.so.0", "sqlite3_column_count")
fun sqlite3ColumnCount(stmt: RawPtr): Int

@SmGen("native", "libsqlite3.so.0", "sqlite3_column_name")
fun sqlite3ColumnName(stmt: RawPtr, index: Int): Str

// `sqlite3_column_type` for the current row: SQLITE_INTEGER, SQLITE_FLOAT, SQLITE_TEXT,
// SQLITE_BLOB (read the bytes you did not plan for as text) or SQLITE_NULL.
@SmGen("native", "libsqlite3.so.0", "sqlite3_column_type")
fun sqlite3ColumnType(stmt: RawPtr, index: Int): Int

// `sqlite3_column_int64` / `_double` / `_text` - each reads the value in its own type,
// converting the way SQLite does. A null column reads as 0 / 0.0 / "".
@SmGen("native", "libsqlite3.so.0", "sqlite3_column_int64")
fun sqlite3ColumnInt(stmt: RawPtr, index: Int): Int64

@SmGen("native", "libsqlite3.so.0", "sqlite3_column_double")
fun sqlite3ColumnDouble(stmt: RawPtr, index: Int): Float64

@SmGen("native", "libsqlite3.so.0", "sqlite3_column_text")
fun sqlite3ColumnText(stmt: RawPtr, index: Int): Str

// ---- the library itself ----------------------------------------------------------

// `sqlite3_libversion`: which engine answered, worth printing once.
@SmGen("native", "libsqlite3.so.0", "sqlite3_libversion")
fun sqlite3LibVersion(): Str
