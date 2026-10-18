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

## Phase B — the assignment spelling (sized, not started)

Sweep `out.append(ch)` → `out += ch` and `out.appendStr(part)` → `out += part` in
prelude and compiler sources. The desugaring is the fused shape, so the hot loops
in `intToStr`/`fmtStr` keep their per-iteration cost *if* the fusion treats
statement-local `a += b` as it does `a = a + b` (it does — that is the probe's
emission). **Lists are explicitly out**: `list += item` would need a `List.plus`
that copies per element; `append` stays the in-place spelling there unless a
`plusAssign`-style in-place operator lands first (Phase C).

## Phase C — what would complete the set (deferred, with reasons)

- **`plusAssign` (in-place `+=` for containers)**: a `List<T>.plusAssign(value)`
  desugaring target so `parts += item` appends in place. Needs a sema rule
  (`+=` prefers `plusAssign`, falls back to `a = a + b`) — the one real language
  change in this file, and the key that would let Phase B cover lists too.
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
