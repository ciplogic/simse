# Reusing values the body already computed

Evidence: `ns12_semaBuiltinGenericArity` in `cppsrc/simse_bootstrap.cpp` (the `when (name)` over a
`Str*`, ~L41988). It performs **eight deep `Str` copies of one expression**:

```cpp
_sm_when1_n = name->size();
_sm_expr1 = _sm_when1_n == 4;
if (!(_sm_expr1)) goto L7;
_sm_base1 = *(name);                          // a Str copy
_sm_expr1 = _sm_base1 == __sm_stringTable[575];
if (_sm_expr1) goto L1;
L7:;
_sm_expr1 = _sm_when1_n == 5;
if (!(_sm_expr1)) goto L6;
_sm_base2 = *(name);                          // the same read, a second copy
...
```

Two causes, and they are worth separating because they are fixed in different places.

**1. The `when` desugaring re-emits the subject once per arm.** `when` is desugared in the parser
(like `for`), so `when (name)` becomes `if (name == lit) ... else if (name == lit) ...` with the
subject *spelled once per arm*. The expression lowering then hoists each occurrence on its own, into
its own temporary. Note that the length discriminator **is** hoisted once (`_sm_when1_n =
name->size()`), so the shape this pass wants already exists in the same body - it is only the value
read that is repeated.

**2. Every hoisted temporary is a `Str`, so every hoist is a deep copy.** `*(name)` has type `Str`,
so `_sm_baseN = *(name)` copies the string (the `Str` is `SmallVector<24, Char>` - a heap allocation
past 23 bytes), while the comparison against `__sm_stringTable[k]` is already free, because a
literal at a site *is* a `StrView` and `Str`/`StrView` comparisons have direct overloads
(`cppsrc/rtl/strview.hpp`). The copy is pure waste: the value is only ever compared.

## The three fixes, in the order I would do them

**A. The `when` subject is already bound once - and the rule that skips it is why the copies are
there.** `parseWhen` (`cppsrc/parser/Parser.kt`) binds `var _sm_when1 = <subject>` and makes every
test read that - *unless* the subject is a **place**, in which case it deliberately skips the
template and re-reads the subject per test (`whenSubjectIsPlace`, and the rationale is recorded at
its definition: the template would *copy* a `Str` subject - a heap copy past the inline buffer - on
every execution, which dwarfs the tests).

That trade is right for a `Str` **local**: a local is already an IL slot, so it can be an operand
directly and re-reading it costs nothing. It is **not** right for a `Str*` subject. `*(name)` is
*not* a slot, so the IL's own rule - "one instruction is one operation, every operand is a slot of
the declared frame or a constant" - materializes it into a temporary per arm, and that temporary is
a `Str`, so each arm is a deep copy. A parameter of **pointer type** is therefore a place whose
re-read is not free.

The parser cannot tell a `*Str` parameter from a `Str` one; that is a *type*, and types arrive
after parsing. So the fix wanted is type-aware, not a parser tweak: bind the subject as a value
exactly when its type is a pointer or a handle (where re-reading costs a copy), and keep the place
shortcut when it is a value type (where it costs nothing). Doing it in the parser instead - always
using the template, which the flag below already does - trades N copies for 1 on a pointer subject
yet makes every `Str`-local subject pay a copy it does not pay today. Two sides, so it is a
measurement before it is a change.

**And that measurement already exists:** `--when-copy-subject` *is* this A/B - `cppsrc/compiler/Driver.kt`
takes it and `whenCopySubject()` is read by the one predicate that decides - and it is documented at
its flag as "the A/B for the copy, not a mode to ship". Running the corpus and the self-transpile with
and without it prices both sides before any of this is written.

**B. Global simple expression elimination** (`cppsrc/optimizations/usedef/`). The framework is
already there - `UseDefs.kt`, `DeadLocals.kt`, `DeadStores.kt`, `MergeLocals.kt` - with `FoldExprs.kt`
for the expression shape, so this is a new pass beside them rather than new machinery. The property
is **invariance**, which is what "a parameter, or something unchanged from the beginning of the
function" names precisely: an expression whose operands have no write between its first occurrence
and the second. `*(name)` qualifies exactly when nothing in the function writes through `name`; note
that `name->size()` is *already* commoned in this very function, so the analysis is close to what is
needed - the load is the piece missing it.

Two shapes to keep in mind so the pass stays sound: a load through a `*T` is invariant only if the
function never writes through that pointer (and nothing it calls can, which closed-world makes
checkable the same way the coloring and `!!` passes check their names); and reusing a `Str`-typed
result means reusing a *copy* unless the reuse is a borrow, which is where B and A meet - A's view
is what makes B's reuse free.

**C. The branch shape (`if` / `goto` ordering).** The user asked for `ifTrue cond -> then; goto
else; then:` rather than the negated, branch-swapped form a peephole produces. Worth stating its
price before doing it, because it is the opposite trade to the other two: it **adds** an instruction,
and it changes the emitted C++ *everywhere*. That means the `stress/*/expected.cpp` goldens must be
regenerated (by hand - `--update` deliberately does not rewrite them), the published bootstrap
refreshed, and a line added to the docs, since `README.md` and `docs/how-it-works.md` currently say
the output "is not meant to be read like prose". It is a readability change, not a speed one, and it
should be recorded as such.

## Measuring, and the corrected order

The `when`'s copies are *a* symptom of B, not a separate fix: with B in place the eight reads
collapse to one, whatever the desugaring was able to bind. So the order is **measure A's two sides,
then B, then C**:

- **A first, as a measurement only**: run the corpus and the self-transpile with and without
  `--when-copy-subject`. That is the number that decides whether the type-aware version of A is
  worth writing, and it costs an afternoon rather than a compiler change.
- **B** is the general fix and subsumes this instance. Count the `_sm_baseN = *(name)` sites in the
  amalgamation before and after - the copies are the countable symptom - and profile the
  self-transpile (`--profile`), which is the published number.
- **C** last, and priced as a readability change: it adds an instruction and touches the emitted
  C++ everywhere.
