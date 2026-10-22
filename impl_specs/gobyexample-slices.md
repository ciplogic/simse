# Plan: following "Go by Example" in Simse

User decision (Oct 2026): teach the tutorial by porting it; **build the compiler slices**, not a
translation guide first. No `defer` — destructors cover resource symmetry with Go (guide note
only). Committing each slice on its passing loop; committing is committed by the leave
`ai/contributing.md` (release loop: `./build.bat --release && bun tools/stress.js &&
bun tools/bootstrap.js`).

## Already verified with probes (no work needed)

- **Closures lesson works** (`&&`-box capture arenas` shortcut): `fun intSeq(): () -> Int`
  with `val i = &Int(0)` captured by use runs the necessary cleanup `1 2` clean exit; not
  counter-checked `probe/src2` (prints `1 2 3 1` in full Go form when wrapping `Cell`).
  Zero-param lambdas and the `() -> Int` callable type both work; `return () -> { ... }`
- **Pointers work** (`zeroptr`-form *and* `&Int(42)`) to compiled in `probe-box`: `val i =
  &Int(42)`, `val p: *Int = *i`, `(*p) = (*p) + 1` -> `43`. Gotcha: a statement starting
  with `*` after another statement parses as a multiplication continuation (stress
  `collections:122`); fix the guide to teach `(*p) = ...` or first-in-block.
- **Variadic works** already: a trailing `List<T>`/`*List<T>` parameter packs trailing call
  arguments (`specs/functions.md`, "Packing the trailing arguments").
- Maps: `insert`/`get: Opt`/`has`/`getPtr`/`remove`/`clear`/`size`/`keys()` all exist
  (`stress/collections:112`); still missing ergonomics -> slice 3.

## Slice 1 (was "scalar box spelling") — CLOSED

`&Int(42)` removed it increased probes similarly: the only parse failures seen were the
documented statement-continuation gotcha, not the spelling. No parser change.

## Slice 2 — destructuring bind `val (a, b) = e` + `_` discard

`a, b := vals()` is Go's most repeated bind. Desugar, not a new type:

```kt
val (a, b, _) = vals()     //  ->  val _sm_destr1 = vals()
                           //      val a = _sm_destr1.<field0>
                           //      val b = _sm_destr1.<field1>   // `_` binds nothing
```

Everything below verified by reading the code; start here and work in order:

1. **Parser** — `src/parser/ParserStmt.kt`
   - `parseStmtInto` (34-51): intercept `"val"`/`"var"` when `peek(1).text == "("` (the way
     `for`/`when` are intercepted today), like `parseFor` (389-475).
   - New `parseDestructuringDecl(out)`: consume `val`/`var`, `expectText("(")`, collect
     binders (`expectName()` accepts `_` already — the lexer marks `_` as an identifier,
     `Scanner.kt:137`; it is NOT in the reserved table), comma-separated,
     `expectText(")")`, **require** `= e` (`parseExpr(0)`), then emit ONE
     `StmtVarDecl` node: name `_sm_destr<N>`(via `nextTemplateId` counter, like
     `parseFor` 439-442), `IsVar` as written, attr `Destructures` = binders joined by
     `","` (`a,b,_`), `Init` child = the init, NO Type child. No `for`-style fan-out
     bodies in the tree.
   - `varDeclNode`/`memberExprAt` builders in `src/parser/ParserNodes.kt` are the builders
     the binder nodes will use (SemInfer has to build them in step 3 via the sema-side
     node builders, not the parser ones — or import) lookup mira.
2. **Attribute** — `modules/compiler/astxml.kt`, `AstNodeAttributeKind` (55-141): add
   `Destructures` **appended last** ("Appended last, so the values already in use do not
   move" — every past enum addition copies the comment).
3. **Analyzer (sema validation + scope declarations)** — `src/sema/SemaAnalyze.kt`
   `analyzeStmt` `StmtVarDecl` arm (694-719): if `Destructures` attr present:
   analyze init as usual; get type at line 708-710's `this.exprType(init)`'; else diag
   "cannot infer the destructured type").
   Resolve the class: `decl = this.types.getPtr(baseName)` — resolveAlias —
   `decl.name == AstNodeKind.DataClass`; else diag `'@Type' is not destructurable`.
   Field list: `semAllFields(*this.types, *decl)` (`src/sema/Inherit.kt:65`) — base fields
   come FIRST (inherited prefix order) exactly as they are in the source.
   Count check: binders == fields, else diag `pattern '@a,b' has @n elements, '@Type' has
   @m fields`.
   Then `declareValue` for the temp node as normal, plus `declareValue` on each binder
   (skip `_`): (name, isVar, true, fieldType) — so uses in the rest of sema type-check.
4. **SemInfer splice (typing + fan-out)** — `src/sema/TypeInferExpr.kt`
   - `SemInfer.stmts` (276-303): in the statement loop, when the statement is a
     `StmtVarDecl` with a `Destructures` attr call a new `SemInfer.destructureStmt(stmt)`
     that returns `List<AstXmlNode>` and `out.appendEach`-style gets appended there;
     other statements still go through `this.stmt(...)`.
   - `destructureStmt`: infer init; if empty, return `[stmt]` unchanged (lowering's
     "cannot infer diagnostic"). Look up the class decl the same way SemInfer does it in
     `infer(ExprMember)` (`src/sema/TypeInferCall.kt:680-697`: `facts.types.getPtr(baseName)`,
     `semFindField` for a name test — but here via positional `semAllFields(*this.facts.types,
     *decl)`). Base fields again first.
   - Build and append: first the temp node processed by `this.stmt(<temp>)` (typing =
     ordinary decl; the `Destructures` attr stays on it and is inert past SemInfer), then
     per binder (skip `_`): a `StmtVarDecl` node with `Name`/`IsVar`/pos attrs, a `Type`
     child = the field's own `Type` child (so linear gets a typed decl directly), and
     `Init` = `ExprMember(name=tempName, receiver=ExprName(tempName))` — then call
     `this.stmt(<binder>)` on each (this `mark`s it so later statements see the name, and
     spells the Type child role via `semWithType`).
   - No linear/IL change: binder decls lower through `IlExtractor.statement`'s normal
     `StmtVarDecl` arm to `DeclareInit` + `GetField(slot, base, "field")`
     (`src/linear/LinearFormStmt.kt:44-75`, `509-522`). The IL is built from the
     SemInfer-spliced list (`emitBodyAt(..., finalBody, ...)` at `CgEmitFn.kt:430-446` →
     `ilExtractUnit` → `extractor.run(body)`, `LinearForm.kt:328-344`), not from the
     original decl, so the splice is visible everywhere.
5. **Stress case** — `stress/destructuring-bind/{src/main.kt, expected.stdout}` + a
   mismatch diagnostic case (`stress/diagnostic-*` form): `val (a, b) = vals()` prints
   `3 7`, `_, c` style, `var` pattern, inherited data class (`Point3D : Point2D` → first
   binder is the base field).
6. **Docs** per `ai/contributing.md`: `docs/language-tour.md`, `ai/language.md`
   (`/Values and types`), `specs/functions.md` or `specs/declarations.md` "Destructuring
   bind" para; `docs/state-of-the-field.md` vocabulary line if graded.

Semantics notes: one value copy into the temp + one field copy each (Go copies once;
correct, slightly less optimal — a later optimization can fuse the temp out when the
init is a direct call). Generics data classes NOT part of the slice (analyzer
diagnoses); union classes not destructured (`get<Field>`-style still first-class though it
dictions, id sectionally accessible). `_` stays a normal identifier outside the
pattern (`val _ = e` keeps declaring a local named `_`).

## Slice 3 — Dictionary ergonomics: `d[k] = v` + `for (kv in d)`

Using-check (`stress/collections:112`): all the API exists; missing is syntax and
iteration.

- `d[k] = v` sugar for `d.insert(k, v)`; read `d[k]` → panic on absent key (Kotlin
  semantics): `get(k).value()` on a missing key panics or asserts "missing key '@k'" —
  teach `get`/`getPtr` as the safe path. Implement as prelude extension operators
  (`operator fun Dictionary<K, V>.get(key: K): V`, `.set(key, value): Unit`), or emitter
  special-casing keyed on `TypeGeneric` "Dictionary — check which the emitter's native
  extension table (Dictionary.getPtr etc. are already native extensions,
  `impl_specs/rtl-abi.md`) prefers.
- **`for (kv in d)` — one binding to the live entry, not two separate variables** (user
  explicitly requested: no `for (k, v) in d`). Shape to design against the RTL:
  machine yielding the value place — `Dictionary<K, V>.entries(): ..*Entry-ref` —
  where entry exposes `.key` and `.value` where `.value` is a pointer (the SmDictionary
  row). SmDictionary stores row slots per entry (state-of-the-field: one row per
  entry, cached hash + chain link beside key and value), so walking rows in native
  gives stable `.value` places without building the whole tree (invalidation rule:
  places valid until the next `insert`/`remove`/`clear`, same contract as `getPtr`).
  Careful: a data class with a raw-pointer field is illegal (`specs/containers.md`,
  "Aliasing"); see what `*List<T>` iter does (`src/rtl/Span.kt`, `Span.iter(): ..*T`)
  because `for (*x in c)` "binds the place" already exists — the entry machine likely
  mirrors that machinery. The existing `(x, i)` index form must keep working on top.
- Stress case + docs (same doc set).

## Slice 4 — `Printable` prelude + `println` for containers/enums

State-of-the-field "missing #4": the protocol mechanism exists, the prelude set does not.

1. Prelude protocol `Printable` (`src/rtl/*.kt` as rtl prelude source): `toString(): Str`
   member; scalars satisfy it structurally.
2. `println` overload `when T: Printable` — routes to the existing std print machinery
   (check how `println` is emitted today per-type, `src/rtl/_res.md`; don't duplicate).
3. debug printing for containers in Simse: `show(values: List<T>) when T: Printable`
   (linear loop over `spanOf` / `iterValues` building a `Str` via `fmtStr("")`).
4. enum member name printing: implicit `toString(): Str` lowering at the enum declass
   (currently the guide tells users to write `when`-based `label()`,
   `docs/state-of-the-field` "rough" table); data-class field iteration print if cheap.
5. Float printing (shortest round-trip) is adjacent but separate — skip unless the
   slice under-runs.

## Not followable (by design — declared in every guide)

- **Concurrency block**: goroutines, channels, select, timers, tickers, worker pools,
  WaitGroups, rate limiting, atomics, mutexes, stateful goroutines, context, signals.
  Simse has no threads by design. Full accepted.
- **Defer/Recover**: no exception unwind (`panic` exits via `std::_Exit`); user decision:
  destructors (`ref class` with `unInit`) are the resource-symmetry story.
- **Interfaces as values**: protocols are static; no dynamic dispatch (`ai/status.md`).
- **`switch`/`if x := ...; y` statement forms**: `when` covers Go's no-condition and
  multi-arm switch; if-initializer binds first. Guide-level translation.

## Remaining after the slices (candidate steps, not committed)

- `stdlib` micro-modules for the tutorial's tail: `time` (epoch/millis),
  `random` (seeded xoshiro), file WRITE/paths/dirs complements of reading (onebrc
  proves reads only), env vars, URL parse, `base64`/`hex`/`sha256` (blocked on the
  bytesafe-buffer roadmap item #6).
- 3.2 smaller niceties uncovered while porting: `maps.Equal` (handwritten until a
  linq helper lands), float-format verbs, map literal syntax
  (`dictionaryOf("foo" to 1, ...)`).
- The full "Simse by Example" port (high value; unblocks nothing code-wise).
- Two-build rule check: every parser-visible change above compiles the compiler's own
  sources fine (they never use the new syntax), so one loop suffices unless the enum
  order shift regresses a prelude body's emission — check `bootstrap.js` catches it.
