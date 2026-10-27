# Limitations: rough edges, what was fixed, and what still needs the workaround

This file started as the record of what did not work while building `modules/xml`
(parser, writer, readers) and `modules/sqlite` (a run-time binding to the platform's
SQLite). The compiler-side items have since been fixed - each with a stress case - and
the ones that remain are the workarounds a module author still needs. When a fix lands,
its item here retires.

## Fixed (the record, and where the pin lives)

- **Same-name extension methods resolved by name, not shape.** With `Query.whereBind(cond,
  value)` and `Query.whereBind2(cond, a, b)` both declared, a call spelling `whereBind`
  with three arguments once resolved `whereBind2`'s signature but emitted `whereBind`'s
  name and symbol - no diagnostic, a C++ error far from the cause. The call resolution
  now selects by receiver and arity before binding a name
  (`LinearFormExpr.callTarget`'s several-candidate lane, the emitter's own
  `findExtensionFn`), and the arity mismatch is a diagnostic at the call with the
  declaration's shape in its hint: `stress/diagnostic-extension-arity` pins it.
- **A program's own function lost to an imported extension of the same name.** The
  two-argument `attr(name, value)` of a program importing a module whose `XmlNode.attr`
  extension shares the name once resolved to the extension and emitted nonsense. Same-name
  declarations now emit as C++ overloads of one symbol and each call resolves by its own
  shape - the emitter's by-arity resolution and sema's arguments-picked overload agree on
  which declaration a call names: `stress/overload-shadow` pins both calls of one program.
- **Hexadecimal `Int` literals.** `0x`/`0X` and the hexadecimal digits, the spelling a C
  flag or mask wants (`SQLITE_OPEN_READWRITE` is `0x00000002`); a magnitude past `Int32`
  is an `Int64` literal exactly as a long decimal is, and the value folds like any
  integer (`specs/built-in-types.md`, "Integer literals"). The one rule lives in
  `common/literals.kt` (`litParseInt64`/`litIntLiteralIsInt64`), read by the fold and the
  width test alike: `stress/hex-literals` pins it.
- **A call's type was invisible.** `exprType` answered "unknown" for every plain call,
  so a union construction could not match its arm against a call's result, a `for` over
  a call's result had no type to wrap, and a `Res`-returning call written directly in a
  `return` dropped its out-parameter slot. `Analyzer.semaPlainCallReturn` resolves the
  arity-matching overload with its type parameters bound; `stress/call-return-type` pins
  the three readers.
- **A union construction's argument by spelling.** A scalar conversion names its result
  (`Int64(x)` is an `Int64` - the shape a bound P/Invoke value takes), a `Str` literal is
  a `Str`, an `Int` literal an `Int`; all three now match arms, and a real mismatch still
  diagnoses. Pinned in `stress/call-return-type`.
- **The P/Invoke surface grew two pieces** (not a fix, a capability): `rawPtr(address)`
  spells the pointer-sized-integer constants a C API wants (`SQLITE_TRANSIENT` is
  `((void*)-1)`; pinned in `stress/raw-ptr-handle`), and the native loader falls back to
  the macOS `.dylib` spelling of a `.so` name, so one declaration serves both platforms.
  The `native class` out-parameter pattern - the library writes through the address the
  borrow hands it - is pinned in `stress/native-out-param` (Windows), and
  `modules/sqlite` is now a pure `@SmGen("native", ...)` binding with no C++ glue at all.
- **`tools/iterate` runs on Linux** (g++ or clang++, the shim include when the sibling
  checkout is there): the corpus is 16 cases at a time instead of the shell loop's
  serial walk, and the three `test-*` diagnostics count correctly.

## Still open (the workarounds a module author needs)

- **Bare `when` labels need a simple-name subject.** `when (v) { I -> ... }` works for a
  local `v`; `when (cell.value()) { I -> ... }` emits a bare `I` the C++ cannot see.
  Workaround: `when (x.getTypeOf())` with qualified labels (`SmSqlValueTypes.I`), the
  form `stress/unions`' `labelSide` uses.
- **`Res<Unit>` is not usable** (an invalid `Res<void>` in C++ - already known to
  `modules/minui`). Fallible-nothing operations answer `Res<Int>` (a row count, a
  status) instead.
- **A by-value parameter never propagates mutations.** A helper taking `out: Str` or
  `out: List<T>` and appending to it mutates a copy. Workaround: the cursor-and-borrow
  idiom `modules/minui/xmlread.kt` uses - the mutable state as `*XmlParser`/`*Str`
  parameters, lent at the call site with `*value`.
- **`for` needs a local as its source... sometimes.** `for (x in f())` works when the
  call's return type resolves (see the fixed list); a call the checker cannot type still
  fails with `has no member named iterValues`. Bind the intermediate to a name and the
  diagnostic, when there is one, points at the right line.
- **A non-generic machine cannot be consumed by `for`.** A source machine declared
  without type parameters (`fun XmlNode.elements(): ..*XmlNode`) has no `iter` member in
  its generated yieldable; the same machine feeds the `linq` operators fine. Workaround:
  pipelines for machines, `elementList()` (a plain `List`) for loops - until the
  emitter generates `iter` for every yieldable.
- **Single-statement bodies want braces.** `if (x) return y` parses as an error at the
  `return`; `while (c) d++` the same. Braces everywhere.
- **No leading-dot chain continuation.** A method chain must sit on one line; a
  continuation line starting with `.` is a parse error. Long chains: bind the
  intermediate to a name.
  *Fixed: a continuation line starting with `.` extends the expression above it now -
  the one newline an expression may span, tried tentatively so every other newline ends
  it as ever (`Parser.parsePostfix`); `stress/dot-continuation` pins it.*
- **`Char` is a signed byte.** `ch >= 128` is tautologically false (clang warns); the
  non-ASCII test is `Int(ch) < 0`, the unsigned read `Int(ch) & 255`
  (`bench/simse/kit`).
- **Raw strings are backtick strings**, and once one interpolates, every `@` must start
  a name - an error message wanting a literal `@` goes to a `"..."` string. The modules'
  error messages use plain concatenation throughout, which is also what keeps them free
  of interpolation rules.
- **Lambda captures are by value.** An accumulator written inside `forEach` stays
  inside it (reference captures and explicit capture lists are deferred,
  `ai/status.md`). A fold over materialized rows is a plain `for`.
- **A function reference silently coerces to a value.** Passing `SQLITE_TRANSIENT`
  where `SQLITE_TRANSIENT()` was meant - a function name without its call - compiled
  and emitted the function's address where a `RawPtr` slot was wanted. Until a
  diagnostic exists, a wrong-typed argument at a native declaration is worth reading
  twice.

## The `native` boundary's own hazards

- **The C API's arity is the ABI.** A `native` declaration's parameter list must match
  the C function's *full* signature; the thunk casts the resolved symbol to the
  declaration's own function-pointer type, so a short parameter list compiles, calls,
  and corrupts the heap two operations later (`sqlite3_exec` is the textbook: five
  parameters, the fifth `char** errmsg`). Check the declaration against the library's
  header, however obvious it looks.
- **A Str parameter cannot spell NULL.** A `null` argument for a `Str` parameter
  arrives as the empty string, and a library that distinguishes NULL from "" rejects it
  ("no such vfs:" was the lesson). The untyped slot is a `RawPtr` parameter, passed
  `null`.
- **A missing library answers the thunk's defaults.** `@SmGen("native", ...)` resolves
  at run time and a load failure answers null - which for an `Int`-returning open call
  is `SQLITE_OK` with a null handle. A wrapper's job: treat "ok but null" as the
  loader's failure (`modules/sqlite/sqlite.kt`'s `open` does).
- **The library name is the declaration's.** `libsqlite3.so.0` serves Linux, and the
  loader's `.dylib` fallback serves macOS; Windows wants `sqlite3.dll`, which a
  cross-platform module spells as a second declaration until declarations can name a
  library per platform.
- **The bootstrap's pin is lifted.** The published bootstrap tracks HEAD again
  (`simse_bootstrap` refreshed from the same commit), so the corpus's remaining Linux
  failures are exactly the Windows-only natives: `http`, `native-invoke`,
  `native-struct-pointer`, `sockets`, `minui-*` and `stress/native-out-param`'s kernel32
  pair.
