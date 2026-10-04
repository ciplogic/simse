# What is implemented, what is not

Keep this file short; the record of what moved and what it cost is
`impl_specs/capability-matrix.md`, and the user-facing plan is
`impl_specs/user-language-roadmap.md`.

## Implemented

Language: values and locals (`val`/`var`, inferred or declared), file-level statics,
`if`/`else` (expression), `when` (subject once, string dispatch), `while`, `for` over
`List`/`Array`/`Span` and machines (`..T`, both value and pointer forms), `break`/`continue`,
`yield` (a state machine — `impl_specs/yield.md`), functions, extension methods, `data` pure
functions, lambdas (block bodies, by-value capture), reified generics with call inference,
data classes with methods, `operator` functions (`get`/`set` indexers, `compareTo`/
`equals`/`plus`), `union class` (a discriminated union: the implicit `Sm<Name>Types` tag
enum, generated `getTypeOf`/`isOfType`/`get<Field>`/`set<Field>`/`setNone`,
`U()`/`U(value)` construction), enums, `typealias`,
`Opt`/`Res` and `!!`, `null` for handles,
memory operators (`*T`, `&T`, `copy`), string interpolation (`` `n=@n` ``), containers and the
string library, modules/packages and `simse.md` manifests, `main(args)`, resources (`_res.md`,
`Resources.get`), source generators (`@SmGen("cpp")`, `@SmGen("kt")`, `@SmGen("res")`,
`@SmGen("native", ...)` on Windows), diagnostics with positions.

Toolchain: `--showLinearRepresentation`, `--showAsync`, `--showBorrow`, `--no-concat`,
`--no-borrow`, `--profile`, `--version`, the stress corpus, the bootstrap fixed point, and the
iterate loop that runs them (`bun tools/iterate.js`, `--full` before committing).

## Deferred (specified or planned, not implemented)

- **`object` declarations** (file-level statics are done; `specs/statics.md`,
  `impl_specs/statics.md`). This is what the RTL needs to move per-type statics out of C++.
- **Async task machinery**: `suspend` is parsed and colored, but a suspending body is not yet
  lowered to a task (`impl_specs/async.md`).
- **Range and `Dictionary` iteration**; `for` is over `List`/`Array`/`Span`/machines today.
- **Reference captures, explicit capture lists, `unsafe`/raw-pointer escape rules.**
- **Attributes on types/fields/parameters/statements, stacked attributes, per-instantiation
  generators** (`impl_specs/generators.md`'s "Deferred": `@Json`, enum-to-string, ...).
- **Interfaces/virtual dispatch, method overriding, default parameter values, `when` pattern
  labels (`is T`, `in 1..5`), multiple packages per file, external-module manifests.**
- **Feature work the user roadmap orders**: closed unions with exhaustive `when`, static
  protocols, `Set`, byte buffers, JSON codegen, sockets/HTTP polish, Linux/macOS, user FFI.

## Open engineering notes

- **Sema is quadratic in one file's declaration count** (`collectGlobal`'s copies,
  `buildVisible` per file, per-call overload scans in `src/sema/Sema.kt`); many small files
  stay linear. Fix when a real workload needs it.
- **`SmDictionary` hit lookups** are ~1.8x slower than the `std::unordered_map` it replaced
  (misses, iteration and deep copies are faster); the cold-path suspects are the bucket
  indirection and `SmallVector::operator[]`'s inline/heap branch.
- **Lambda typing stays conservative** around bodies and generic type aliases; a body/return
  mismatch can surface as a C++ error instead of a Simse diagnostic. Deeply nested generic
  type aliases in expected callable positions are the other known weak spot.
- The single amalgamated translation unit (~190 KB of C++) is fine today; revisit if the
  compiler grows much.

`docs/state-of-the-field.md` lists the rough edges a *program author* hits, with workarounds.
