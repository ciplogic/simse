# Language surface decisions (provisional)

Status: provisional - consolidate into the topic specs when stable.

This file records provisional, normative decisions about constructs that the
`.simse` mirrors and the parser already rely on but that the topic specs do not
yet state. Each decision names the topic spec it logically belongs to. When a
topic becomes stable, its decisions are folded into that spec and removed from
this file. Nothing here introduces a new topic; these rules only pin down
underspecified surface details and do not override the topic specs where those
specs already speak.

## Built-in types

### `Bool`

Decision: `Bool` is a built-in type with exactly two values, `true` and `false`.
`true` and `false` are keywords.

Belongs to `built-in-types.md`.

### Character literals

Decision: `'c'` denotes a `Char` (`Int8`) value. The supported escapes are
`\n`, `\r`, `\t`, `\0`, `\\`, `\'`, and `\"`. An unknown escape is an error.
`Char` is not a Unicode scalar.

```text
val newline: Char = '\n'
val quote: Char = '\''
val letter: Char = 'a'
```

Belongs to `built-in-types.md`.

### `Str` construction and minimal API

Decision: `Str()` constructs an empty string. The minimally supported operations
are:

- `size(): Int`;
- indexing `s[i]`, for both reading and assignment;
- `append(ch: Char)` and `append(s: Str)`;
- `clear()`;
- `resize(n: Int)`; and
- `data()`, which returns a NUL-terminated buffer for C interop.

Belongs to `built-in-types.md`.

## Containers

### `List<T>` minimal API

Decision: the minimally supported `List<T>` operations are:

- `size(): Int`;
- indexing `list[i]`, for both reading and assignment, where assignment requires
  a mutable variable;
- `append(value: T)`;
- `insert(index: Int, value: T)`;
- `removeAt(index: Int)`;
- `removeRange(start: Int, end: Int)`, which removes the half-open range
  `[start, end)`; and
- `clear()`.

Indexing and member calls are permitted directly on a `&List<T>` and on a
`*List<T>`, with automatic dereference.

Belongs to `containers.md`.

## Core types and error handling

### `Res<T>` and `Opt<T>` inspection

Decision: `Res<T>` exposes `isOk(): Bool`, `value: T`, and `error: Str`.
`Opt<T>` exposes `hasValue(): Bool` and `value(): T`, in addition to the
constructors stated in `core-types.md`.

Accessing `value`, `error`, or `value()` on an empty (`Opt`) or failed (`Res`)
value is an error-path operation and must be guarded by a test of `isOk()` or
`hasValue()`.

Belongs to `core-types.md`.

## Imports

### `import`

Decision: `import a.b.c` imports all top-level declarations of the module
identified by the dotted path.

Exact file-versus-package resolution is deferred.

Belongs to `functions.md`.

## Native functions

### `native fun`

Decision: a declaration `native fun name(params): Ret` introduces a function with
a Simse type/signature but no body; its implementation is provided by hand-written
C++. An optional explicit-symbol form `native("Symbol") fun name(...)` may be used
when the source name and the C++ symbol differ. Native bodies are absent from
Simse.

```text
native fun readFile(path: Str): Str
native("FileUtils::readFile") fun readFile(path: Str): Str
```

Belongs to `functions.md`; see `impl_specs/plan-to-selfhost.md` for the native
fallback boundary.

## Declarations

### Enum member qualification

Decision: enum member access is qualified as `EnumType.Member`. An enum remains a
distinct type whose runtime representation is `Int`.

Belongs to `declarations.md`.

### Declaration hoisting

Decision: module-level declarations (functions, `data class`, `enum`, and
`typealias`) are **hoisted**. They are visible throughout the module regardless
of textual order, like Kotlin, Java, or C#. Declarations may be referenced before
their textual definition, and mutually recursive functions need no source-level
forward declaration. Methods within a class body are likewise order-independent
relative to one another and to the class's fields. Local variables are **not**
hoisted: a local is visible only from its declaration onward, so a local
use-before-declaration is an error.

Belongs to `declarations.md`.

## Control flow

### `break` and `continue`

Decision: `break` and `continue` are reserved keywords and are valid inside
`while` and `for` loops.

Belongs to `functions.md`.

## Parameters

### Default parameter values

Decision: default parameter values are not supported for now; this is deferred.

Belongs to `functions.md`.

## Statement separation

### Line endings and `;`

Decision: a line ending (the `EndOfLine` token: LF, CR, or CRLF) separates
statements; an explicit `;` is optional and equivalent.

Belongs to `functions.md`.

## Deferred items

The following are explicitly deferred and must not be depended on by the parser
or the mirrors:

- default parameter values (see above);
- file-versus-package resolution for `import` (see above);
- the final `native` symbol-naming, linkage, and build integration (see above).
