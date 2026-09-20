# Language surface decisions (index)

Status: consolidated. The provisional decisions that used to live here have been
folded into the topic specs, which are now normative. This file is kept as a
short index so the decisions remain discoverable from one place.

Nothing here overrides the topic specs; where a topic spec speaks, it wins.

## Where each decision now lives

| Decision | Topic spec |
| --- | --- |
| `Bool` exists; `true`/`false` are keywords | `built-in-types.md` |
| Character literals and their escapes | `built-in-types.md` |
| `Str()` construction and the minimal `Str` API | `built-in-types.md` |
| `List<T>` minimal API (`append`, `insert`, `removeAt`, `removeRange`, `clear`) and indexing through `&List<T>`/`*List<T>` | `containers.md` |
| `Res<T>`/`Opt<T>` inspection (`isOk`, `value`, `error`, `hasValue`) | `core-types.md` |
| `import a.b.c` brings a package into unqualified scope by name; there is no `a.b.c.Name` access form | `specs/modules.md` |
| `package a.b.c` declares a file's package namespace as an opaque dotted identifier | `specs/modules.md` |
| body-less declaration with an `@SmGen` attribute (`@SmGen("cpp"[, symbol])`) | `functions.md` |
| `break`/`continue` are reserved and loop-only | `functions.md` |
| `for` has exactly two forms and iterates whatever has an `iter` (a container, or a machine itself) | `functions.md` |
| `yield e` and `..T`: a body that yields is a state machine | `impl_specs/yield.md` |
| Default parameter values are deferred | `functions.md` |
| Line endings and `;` separate statements | `functions.md` |
| Enum member qualification `EnumType.Member` | `declarations.md` |
| Module-level declaration hoisting; locals are not hoisted | `declarations.md` |
| Automatic dereference through `&T`/`*T` for member/index/call, and for a binary operand | `memory-model.md` |
| Compound assignment (`+= -= *= /= %=`) and the step operators (`i++`, `i--`) update a place in place | `memory-model.md` |
| The binary operators and their precedence (bitwise tighter than a comparison) | `built-in-types.md` |

## Still deferred

These are explicitly deferred and must not be depended on by the parser or the
mirrors:

- default parameter values;
- multiple packages per file, and external-module manifests/versions (see
  `specs/modules.md`);
- the final `native` symbol-naming, linkage, and build integration (see
  `impl_specs/native-interop.md`);
- the precise syntax for declaring and entering an `unsafe` block.
