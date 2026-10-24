# examples/sqlite - the sqlite module, three small programs

Each folder is one self-contained program against the same five-book table: `basics`
opens, creates, inserts with bound parameters, reads through a lambda, and watches an
error come back as a value; `builder` spells the reads (and the UPDATE/DELETE) as
builder chains; `linq` materializes a result set with `queryRows` and shapes it with
the `linq` module's operators.

The module (`modules/sqlite`) resolves the platform's SQLite at run time - nothing is
linked, no header is needed, and the module carries no C++ at all: the `native`
declarations in `api.kt` are the whole binding. The only prerequisite is that the
library exists where the program runs: on Linux the runtime `libsqlite3.so.0`
(installed nearly everywhere; the loader falls back to the macOS `libsqlite3.dylib`
spelling), on Windows `sqlite3.dll` beside the executable.

## Run (Linux)

From the repository root, with the compiler built (`docs/building-on-linux.md`):

```sh
g++ -std=c++20 -O2 -DNDEBUG -I. ../simse_bootstrap/builds/simse_bootstrap.cpp -o simse
./simse --root examples/sqlite/basics/src --module modules/sqlite -o /tmp/basics.cpp
g++ -std=c++20 -O2 -DNDEBUG -I. /tmp/basics.cpp -o /tmp/basics && /tmp/basics
```

`./run.sh` does that for all three, with g++ and then clang++ when both are present.

## The layer map

- `modules/sqlite/api.kt` - the raw C ABI, one `@SmGen("native", ...)` declaration per
  C function. The out-parameters (`sqlite3_open_v2`'s `sqlite3**`) are `native class`
  handle structs; the untyped null slots are `RawPtr`; `SQLITE_TRANSIENT` is
  `rawPtr(Int64(-1))`, the prelude's escape hatch.
- `modules/sqlite/sqlite.kt` - `open`/`exec`/`query`/`execBind`/`queryRows`, the
  `SqlValue` bind values, the borrowed `Row` and the materialized `RowValue`. It also
  owns the one behavior the thunk cannot see: a library that failed to load answers
  `SQLITE_OK` with a null handle, and `open` turns that into the loader's error.
- `modules/sqlite/builder.kt` - `select`/`select2`/`select3` chains, `whereBind`,
  `insert`/`insertCols`, `Query.rows`.

A program imports `sqlite` alone; the `linq` example adds `import linq`.
