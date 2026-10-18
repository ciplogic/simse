# The operator surface: making container code read one way

Status: plan — Phase A implemented, B and C sized. Companion to `prelude-surface.md`
(the compiler/prelude split); this one is about the *program-facing* surface.

The premise: the language already has the operator conventions (`specs/functions.md`)
— `get`/`set` for `[]`, `compareTo`/`equals`/`plus` — and `+=` already desugars
through `+` into the **fused** in-place append (`resize` + cursor write, verified by
emission: `out += "b"` is one resize and one byte store). What is *not* uniform is
which types use them: `Span` has `operator get`/`set`, `Str` was reached by C++
subscript accident (`SmString::operator[]`, no declaration — the checker's
permissiveness papering over it) and spelled `charAt(...)`, and string-building code
spelled `append`/`appendStr` where `+=` is both legal and efficient.

## Phase A — the indexer, uniform (done)

- `charAt`'s native declaration is renamed to `operator fun get` (the `@SmGen`
  symbol `simse_str_charAt` unchanged — symbol reach is by name of the symbol, so
  the shared `get` name cannot drag the section anywhere). `s[i]` on `Str` now
  resolves through the operator system like `Span`'s, not through C++.
- `operator fun Str.set(index: Int, value: Char)` joins it — one body, writing
  through `strBytes` the way `Span.set` writes through `ptr`. `s[i] = ch` works;
  the edit-style loops stop needing `setBytes` for single bytes.
- `StrView.charAt` (an `at` alias) is deleted: `Span`'s `get` already serves `[i]`.
- The whole tree sweeps `.charAt(x)` → `[x]` — 82 sites, paren-aware.
- A missed sweep site degrades gracefully: the checker's permissiveness falls back
  to the C++ subscript, same unchecked semantics as before.

## Phase B — the assignment spelling (attempted, rejected as a textual sweep)

`out += ch` and `out += part` are verified efficient by emission (the fused
resize-and-write; `Char` operands included). But the sweep of `append`/`appendStr`
call sites to `+=` **cannot be done textually on this tree**, for three concrete
reasons the attempt found:

1. **Code-as-text files** — the emitters and generators build Simse or C++ inside
   string literals (`JsonGen`, `IlEmit`, the profiling prologue); a rewrite that
   understands Simse string literals still corrupts text that *generates* string
   literals, because the two nest.
2. **`*Str` pointer receivers** — `jsonWriteQuoted(out: *Str)`'s `out.append('"')`
   would have to become `*out += '"'`; sweeping it to `out += '"'` assigns the
   pointer. Telling the two apart needs types, not text.
3. **`List<Char>` collisions** — `.append('x')` is legal on both, so char-literal
   sites are only safe in files the type never appears in.

The way through is one of: a type-aware refactor tool (none exists), a manual
file-by-file pass outside code-generating files — or doing **Phase C's `plusAssign`
first**, which subsumes the sweep's purpose: `+=` resolving to the in-place native
(`simse_str_appendStr`, `simse_list_append`) is both the optimal string path and
the one that covers lists, leaving nothing to migrate for spelling's sake.

## The handle rule (landed)

**An operator on a handle applies to its pointee** - the rule every value position
already gives a handle (`print(x)` on a `&Str` prints the text), now stated for
compound assignment: `counter += 1` on a `counter: *Int` reads and writes through the
pointer (`LinearFormStmt`'s `assignCompound`, the params' declared types from
`ilFunctionFor`), because the statement position cannot even parse the explicit
`*p += x` spelling. Never pointer arithmetic - the language has none. Pinned by
`stress/compound-pointer`.

## Phase C — what would complete the set (partly landed)

- **`plusAssign` (in-place `+=`) — LANDED for value targets**: `+=` travels as its
  own op into the IL and the emitter resolves a matching `plusAssign` declaration
  before folding to the binary (`IlEmit`'s `+=` arm). The prelude declares it as
  native aliases over the append symbols — both `Str` spellings name ONE C++ symbol
  whose overloads C++ picks by the right operand's type (the same-name trick
  `spanOf` avoids, safe because the declarations share the symbol), and
  `List<T>`'s is the template. So `out += part` and `parts += item` are the
  in-place append — no copy, no fusion dependency — and `n += 1` falls back to the
  binary fold with exactly yesterday's emission. Pinned by
  `stress/compound-plus-assign`. **The handle-typed parameter landed with it**:
  `out: *Str; out += x` lowers to the synthetic `out.plusAssign(x)`, and the
  emitter's own native resolution adapts the pointer receiver (`(*out)`) the way
  `out.append(ch)` always had - the in-place append through out-parameters for
  strings, chars and lists alike, while a `*Int` keeps the scalar fold (no
  `plusAssign` matches, the fallback's point). The decision lives where the
  natives table is: `ilFunctionFor` unifies each handle parameter's pointee
  against the `plusAssign` declarations into `plusAssignParams`, and the
  extractor's handle arm reads it (lambdas inherit the enclosing set).
- **`in` / `contains` as an operator**: `x in parts` → `parts.contains(x)`.
  Sugar only; `contains` exists.
- **Ranges (`..`) as index slices** (`s[a..b]` for `substr`): stays deferred with
  `impl_specs/` range work (`ai/status.md`'s deferred list).
- **Properties (`s.length`)**: no property syntax is planned; `size()` is the
  spelling.

## The rule

Same shape as `prelude-surface.md`'s: **an operator convention is the surface, a
method name is an implementation detail.** When a new type joins (`ByteArray`,
`Set`), it declares the operators and every `[]`/`+`/`+=` site reads the same —
the compiler synthesizes the call either way, and no per-type spelling leaks into
program code.
