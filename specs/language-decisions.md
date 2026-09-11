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
| `import a.b.c` imports a module's top-level declarations | `functions.md` |
| `package a.b.c` groups a file's declarations for import | `functions.md`, `declarations.md` |
| `native fun` declaration form and explicit `native("Symbol")` | `functions.md` |
| `break`/`continue` are reserved and loop-only | `functions.md` |
| Default parameter values are deferred | `functions.md` |
| Line endings and `;` separate statements | `functions.md` |
| Enum member qualification `EnumType.Member` | `declarations.md` |
| Module-level declaration hoisting; locals are not hoisted | `declarations.md` |
| Automatic dereference through `&T`/`*T` for member/index/call | `memory-model.md` |

## Still deferred

These are explicitly deferred and must not be depended on by the parser or the
mirrors:

- default parameter values;
- the directory fallback for `import` (used only when no file declares the named
  package); new code should declare packages;
- the final `native` symbol-naming, linkage, and build integration (see
  `impl_specs/native-interop.md`);
- the precise syntax for declaring and entering an `unsafe` block.
