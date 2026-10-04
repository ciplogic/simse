# Reusing values the body already computed

Evidence: `ns12_semaBuiltinGenericArity` in `src/simse_bootstrap.cpp` (the `when (name)` over a
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
literal at a site *is* a `StrView` and the comparison is the prelude's view `equals`/`compareTo`
(`src/rtl/StrView.kt`), which reads both operands in place. The copy is pure waste: the value is
only ever compared.

## The three fixes, in the order I would do them

**A. The `when` subject is already bound once - and the rule that skips it is why the copies are
there.** `parseWhen` (`src/parser/Parser.kt`) binds `var _sm_when1 = <subject>` and makes every
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

**And that measurement already exists:** `--when-copy-subject` *is* this A/B - `src/compiler/Driver.kt`
takes it and `whenCopySubject()` is read by the one predicate that decides - and it is documented at
its flag as "the A/B for the copy, not a mode to ship". Running the corpus and the self-transpile with
and without it prices both sides before any of this is written.

**B. Global simple expression elimination** (`src/optimizations/usedef/`). The framework is
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

The mechanism B needs - per-call purity, effect classes and value numbering - is written out under
"Purity, effects and value numbering" below; the pass itself belongs beside the `usedef/` ones
(`src/optimizations/`).

**Landed (the memory-independent half).** `src/linear/ReuseExprs.kt` implements the value
numbering for the opcodes that read no memory - `BinaryOp`, `UnaryOp`, `FieldAddr`, `IndexAddr`,
`GetStaticAddr`. A pure op's key is `(opcode, constant operands, each slot operand's last writer)`;
a later key equal to an earlier one *in the same basic block* becomes a read of it, and the pass is
iterated to a fixpoint so `&(a merged address)` matches its twin. The operands are compared by
value (a slot contributes its number *and* the instruction that last wrote it), and the kept and
merged-away slots must be written exactly once in the body - the guard that makes redirecting every
read of the merged-away slot sound where the local merge reuses a slot. It runs before `ReusePure`,
so merging `&self->out` is what lets a later read through it match. The *memory-reading* class
(`x.f`, `x[i]`, `size`/`spanOfStr`, `*p`) and the write/kill rule are still open - that is what
still repeats `spanOfStr(op)` per arm of `isCompoundAssignOp`'s `||` chain.

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

## Purity, effects and value numbering

B's property, invariance, needs a mechanism that says *which* expressions may be commoned and
*what* a write kills. The design makes that one thing, with no per-kind exceptions.

**Purity belongs to a call instance, not to a name.** `setArray(a, i, v)` is impure as a *call* -
its result is a fresh value and it writes `a` - but `a`, `i` and `v` are pure *expressions*, and
they are numbered and reused like any other. An impure call is never reused *itself*, and never
blocks the reuse of its arguments.

**Three effect classes**, the pass's only inputs:

| class | numbered? | killed by a write? | examples |
| --- | --- | --- | --- |
| pure, memory-independent | yes | no | `a + b`, `a - 1`, `&x.f`, `&x[i]`, `&Static.m` |
| pure, memory-reading | yes | yes, when the written location may alias | `x.f`, `x[i]`, `size(x)`, `spanOfStr(x)`, `*p` |
| impure / writes / fresh identity | no - a new value each time | - | `setArray(...)`, `Store`, `x = ...`, `Pack`, `toArray()` |

**One pass: value numbering.** A pure op's number is `(op, operand numbers, constants)` - the
constants are part of the key, so `&x.a` and `&x.b` differ - and an impure op gets a fresh number.
A slot holding a number another slot already holds **is** that slot: every use of the later one
becomes a use of the earlier, the duplicate op and its `Declare` go, and the pass repeats so merges
cascade (`&self->out` collapsing is what makes `&(that)->pool` collapse too).

**A write kills by the same numbers.** A store, or a call that may write, kills the numbers of the
memory-reading ops whose base may alias the written location - and "may alias" *is* the number
equality, which is where "provably the same pointer" is spelled. `_sm_base4` and `_sm_base7` are
replacements for each other because one pure op with one set of operands produced both, so the
write through the one is seen to reach a read through the other. Address computations are
memory-independent, so they survive the writes that kill the reads through them - which is exactly
the `self->out` evidence at the top of this file:

```cpp
_sm_base5 = simse_addressOf(self->out);   // memory-independent: one slot for all three
simse_list_append((*_sm_base4), text);    // impure: killed nothing above, no number of its own
_sm_expr2 = _sm_base7->size();            // memory-reading: NOT commoned across the append
```

**Purity is declared, never listed.** A call's effect comes from its declaration - `data` means
"writes nothing" - so the two `pureCallees.insert` lines for `size`/`count` in `Codegen.run`
are gone and the length accessors are declarations like `spanOfStr` and the intrinsics
(`strBytes`, `setBytes`). `lenOf(x)` is the operation for `Str`/`List` (`src/rtl/rtl.kt`, the
`lenops` section of `src/rtl/_res.md`); `Array.count`, `Dictionary.size` and `StrView.size()`
already had declarations and now carry the mark. `parser/BorrowParams.kt` seeds no name either:
its `pure` set is exactly the `IsPure` marks. An opcode's effect is one table row each. Binary
and unary arithmetic stay opcodes, because a reader understands `+` where `_sm_Op("+", ...)`
would need teaching - and both are pure, so the same pass commons `a + b` too, not only calls.

**What must not merge: a fresh identity.** Pure is necessary but not sufficient - an op that yields
a new ref-counted object (`Array<T>.toArray()`, a `Pack`) would hand both uses the same block, so a
write through one would show through the other. Value results, views and addresses are shareable;
fresh `Array`/ref-counted results are not.

**Where a field op can and cannot become a function.** `size(x)`, `getElement(base, i)` and
`addressOfElement(base, i)` take runtime arguments, so they can be ordinary `data` declarations
with intrinsic bodies (the `@SmGen("cpp", ...)` route). A *field* selector is a compile-time text
operand (`FieldAddr Var,Var,Text`), so it cannot be a plain function's argument: the field ops stay
opcodes, covered by the one effect table. The uniform end state, if it is ever wanted, is a
generated accessor per (type, field) - `simse_fieldAddr_<T>_<f>(base: *T): *F` - which makes them
functions too.

## Future: inferring purity

`data` is today a *promise* the writer makes. It can become a *result*: a function is pure when its
body is - it calls only pure functions, writes nothing, and returns no fresh identity of its own.
Concretely,

```kt
fun tan(angle: Float64): Float64 {
    return Sin(angle) / Cos(angle);
}
```

is pure as soon as `Sin` and `Cos` are, by a fixpoint over the call graph: start from the
intrinsics the compiler knows and the declarations already marked, and keep adding the functions
whose every call is to a function already known pure and whose own body contains no write. The
result is bottom-up purity, so `tan` needs no mark - and the optimizer's input stops being a
promise and becomes a proof. `pureCallees` is then not a list at all: it is the fixpoint's first
iteration, and the language does not ask an author to remember a mark for a function the compiler
can see through anyway.

The borrow pass already runs this shape of fixpoint for the *weaker* fact it needs
(`bpInferReads`, `impl_specs/escape-analysis.md`): "writes nothing" is enough to hand a pointer,
while folding a call additionally needs the result to be identity-free and the body to read no
file-level `var`, which is why `pureCallees` is still the `data` marks.
