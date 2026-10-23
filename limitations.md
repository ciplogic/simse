# Limitations: what does not work, hit while building the `xml` and `sqlite` modules

This file records the rough edges met while adding `modules/xml` (parser, writer,
readers) and `modules/sqlite` (a run-time binding to the platform's SQLite) **without
touching the compiler**. Each item is a workaround, not a fix: the fix belongs to the
compiler, and the workarounds are spelled the way the modules use them. When a compiler
change lands, the item here retires and the module code simplifies.

## The language and the emitter

- **A union constructor cannot read a call's type.** `SqlValue(sqliteColumnInt(...))`
  fails with "the argument's type is unknown" even though the call's type is declared.
  A literal has the same trouble. Workaround: bind the value to a typed local first -
  `val n: Int64 = ...; SqlValue(n)` - which reads fine and always works.
- **`return f(...)` drops the out-parameter slot.** A call whose `Res<T>` return crosses
  the out-parameter ABI, written directly in a `return`, emits without the out slot and
  fails to compile. Workaround: `val r = f(...)` then `return r`.
- **Bare `when` labels need a simple-name subject.** `when (v) { I -> ... }` works for a
  local `v`; `when (cell.value()) { I -> ... }` emits a bare `I` the C++ cannot see.
  Workaround: `when (x.getTypeOf())` with qualified labels (`SmSqlValueTypes.I`), the
  form `stress/unions`' `labelSide` uses.
- **Same-name extension methods confuse the emitter.** With `Query.whereBind(cond,
  value)` and `Query.whereBind2(cond, a, b)` both declared, a call spelling `whereBind`
  with three arguments resolved to `whereBind2`'s signature but emitted `whereBind`'s
  name and symbol - no diagnostic, a C++ error far from the cause. Workaround: exact
  names at every call site (`whereBind2(...)` for two values), and treat an arity
  mismatch on an extension as a silent mis-resolution, not a compile error.
- **A program's own function loses to an imported extension of the same name.** The
  `xml` module originally named its lookup `XmlNode.attr`; `stress/xml-module`, which
  defines its own `attr(name, value): Attribute`, then resolved those two-argument calls
  to the zero-receiver extension and emitted nonsense. Workaround: the module's lookups
  are `attribute`/`attributeOr`. Rule of thumb: do not give an extension a name a
  caller could plausibly define for itself.
- **`for` needs a local as its source.** `for (x in f())` - the container arriving from
  a call - fails to instantiate the iterator (`has no member named iterValues`), for
  both value and pointer forms. Workaround: `val xs = f()` then `for (x in xs)`.
- **A non-generic machine cannot be consumed by `for`.** A source machine declared
  without type parameters (`fun XmlNode.elements(): ..*XmlNode`) has no `iter` member in
  its generated yieldable, so `for (*e in node.elements())` fails; the same machine
  feeds the `linq` operators fine. Workaround: pipelines for machines, `elementList()`
  (a plain `List`) for loops - until a compiler change generates `iter` for every
  yieldable.
- **A by-value parameter never propagates mutations.** A helper taking `out: Str` or
  `out: List<T>` and appending to it mutates a copy; the writes vanish. This is the
  difference between the first draft of the parser (every value empty) and the working
  one. Workaround: the cursor-and-borrow idiom `modules/minui/xmlread.kt` uses - the
  mutable state as `*XmlParser`/`*Str` parameters, lent at the call site with `*value`.
- **Single-statement bodies want braces.** `if (x) return y` parses as an error at the
  `return`; `while (c) d++` the same. Braces everywhere.
- **No leading-dot chain continuation.** A method chain must sit on one line; a
  continuation line starting with `.` is a parse error. Long chains: bind the
  intermediate to a name.
- **Hex literals are not accepted** for `Int` (`0x1` fails to parse); decimal spells
  the sqlite flags.
- **`Res<Unit>` is not usable** (an invalid `Res<void>` in C++ - already known to
  `modules/minui`). Fallible-nothing operations answer `Res<Int>` (a row count, a
  status) instead.
- **`Char` is a signed byte.** `ch >= 128` is tautologically false (clang warns); the
  non-ASCII test is `Int(ch) < 0`, the unsigned read `Int(ch) & 255` (`bench/simse/kit`).
- **Raw strings are backtick strings**, and once one interpolates, every `@` must start
  a name - an error message wanting a literal `@` goes to a `"..."` string. The modules'
  error messages use plain concatenation throughout, which is also what keeps them
  free of interpolation rules.
- **Lambda captures are by value.** An accumulator written inside `forEach` stays
  inside it (reference captures and explicit capture lists are deferred,
  `ai/status.md`). A fold over materialized rows is a plain `for`.

## The `sqlite` module's own hazards

- **The C API's arity is the ABI.** The glue declares each sqlite3 function as a
  function pointer typed by hand; `sqlite3_exec` takes *five* arguments (the fifth is
  `char** errmsg`), and declaring four - calling through a 4-parameter pointer type -
  leaves the fifth register garbage, which SQLite then writes through: heap corruption
  that surfaces two operations later. Every new raw declaration needs the full C
  signature, checked against sqlite3.h, however obvious it looks.
- **The library name is per platform.** The glue loads `sqlite3.dll` on Windows and the
  runtime SONAME `libsqlite3.so.0` elsewhere (the `-dev` package's unversioned `.so` is
  not needed). A platform without one of those names is a platform where the module
  answers `sqliteLastError(null)` on every call - by design: a missing library is a
  `Res` error, never a crash.
- **`Str` returns cross by value, parameters as `const Str&`.** The `res` generator's
  call sites and the hand-written glue must agree; the forward declarations in
  `modules/sqlite/_res.md` are the contract. A `Str`-returning section needs its
  `forward` block filled in - an empty one compiles the bodies but not the calls.

## The published bootstrap's pin

The stress corpus's remaining Linux failures are the bootstrap's age, not the modules':
`http`, `native-invoke`, `native-struct-pointer`, `sockets` and the `minui-*` cases are
Windows-only natives; `compound-pointer`, `destructuring-bind`, `place-reuse` and the
`test-*` cases use language features newer than the published bootstrap. A refreshed
bootstrap retires them (see `docs/building-on-linux.md` for the fixed point).
