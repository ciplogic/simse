# What is implemented, what is not

Keep this file short; the record of what moved and what it cost is
`impl_specs/capability-matrix.md`, and the user-facing plan is
`impl_specs/user-language-roadmap.md`.

## Implemented

Language: values and locals (`val`/`var`, inferred or declared), file-level statics,
`if`/`else` (expression), `when` (subject once, string dispatch; subjectless conditions), `while`, `for` over
`List`/`Array`/`Span` and machines (`..T`, both value and pointer forms), ranges (`a..b`,
`until`/`downTo`/`step`, and `x in c` membership — sugar over prelude `rangeTo`/`contains`
extensions), `break`/`continue`,
`yield` (a state machine — `impl_specs/yield.md`), functions (block or expression body),
extension methods, `data` pure
functions, lambdas (block bodies, by-value capture, the trailing brace form with `it` -
`f(x) { ... }` - and the prelude's `repeat`/`forEach` consumers), reified generics with call inference,
protocols (`protocol` signatures and `when T: P` constraints, checked at instantiation;
static dispatch, no vtables — `impl_specs/protocols.md`),
data classes with methods, data-class inheritance (`: Base` appends the base's fields ahead
of the class's own, and a derived value converts to its base by value, by raw pointer or into
a snapshot `&Base` - `specs/declarations.md`), `operator` functions (`get`/`set` indexers, `compareTo`/
`equals`/`plus`), `union class` (a discriminated union: the implicit `Sm<Name>Types` tag
enum, generated `getTypeOf`/`isOfType`/`get<Field>`/`set<Field>`/`setNone`,
`U()`/`U(value)` construction), enums, `typealias`,
`Opt`/`Res` and `!!`, `null` for handles,
memory operators (`*T`, `&T`, `copy`), string interpolation (`` `n=@n` `` and Kotlin's
`"n is $n"` / `"sum ${a + b}"` in quoted strings), containers and the
string library, modules/packages and `simse.md` manifests, `main(args)`, resources (`_res.md`,
`Resources.get`), source generators (`@SmGen("cpp")`, `@SmGen("kt")`, `@SmGen("res")`,
`@SmGen("native", ...)` on Windows), diagnostics with positions.

Toolchain: `--showLinearRepresentation`, `--showAsync`, `--showBorrow`, `--showEscape`,
`--no-concat`, `--no-borrow`, `--no-escape`, `--profile`, `--version`, the stress corpus, the
bootstrap fixed point, and the iterate loop that runs them (`bun tools/iterate.js`, `--full`
before committing).

## Deferred (specified or planned, not implemented)

- **`object` declarations** (file-level statics are done; `specs/statics.md`,
  `impl_specs/statics.md`). This is what the RTL needs to move per-type statics out of C++.
- **Async task machinery**: `suspend` is parsed and colored, but a suspending body is not yet
  lowered to a task (`impl_specs/async.md`).
- **`Dictionary` iteration** (a `for` over `d.keys()` works today); `for` is over
  `List`/`Array`/`Span`/machines/ranges today.
- **Reference captures, explicit capture lists, `unsafe`/raw-pointer escape rules.**
- **Attributes on types/fields/parameters/statements, stacked attributes, per-instantiation
  generators** (`impl_specs/generators.md`'s "Deferred": `@Json`, enum-to-string, ...).
- **Interfaces/virtual dispatch, method overriding (a base method cannot be replaced; the
  declaration is a diagnostic), default parameter values, `when` pattern
  labels (`is T`, `in 1..5`), multiple packages per file, external-module manifests.**
- **Feature work the user roadmap orders**: closed unions with exhaustive `when`, `Set`,
  byte buffers, JSON codegen, sockets/HTTP polish, Linux/macOS, user FFI.

## Open engineering notes

- **Sema is quadratic in one file's declaration count** (`collectGlobal`'s copies,
  `buildVisible` per file, per-call overload scans in `src/sema/Sema.kt`); many small files
  stay linear. Fix when a real workload needs it.
- **`SmDictionary` hit lookups** are ~1.8x slower than the `std::unordered_map` it replaced
  (misses, iteration and deep copies are faster); the cold-path suspects are the bucket
  indirection and `SmallVector::operator[]`'s inline/heap branch.
- **Lambda typing is closed at the contract**: a lambda used against a callable type must
  agree with it - its own parameter annotations and its body's proved answer against the
  position's - and a disagreement is the compiler's report at the lambda, not a C++
  conversion error at the call. A lambda declared in a yielding body is a machine field
  typed with its closure class, defined on the fly before the machine struct
  (`impl_specs/yield.md`, "A lambda as a machine field"). Generic type aliases are resolved
  with their arguments substituted (`Row<Int>`, `Row<T> = List<T>`, is `List<Int>`) in every
  position that walks an alias (a member call's receiver and declaration, an expected
  callable, a view parameter), one shared walk (`semResolveTypeAlias`). The remaining
  boundary: a lambda nested *inside another* lambda in a machine body captures only what
  the enclosing closure's frame carries (the same depth rule any body has), and `yield`
  inside a lambda body is not a shape the language gives meaning to.
- **The linear form is fully typed: zero `?` slots** (typed-IL step 4,
  `impl_specs/typed-il.md`): a `null` and a function name are typed by the position they
  stand in - the other comparison operand, the parameter, the field, the return, a
  construction's element, an index store's element - and a temp whose single definition is
  its spelling (`SetVar_Null`, `GetStatic`) folds at its use, so the C++ keeps the inline
  `nullptr` / `compareLessThan` / `SmOptTypes::Value`. A machine receiver can be spelled
  through its protocol (`fun Yieldable<*T>.toList<T>()`, with `protocol fun
  Yieldable<TStateMachine, TItem> TStateMachine.tryAdvance(current: *TItem): Bool` the
  prelude's name for the machine contract) and the IL carries that name - the `*?`
  receiver is gone. Every `for` steps through that one method too: the desugar's out slot
  (`var _sm_item<n>`) takes its type from its machine, and a packed call's element type is
  its elements' own, so `for (w in listOf("a", "b"))` is typed end to end (the published
  compiler could not compile it). The promotion trace is truthful (each loop walked once, in
  scope; every hoist refusal names the gate that failed) - `--diagnostics sema` is the
  measurement to trust.
- **The linear form's next steps are decided and in progress**:
  `impl_specs/linear-form-plan.md` is the measured list of what is still wrong with the
  compiler's middle end now that it is typed - 94 decisions keyed on a name, the type rule
  written in two to six places, 1,859 operands a callee writes that the IL records as
  reads, 1,138 typed single-use temporaries that never fold - and the order to fix them in.
  Its three open decisions are taken, by default: `advance()`/`current` stay and the
  machine's step folds under them; `&place` is the out argument for every callee, by
  signature; type checks become errors as soon as they are clean. Step 0 is in: the dump,
  the `sema` trace and the walk's own corpus tell the truth - `iterate --full` runs the
  corpus with `--for-walk` as well, and its first whole run found an indexed-range
  miscompile. Step 1 is in, report-only: the compiler compares assignability under
  `--check-types` (`src/sema/TypeCheck.kt`) - argument against parameter, initializer,
  assignment and return against their declared types, member against its class - and on
  the corpus and the self-transpile it reports nothing; the five ordinary type mistakes the
  plan's small programs show now report at their own line in the language's names instead
  of in cl.exe's generated code. Step 1b - the flip - is next.
- The single amalgamated translation unit is 4.07 MB of C++ (118,039 lines, measured
  November 2026 - it was ~190 KB when this note was written); revisit if the compiler grows
  much.

`docs/state-of-the-field.md` lists the rough edges a *program author* hits, with workarounds.
