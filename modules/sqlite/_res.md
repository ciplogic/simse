Generated C++
====
The `sqlite` module's glue: the C++ side of `api.kt`.

!sqliteglue
====
emit: reached
includes:
```cpp
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif
```
forward:
```cpp
void* simse_sqlite_open(const Str& path, Int flags);
Str simse_sqlite_lastError(void* db);
Int simse_sqlite_close(void* db);
Int simse_sqlite_exec(void* db, const Str& sql);
Int simse_sqlite_changes(void* db);
void* simse_sqlite_prepare(void* db, const Str& sql);
Int simse_sqlite_step(void* stmt);
Int simse_sqlite_reset(void* stmt);
Int simse_sqlite_clearBindings(void* stmt);
Int simse_sqlite_finalize(void* stmt);
Int simse_sqlite_bindCount(void* stmt);
Int simse_sqlite_bindNull(void* stmt, Int index);
Int simse_sqlite_bindInt(void* stmt, Int index, Int64 value);
Int simse_sqlite_bindDouble(void* stmt, Int index, Float64 value);
Int simse_sqlite_bindText(void* stmt, Int index, const Str& value);
Int simse_sqlite_columnCount(void* stmt);
Str simse_sqlite_columnName(void* stmt, Int index);
Int simse_sqlite_columnType(void* stmt, Int index);
Int64 simse_sqlite_columnInt(void* stmt, Int index);
Float64 simse_sqlite_columnDouble(void* stmt, Int index);
Str simse_sqlite_columnText(void* stmt, Int index);
Str simse_sqlite_libVersion();
```
bodies:
```cpp
// The sqlite3 API surface the module reaches, as one struct of function pointers resolved
// once. The signatures are the library's own (sqlite3.h), written here because the module
// deliberately ships no header and links nothing: like the compiler's `native` loader
// (src/compiler/NativeInvokeGen.kt), the symbols are looked up at run time, on Windows in
// sqlite3.dll, elsewhere in the runtime libsqlite3.so.0 (the SONAME the distributions
// install; the -dev package's unversioned .so is not needed).
struct SimseSqliteApi {
    int (*open_v2)(const char*, void**, int, const char*) = nullptr;
    int (*close_v2)(void*) = nullptr;
    const char* (*errmsg)(void*) = nullptr;
    int (*exec)(void*, const char*, int (*)(void*, int, char**, char**), void*, char**) = nullptr;
    int (*changes)(void*) = nullptr;
    int (*prepare_v2)(void*, const char*, int, void**, const char**) = nullptr;
    int (*step)(void*) = nullptr;
    int (*reset)(void*) = nullptr;
    int (*clear_bindings)(void*) = nullptr;
    int (*finalize)(void*) = nullptr;
    int (*bind_parameter_count)(void*) = nullptr;
    int (*bind_null)(void*, int) = nullptr;
    int (*bind_int64)(void*, int, long long) = nullptr;
    int (*bind_double)(void*, int, double) = nullptr;
    int (*bind_text)(void*, int, const char*, int, void (*)(void*)) = nullptr;
    int (*column_count)(void*) = nullptr;
    const char* (*column_name)(void*, int) = nullptr;
    int (*column_type)(void*, int) = nullptr;
    long long (*column_int64)(void*, int) = nullptr;
    double (*column_double)(void*, int) = nullptr;
    const unsigned char* (*column_text)(void*, int) = nullptr;
    const char* (*libversion)() = nullptr;
};

inline SimseSqliteApi& simse_sqlite_api() {
    static SimseSqliteApi api;
    return api;
}

// The library load, once. A failed load stays failed: every wrapper then answers the
// error code a caller maps to a message, and the message - what actually happened - is
// what `simse_sqlite_lastError` holds.
inline void simse_sqlite_setError(const char* text);

inline bool simse_sqlite_ready() {
    static bool ready = false;
    if (ready) return true;
#if defined(_WIN32)
    HMODULE module = LoadLibraryA("sqlite3.dll");
#else
    void* module = dlopen("libsqlite3.so.0", RTLD_NOW | RTLD_LOCAL);
#endif
    if (module == nullptr) {
        simse_sqlite_setError("the sqlite3 library could not be loaded");
        return false;
    }
    SimseSqliteApi& api = simse_sqlite_api();
    bool missing = false;
    const char* missingName = "";
#if defined(_WIN32)
#define SM_SQLITE_LOOKUP(module, name) (void*)GetProcAddress(module, name)
#else
#define SM_SQLITE_LOOKUP(module, name) dlsym(module, name)
#endif

#define SM_SQLITE_SYM(field, name) \
    if (!missing) { \
        api.field = reinterpret_cast<decltype(api.field)>(SM_SQLITE_LOOKUP(module, name)); \
        if (api.field == nullptr) { missing = true; missingName = name; } \
    }
    SM_SQLITE_SYM(open_v2, "sqlite3_open_v2")
    SM_SQLITE_SYM(close_v2, "sqlite3_close_v2")
    SM_SQLITE_SYM(errmsg, "sqlite3_errmsg")
    SM_SQLITE_SYM(exec, "sqlite3_exec")
    SM_SQLITE_SYM(changes, "sqlite3_changes")
    SM_SQLITE_SYM(prepare_v2, "sqlite3_prepare_v2")
    SM_SQLITE_SYM(step, "sqlite3_step")
    SM_SQLITE_SYM(reset, "sqlite3_reset")
    SM_SQLITE_SYM(clear_bindings, "sqlite3_clear_bindings")
    SM_SQLITE_SYM(finalize, "sqlite3_finalize")
    SM_SQLITE_SYM(bind_parameter_count, "sqlite3_bind_parameter_count")
    SM_SQLITE_SYM(bind_null, "sqlite3_bind_null")
    SM_SQLITE_SYM(bind_int64, "sqlite3_bind_int64")
    SM_SQLITE_SYM(bind_double, "sqlite3_bind_double")
    SM_SQLITE_SYM(bind_text, "sqlite3_bind_text")
    SM_SQLITE_SYM(column_count, "sqlite3_column_count")
    SM_SQLITE_SYM(column_name, "sqlite3_column_name")
    SM_SQLITE_SYM(column_type, "sqlite3_column_type")
    SM_SQLITE_SYM(column_int64, "sqlite3_column_int64")
    SM_SQLITE_SYM(column_double, "sqlite3_column_double")
    SM_SQLITE_SYM(column_text, "sqlite3_column_text")
    SM_SQLITE_SYM(libversion, "sqlite3_libversion")
#undef SM_SQLITE_SYM
    if (missing) {
        simse_sqlite_setError((std::string("the sqlite3 library lacks ") + missingName).c_str());
        return false;
    }
    ready = true;
    return true;
}

// The error of the last failed operation: the database's own message for an open handle
// (valid until the next call on that handle, so the Simse layer copies it by returning
// `Str`), the loader's otherwise.
// The message for the failure in progress. `thread_local`: two threads' failures do not
// overwrite each other, and the text outlives the call that reported it.
thread_local std::string simse_sqlite_error;

inline void simse_sqlite_setError(const char* text) {
    simse_sqlite_error = text == nullptr ? "" : text;
}

Str simse_sqlite_lastError(void* db) {
    if (!simse_sqlite_ready()) {
        return Str(simse_sqlite_error.c_str());
    }
    SimseSqliteApi& api = simse_sqlite_api();
    if (db != nullptr && api.errmsg != nullptr) {
        const char* text = api.errmsg(db);
        if (text != nullptr && text[0] != '\0') {
            return Str(text);
        }
    }
    return Str(simse_sqlite_error.c_str());
}

void* simse_sqlite_open(const Str& path, Int flags) {
    if (!simse_sqlite_ready()) return nullptr;
    SimseSqliteApi& api = simse_sqlite_api();
    void* db = nullptr;
    int rc = api.open_v2(path.c_str(), &db, (int) flags, nullptr);
    if (rc != 0) {
        if (db != nullptr && api.errmsg != nullptr) {
            simse_sqlite_setError(api.errmsg(db));
        } else {
            simse_sqlite_setError("sqlite3_open_v2 failed");
        }
        if (db != nullptr && api.close_v2 != nullptr) api.close_v2(db);
        return nullptr;
    }
    return db;
}

Int simse_sqlite_close(void* db) {
    if (!simse_sqlite_ready()) return 1;
    int rc = simse_sqlite_api().close_v2(db);
    simse_sqlite_setError(nullptr);
    return (Int) rc;
}

Int simse_sqlite_exec(void* db, const Str& sql) {
    if (!simse_sqlite_ready()) return 1;
    SimseSqliteApi& api = simse_sqlite_api();
    int rc = api.exec(db, sql.c_str(), nullptr, nullptr, nullptr);
    if (rc != 0 && api.errmsg != nullptr) simse_sqlite_setError(api.errmsg(db));
    return (Int) rc;
}

Int simse_sqlite_changes(void* db) {
    if (!simse_sqlite_ready()) return 0;
    return (Int) simse_sqlite_api().changes(db);
}

void* simse_sqlite_prepare(void* db, const Str& sql) {
    if (!simse_sqlite_ready()) return nullptr;
    SimseSqliteApi& api = simse_sqlite_api();
    void* stmt = nullptr;
    // The tail is left unread: a module call prepares one statement, and a script goes
    // through `simse_sqlite_exec`, which walks its statements internally.
    int rc = api.prepare_v2(db, sql.c_str(), (int) sql.size(), &stmt, nullptr);
    if (rc != 0) {
        if (api.errmsg != nullptr) simse_sqlite_setError(api.errmsg(db));
        if (stmt != nullptr && api.finalize != nullptr) api.finalize(stmt);
        return nullptr;
    }
    return stmt;
}

Int simse_sqlite_step(void* stmt) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().step(stmt);
}

Int simse_sqlite_reset(void* stmt) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().reset(stmt);
}

Int simse_sqlite_clearBindings(void* stmt) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().clear_bindings(stmt);
}

Int simse_sqlite_finalize(void* stmt) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().finalize(stmt);
}

Int simse_sqlite_bindCount(void* stmt) {
    if (!simse_sqlite_ready()) return 0;
    return (Int) simse_sqlite_api().bind_parameter_count(stmt);
}

Int simse_sqlite_bindNull(void* stmt, Int index) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().bind_null(stmt, (int) index);
}

Int simse_sqlite_bindInt(void* stmt, Int index, Int64 value) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().bind_int64(stmt, (int) index, (long long) value);
}

Int simse_sqlite_bindDouble(void* stmt, Int index, Float64 value) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().bind_double(stmt, (int) index, (double) value);
}

// SQLITE_TRANSIENT: the statement copies the text, so the caller's `Str` may die first -
// the only safe choice for a boundary the language does not trace.
Int simse_sqlite_bindText(void* stmt, Int index, const Str& value) {
    if (!simse_sqlite_ready()) return 1;
    return (Int) simse_sqlite_api().bind_text(stmt, (int) index, value.c_str(), (int) value.size(),
        reinterpret_cast<void (*)(void*)>(-1));
}

Int simse_sqlite_columnCount(void* stmt) {
    if (!simse_sqlite_ready()) return 0;
    return (Int) simse_sqlite_api().column_count(stmt);
}

Str simse_sqlite_columnName(void* stmt, Int index) {
    if (!simse_sqlite_ready()) return Str();
    const char* text = simse_sqlite_api().column_name(stmt, (int) index);
    return Str(text == nullptr ? "" : text);
}

// SQLITE_INTEGER 1, SQLITE_FLOAT 2, SQLITE_TEXT 3, SQLITE_BLOB 4, SQLITE_NULL 5.
Int simse_sqlite_columnType(void* stmt, Int index) {
    if (!simse_sqlite_ready()) return 0;
    return (Int) simse_sqlite_api().column_type(stmt, (int) index);
}

Int64 simse_sqlite_columnInt(void* stmt, Int index) {
    if (!simse_sqlite_ready()) return 0;
    return (Int64) simse_sqlite_api().column_int64(stmt, (int) index);
}

Float64 simse_sqlite_columnDouble(void* stmt, Int index) {
    if (!simse_sqlite_ready()) return 0.0;
    return (Float64) simse_sqlite_api().column_double(stmt, (int) index);
}

// Null reads as the empty string: a SQL NULL and an empty text are different types (the
// caller asks `columnType` when it matters), and no Simse caller wants a null `Str`.
Str simse_sqlite_columnText(void* stmt, Int index) {
    if (!simse_sqlite_ready()) return Str();
    const unsigned char* text = simse_sqlite_api().column_text(stmt, (int) index);
    return Str(text == nullptr ? "" : reinterpret_cast<const char*>(text));
}

Str simse_sqlite_libVersion() {
    if (!simse_sqlite_ready()) return Str("unavailable");
    const char* text = simse_sqlite_api().libversion();
    return Str(text == nullptr ? "unavailable" : text);
}
```
