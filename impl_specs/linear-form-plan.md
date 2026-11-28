# The linear form: what to fix next

Status: in progress - the decisions are taken (2026-11-27), step 0 is in and step 1 is
next. The typed IL
is done as a typing exercise - the compiler's own output has no untyped slot and no `auto` -
and what the measurements show now is not a missing type but the shape around the types.
This plan continues `typed-il.md`: its steps 1, 2 and 4 are in, and its steps 3 and 5 are
steps 9 and 2 here. Its "Order of work" section points back to this one.

Every number below was measured on this tree; the last section says how to take each one
again. The first draft left three decisions open, and six of its statements do not hold
against the code; both are settled below ("What reading the code corrected", then
"Decisions"). Where a step disagrees with those two sections, they are right.

## Where it stands

| | |
| --- | --- |
| corpus | 164 cases, emission goldens on 40; the bootstrap fixed point holds both ways |
| the compiler's own IL | 1,905 bodies, 127,372 ops, 29 op kinds; **0** `?` slots, **0** `-> ?`, **0** `*?` receivers |
| the compiler's own C++ | **0** `auto`; 4.07 MB, 118,039 lines |
| typing refusals | **0** - no span hoist is refused for an unknown type |
| the bench suite | 16 Simse rows; the `for` template's new step shape measured at parity with the old |

`--showLinearRepresentation` prints every body's ops; `--diagnostics sema` traces every
promotion decision.

## What the measurements say

### 1. The compiler decides by name

| measured | count | where |
| --- | --- | --- |
| a call, member or function name compared with a string | **94** | sema 42, codegen 50, linear 2 |
| the names most keyed | `Res` 7, `Opt` 6, `initByValue` 5, `SmallVector` 5, `value` 4, `tryAdvance` 4, `error` 4, `Dictionary` 4, `toInt`/`size`/`fromInt`/`current`/`advance` 3 each | |
| literals of a reserved `_sm_` prefix | 34 | `startsWith("_sm_for")` 5, `startsWith("_sm_")` 2, `_sm_ctor`/`_sm_expr`/`_sm_item` 1 each |
| attributes compared with `"true"`/`"false"` | 87 | of 113 attribute kinds |

The cause is in the emitter. "The emitter emits from the IL and from nothing else" is the
rule, and it holds - but the IL emitter spells a call by *rebuilding syntax*: twelve
reconstructors in `IlSlot.kt` (`ilCallNode`, `ilOpValueNode`, `ilSlotNode`, `ilBorrowNode`,
...) turn ops back into nodes, and `IlEmit.kt` hands them to the old emitter 21 times
(`this.expr(...)`, 15 of them in `IlEmit.kt`). `CgCall.kt` then resolves the callee **by
name again** - `findExtensionFn`, `findNativeExt`, `protocolCall`, `staticCallSymbol`, and
the special cases for `iterValues`, `getAs`, `tryAdvance`. So the 50 name-keyed sites in
codegen exist to serve a call the IL has already resolved. The syntax emitter is also the
larger half: `Cg*.kt` is 6,615 lines against `Il*.kt`'s 3,101.

This session paid for it three times. A `step` local shadowed the prelude's `Range.step`
because resolution was by name. `tryAdvance(&slot)` is accepted as a raw address only
because the extractor and the emitter each match the callee's name (CgCall's rule for `&x`
is "a counted reference"; a user writes an out-parameter as `*slot`). And the `for`
template's slot needed its own prefix because five predicates tell a machine from its name.

### 2. The same type rule is written two to six times

| rule-set | function | lines | expression kinds it answers for |
| --- | --- | --- | --- |
| checker | `Analyzer.exprType`, `Analyzer.iteratedType` | 97, 102 | 8 each |
| type pass | `SemInfer.infer`, `memberReturn`, `callReturn` | 295, 136, 34 | all 17 |
| emitter | `Emitter.inferType` | 258 | all 17 |

There are 17 expression kinds, and **not one is typed by a single implementation**. A member
read is typed by six functions, a name by five, a call, a deref and a reference by four each.
The same rule went into two files three times in this session: `tryAdvance`'s result
(`TypeInferCall.kt` and `CgExpr.kt`, the same three lines), the packed element type (the type
pass and the checker), and seeing an alias through a local (the emitter only - the type pass
already did). `semPointeeOf` is `semPointee`. A rule that lands in one and not the other is
not a typo waiting to happen; it is a wrong answer with a source position.

### 3. The IL cannot say "the callee writes this"

The signature table (`LinearFormSig.kt`) gives every operand one role: `Var` (the first one
is where the value goes), `Value` (read), `Text`, `Label`, `Method`, `Type`. A slot the
*callee* fills is a `Value`. A scan that believes the table, over the compiler's own IL,
finds **1,859 reads before any write**:

| the callee that wrote the slot | slots |
| --- | --- |
| `tryAdvance` - the `for` step's out slot | 739 |
| `initByValue` - the union construction's receiver | 122 |
| `appendStr`, `append`, `appendStrPtr` - builders grown from an empty one | 97 |
| `NamedSection`, `setBytes`, `reserve`, `setValue`, ... | 40 |

then 858 later reads of those slots (735 of them the loop variable bound off a step's slot),
and 3 that are closure captures read in the lambda's body. Nothing else.

Three consequences, all paid for already. A strict verifier is impossible: it would report
these 1,859. No pass can reason about when such a slot is defined, so the `for` loop keeps a
slot and a copy per iteration that it does not need (below). And two patches are keyed on a
name because the table cannot carry the fact: `ilPromoteInitReceiver` tests for
`initByValue`, and the `tryAdvance(&slot)` rule has a twin in `CgCall`.

### 4. `&` and `*` each mean two things

`&x` is a counted reference (a box, `Ref<T>`) or the address of `x`; `*x` is a read through
a pointer or the address of `x`. Which one is decided by the operand's type. So a
pointer-typed local has **no spelling for its own address** - `*p` where `p: *T` is a
deref, and `&p` is a `Ref<T*>` - which is why the out argument is spelled `&slot` and
special-cased. In the surface `&x` is otherwise always a box of a copy
(`specs/memory-model.md`), so that `&slot` is an exception two sites make by name; and the
hole itself has one cause: `convertArgument` (`src/linear/LinearFormExpr.kt`) returns a
pointer-typed argument untouched - the *same shape* as a pointer parameter - before it
compares the pointees, so `p: *T` never reaches the address row of a `*(*T)` parameter.
It took three attempts to emit `&(self->item)` for a machine field in the
last stage: a `Deref` op reads a pointer-typed field *through*, and a `FieldAddr` borrows
one. The borrow machinery has the same gap - its rule for a callee that writes through a
fresh local is "the local stays fresh", which is right for a value and wrong for an address
the callee derives from elsewhere; the `for` template is the one caller that does that, and
it needed its own rule (`stress/borrow-for-pointer`).

### 5. A typed temporary never folds

`ilFolded` inlines a temporary at its use only when it is **untyped** and has one definition
and one use; a typed one is declared, assigned and read (two typed exceptions were added
this month, for `null` and a static name). In the compiler's own IL, of 29,404 compiler-made
temporaries, **1,138 have exactly one definition and one use, and all 1,138 are typed** - so
none folds. 635 of them are a pure read or copy (a field read 255, a copy 253, an index
read 54, arithmetic 64); 499 are a call's result. One `&self->mapped` in `select`'s step is
three pointer temporaries. Typing the form removed the `?`s, and with them the folding;
nobody chose that.

### 6. The C++ compiler is the type checker

Thirteen small mistakes, one program each:

| what the author wrote | what the compiler says |
| --- | --- |
| an argument of the wrong type, an unknown member, `"a"` initializing an `Int`, a missing field, a member on an `Int` | **nothing** - the transpile succeeds |
| `x + 1;` as a statement, `yield` inside a lambda, a member on a bare type parameter, a cast as a statement | `unsupported: expression statement` (and three more like it), **at the enclosing declaration** - three on its first line, the fourth at the lambda's call |
| a wrong arity, a `for` over an `Int`, assigning a `val`, an untyped lambda parameter | the right line and the right message |

Five of the thirteen are left to `cl.exe`, and all five are ordinary type errors. What the
author sees is `out.cpp(964): error C2440: '=': cannot convert from 'Span<Char>' to 'Int'`
(a generated file and line, and an internal type where `Str` was written), or a path into
`src/rtl/containers.hpp` naming `SmallVector<Int,4>` for a `List<Int>`, or an error on the
multiplication *inside the callee* for a wrong argument at the call. Every such report also
opens with three `C4244` warnings from the prelude's `Int.toString`. The language is
statically typed and its compiler checks none of assignability; the rules that would
(`semUnifyType`, `convertArgument`) compute the types and decline to compare them.

### 7. A report points where it can

An op carries a line and no column, and a body carries one file. A node the reconstructors
build carries no position at all, so `Emitter.fail` falls back to the declaration - which is
why the four `unsupported:` reports above land on the enclosing declaration. The extractor
has eleven refusal sites, in nine messages: `expression` (twice),
`statement`, `expression statement`, `type name in a value position`, `assignment target`,
`compound assignment target`, `a cast with no destination`, `constructor call with no
destination`, `structured statement reached the IL`.

### 8. The dump misreports, and the walk is not in the measurement

198 bodies carry a dead `?` entry in their type table (none has a live one), so a grep for
`?` overstates the untyped by 198. 104 ops print a wrong operand in their comment
(`SetIndex bytes, index, value  # index[value] = ?p0`); the op line is right. And
`--for-walk` is opt-in: the first equivalence checks of the new `for` template ran with it
off and proved nothing about the pass whose statement arithmetic had changed.

### 9. Optimizations live at two levels

Sixteen files under `src/optimizations/` fold and clean *lowered statements* (AST with
gotos), each pass with its own walk, and `UseDefs.kt` is a use-def for that level; four
passes in `src/linear/` (`PromoteRefs`, `ReuseExprs`, `ReusePure`, `MergeConcat`) work on
*ops*, and the emitter's `IlFrame` is a second use-def. Five proofs in the checker rewrite
the tree *before* the type pass has run (`spanForAt`, `promoteForAt`, `promotePlaceAt`,
`hoistSpanAt`, `forWalkAt`, all in `src/sema/SemaCall.kt`; the `sema` trace calls them
`spanFor`, `promoteFor`, `promotePlace`, `hoistSpan`, `forWalk`), which is why the checker
needs its own typing (section 2). On the
self-transpile, in the default configuration, place promotion is refused 4,315 times as
"not a place" and 233 as "not deep": types no longer block a promotion, the structure of
the proofs does.

## What reading the code corrected

Read against the tree before the first step (2026-11-27). Each correction is folded into
the step it changes.

- **The type pass does not see every statement.** `SemInfer.stmt`
  (`src/sema/TypeInferExpr.kt`) visits declarations and blocks only, never infers an
  initializer that has a declared type, and has no expected type. The comparison the first
  draft called declined is in the extractor: `convertArgument` computes both types and
  returns the operand "not the same type" (`src/linear/LinearFormExpr.kt`).
- **No flag exists for it.** `--typed-il`, `--verify-il` and `--check-types` are names in
  documents, not in `Driver.kt`. The verifier does exist (`ilVerifyBody`,
  `src/codegen/IlEmit.kt`): unflagged, run at emission only, and jump targets are all it
  checks.
- **Fourteen refusal sites, not eleven.** Three more carry messages of their own - a
  lambda's ambiguous overload, a union-class construction, a type parameter that cannot be
  inferred. All of them go through `IlExtractor.unsupported`
  (`src/linear/LinearFormFrame.kt`), whose op already records its line; the report is what
  never reads it.
- **The `?p0` in a store's comment is the printer's bug** (`ilOpComment`,
  `src/linear/LinearFormPrint.kt`): the `SetField` and `SetIndex` arms read a load's
  layout, so the comment shows the fourth operand of a three-operand op. The op line is
  right, as said.
- **Step 3's acceptance asked more than the step.** `initByValue` is the union
  construction's declared extension, named at about twenty sites that are the construction
  rule itself. What can stop naming it is the write accounting.
- **The five proofs have other names** (section 9): the first draft used their trace labels.

## The order of work

One step at a time, each measured before and after, pinned by a stress case, and green in
the loop before the next. A step that moves emission re-pins its goldens after reading them;
one that can reject a program lands report-only first. Sizes: S is a rule, M is a pass, L is
a subsystem. Each step is one validated commit, pushed, and the published bootstrap is
refreshed in the sibling checkout whenever the emitted C++ moves. Where a step forks, the
default is one rule: non-breaking before breaking, measured before kept, report-only before
enforced. Every default a step took is written under it.

### 0. Make the measurement true (S)

Type a `null` where it is created, so no body carries a dead `?`; fix the comment column
of `SetIndex`/`SetField`; give the `sema` trace a line number for every refusal (the
`hoistSpan` and `forWalk` refusals name a file and a template, not a line). Run the corpus a
second time with `--for-walk` in `iterate --full` (it is 11 s; one case exercises the walk
with a golden today).

*Accept:* `grep -c '?'` on a dump equals the count of untyped slots; every refusal line
names a line.

*Default:* the dump's type table lists only the entries something still names (a slot, an
operand, a method record), and a `?` interned for a slot that is typed later is typed
where it is created. The second pass is one `stress.js` step with three options of its own
- `--compiler-arg <text>`, `--work-sub <name>` (it writes `stress/.work/<name>/`, so the
two passes do not overwrite each other) and `--no-emission-golden` (the walk changes
emission by design) - the same step in the C# twin and in both of `iterate.js`'s step
arrays.

*Landed (2026-11-27).* Type tables with a `?`: 198 to 0, untyped slots 0 before and after;
store comments showing `?p<n>`: 105 to 0; refusal lines without a line: 0 of 5,266. A bare
`grep -c '?'` over the dump cannot reach 0 - the compiler's own source holds `?` inside
literals - so the type table is what the measurement reads. The second pass had never run
whole, and was 163 of 164: an indexed `for` over a range bound the index to the walk's
counter, which starts at the range's first bound (`for ((v, index) in 7..9)` printed 77).
Fixed in `forWalkAt` and pinned by `stress/for-walk`; one golden moved
(`stress/for-template`). The record is `impl_specs/capability-matrix.md`.

### 1. Type checks, report-only (M)

The type pass does not see every statement (above), so each comparison goes where its two
types already are. *An argument against its parameter:* in `convertArgument`, whose reach
test becomes one named predicate that the conversion and the check both read. *An
initializer against a declared type, a value against its assignment's target, a return
against the declared return:* new arms in `SemInfer.stmt`, which infers the value where a
type is declared. *A member against its receiver's class:* where the extractor meets the
member read - the class's fields (`semAllFields`), a declaration of that name, the type
pass's `memberReturn` - and a construction's arguments against the class's fields. The
conversions
the language already applies are the allowed list (`convertArgument` is the definition): a
borrow, a read through a handle, a derived value where its base is wanted, `null` for a
handle, a string literal where a view is wanted, a lambda where a callable is. A type the
rules cannot name reports nothing. Under `--check-types` first.

*Accept:* the corpus and the self-transpile report nothing; the five mistakes of section 6
are reported at their own line with both types in the language's names (`Str`, not
`Span<Char>`). *Risk:* a false positive on a shape the rules name imprecisely - that is what
the report-only mode is for, and each one found is a typing bug worth its own commit.

*Default:* reports go to a sink of their own (`src/sema/TypeCheck.kt`), rendered by
`diagRender` with `semaTypeText` after a one-line banner, and the emitted C++ does not
change.

*Landed (2026-11-27).* The corpus with the flag: 165 transpiled, 0 reports; the
self-transpile: 0 reports. The five cl.exe-reported mistakes report at their own line in
the language's names. The exemptions the tree taught: compound assignments, the
stringification builtins, the body's own class's fields, the `Opt`/`Res` surface, and a
parameter naming a type nothing declares. One bug found on the way: the const-params fold
moved a wrong-typed literal into the callee's body - it now declines a literal that does
not reach the parameter, so the mistake reports at the call. The record is
`impl_specs/capability-matrix.md`.

### 1b. Errors by default (S)

The commit after: the checks run for every program and a report fails the transpile,
`--no-check-types` is the escape hatch (the `--no-borrow` convention), the five programs
are `stress/diagnostic-type-*`, and the flip is a release note.

*Accept:* the five cases fail at their own line; the corpus and the self-transpile do not
move.

*Landed (2026-11-27).* Seven `stress/diagnostic-type-*` cases, one per shape - argument,
initializer, assignment, return, member read, member call, scalar member - each failing at
its own line; the escape hatch verified (`--no-check-types` transpiles a wrong program);
the corpus 172/172 and the self-transpile clean. One lesson: the slot hoisting turns an
initialized declaration into a bare declaration plus an assignment before the IL, so the
initializer comparison is the assignment's - there is no separate "initializer" wording.

### 2. Positions on ops, then the refusals (M)

An op carries a column as well as a line; the reconstructors stamp the op's position on
every node they build; a report with no position is an `internal:` diagnostic naming the op,
never a user diagnostic on the declaration. Then typed-IL step 5: each of the fourteen
refusals becomes a diagnostic with a position and a hint (`expression
statement` becomes "an expression statement must be a call"). Also the three `C4244`s: the
prelude's `Int.toString` narrows `Int64` to `Int` three times, in every program ever built.

*Accept:* the four misplaced reports point at their statement; one case per message; a
hello-world builds without a warning.

*Default:* none becomes a typed op. Six are the author's, each with a hint - `expression
statement` ("an expression statement must be a call"), `assignment target`, `compound
assignment target`, `type name in a value position`, `a cast with no destination`,
`constructor call with no destination`; three are the compiler's and stay `internal:` -
`statement`, `structured statement reached the IL`, `expression`. The `Unsupported` marker
is deleted in step 4, and only if nothing produces it then. The `C4244`s are `intToStr` and
`Str.addInt` (`src/rtl/rtl.kt`): each narrowing is spelled as the scalar conversion it is.
One helper appends an op with its line and its column, and every pass goes through it.

*Landed (2026-11-27).* Columns ride through the four passes and the hoist; the
reconstructors stamp; the six author's refusals report at their statement with hints (four
pinned, one per shape), the compiler's three keep the `internal:` wrap, and two refusal
sites proved unreachable (a bare `Point` and a statement construction both build) - left
in place, unpinned. The `C4244`s are spelled as the scalar conversions they are, and a
hello-world builds with zero warnings; 32 goldens moved by exactly that spelling, audited.
The record is `impl_specs/capability-matrix.md`.

### 3. The `Out` role (M)

The signature table gains a role for an operand the callee writes. The facts exist: the
borrow pass already infers which positions a callee writes (`bpInferWrites`, the author's
`mutates` marks and the fixpoint's proofs in one table), and `tryAdvance` and `initByValue`
are known at extraction. They flow into `IlMethod` as a write mask. Then delete
`ilPromoteInitReceiver`'s name test and both `tryAdvance(&slot)` special cases, and give
`&`/`*` one meaning each in the IL - an `Addr` op is an address, a `Box` op is a reference.

*Accept:* the scan of section 3 reports the 3 captures and nothing else;
`ilPromoteInitReceiver` and both `tryAdvance(&slot)` sites are gone (`initByValue` is the
union construction's declared extension and keeps its other sites). *Risk:* the address-of
spellings - the machine-field case is pinned by `stress/yieldable-receiver`, the parameter
case by `stress/borrow-for-pointer`.

*Default, two commits.* *3a:* `IlOperandKind.Out`, and `IlMethod` carries `writes` - the
argument positions the callee writes, the receiver at 0 - filled once in `methodIndex` from
the borrow pass's table and otherwise by one rule: a handle parameter is written unless the
callee is `data`, `borrow` or proved borrow-clean, and `mutates` narrows that to the
receiver. ReusePure and PromoteRefs read the mask. *3b:* `Addr dst, src` is the address of a
slot and `Deref` keeps only read-through, with the emission byte-identical. The out
argument follows "Decisions" below - `&place` bound to a `*P` parameter, by signature - in
`convertArgument`, which retires the extractor's rule and the `&slot` half of `CgCall`'s;
the receiver half joins `advance`'s twin as one rule keyed on the receiver's type.

*Landed (2026-11-27, 3a and 3b).* 3a: `IlMethod` carries `writes` and `grows`;
`IlOperandKind.Out` names the argument; `ilPromoteInitReceiver` is deleted; the scan
(`tools/_outscan.mjs`) reads 0 over 1,938 bodies. 3b: the interceptor is deleted - the
place rule lives in `convertArgument`, gated on the mask, running before the parameter
question (the machine step resolves no declaration); `CgCall`'s two machine arms are one,
keyed on the receiver's `..T` type and on resolving to no declaration; `Addr` is an
opcode (4,565 over the compiler's tree); the recorded place and the mask's position both
gate the emitter's `&` spelling (slot indices are reused by the passes). Emission
byte-identical, no golden moved. The typing twins in `CgExpr.kt`/`TypeInferCall.kt` stay
for steps 7 and 8. The record is `impl_specs/capability-matrix.md`.

### 4. The verifier goes strict (M)

With `Out` in the table the permissive verifier can check what it was meant to: every read
is preceded by a write on every path that reaches it (the CFG is the labels), every operand agrees with
its role, every call's arguments agree with its method record, every jump has a label.
`--verify-il` first, then after every pass in a debug build.

*Default:* `ilVerifyBody` moves to `src/linear/VerifyIl.kt` and always checks an operand
against its role, a call's arity against its method record, and every jump's label. Under
`--verify-il` - a new flag; there is none today - a forward must-be-defined dataflow over
the labels (an intersection at every join; an `Addr` that feeds an `Out` argument defines
the slot it addresses) reports `<slot> read before a write`, after every op-level pass in
`emitBodyAt` and `dumpIl`, naming the pass. The flag joins the corpus's second pass.

*Accept:* zero reports on the corpus and the self-transpile. *Why here:* every later step
is a rewrite, and this is what makes a rewrite that breaks a body name the pass that broke
it - in the last stage a miscompile took the compiler itself down, and the only tool left
was the published bootstrap.

### 5. Fold typed single-use temporaries (S-M)

`ilFolded` folds a typed temporary too when its one definition is pure and its one use is in
the same basic block. A call's result stays declared.

*Accept:* 1,138 falls to about 500; the goldens shrink, read and re-pinned; the bench rows
do not move.

*Default:* kind `Temp` or `Expression`, one definition, one use, a pure definition
(`GetField`, `GetIndex`, `GetStatic`, `SetVar`, `CopyValue`, `BinaryOp`, `UnaryOp`), no
`Label` between the definition and the use, a spellable type, no closure; the slot's
`Declare` goes with it.

### 6. The element lands in the loop variable (S, after 3 and 5)

The `for` binding (`val v = _sm_item<n>`) becomes the step's own out argument: `v` is
assigned nowhere else, and the slot has no other use. In the compiler's own IL that is 739
steps, 734 copies and 750 slots, in 471 bodies. The out slot stops being a name-keyed
template part and becomes what it is: a temporary a pass can forward.

*Accept:* no `SetVar v, _sm_item<n>` remains in a dump.

*Default:* the template declares the loop variable as the out slot itself, and its
declaration carries the template's id as an attribute; `forItemMachine`, the checker's
val-ness rule, `bpClassifyPlace` and `forWalkAt` read that attribute where they read a
`_sm_item` name today. Assigning to a loop variable stays the diagnostic it is.

### 6b. The machine writes through its out slot (M, measured)

The machine-side twin of step 6. Today `tryAdvance(self, out)` is a wrapper generated over
the step (`src/codegen/IlMachine.kt`): the step writes the `current` field and the wrapper
copies it out, on every element of every loop. The step takes the `out` parameter itself -
each suspend point writes `*out` (`yldExpandSuspend`, `src/linear/Yield.kt`) - and
`advance(self)` is what gets generated, as `tryAdvance(self, &self->current)`.

*Accept:* no `current` store on a suspend; `stress/machines` and the `yield*` goldens read
and re-pinned; the bench rows interleaved, `particles` among them (the machine's step is
the shape MSVC pattern-matches there, `impl_specs/for-walk.md`). If no row moves, the
number is recorded here and the fold is not kept.

### 7. One type oracle (L)

The frame has a type for every slot. The emitter stops deriving: it reads a name's type
from the frame, then a reconstructed node's from the op that produced it, and the arms of
`Emitter.inferType` are deleted as they stop firing (a trace counter says which still do).
When the emitter has none left, the checker's are asked what they are for - the five
proofs of section 9 that run before the type pass - and either move after it or keep only
what they need. `semPointeeOf` goes with them.

*Accept:* 17 expression kinds, each typed by one function.

*Default:* `SemInfer.infer` is the oracle - it is the rule-set that covers all 17 and left
no `?`. The emitter's reconstructed nodes carry their slot and `inferType` answers from the
frame first; a counter per arm under `--diagnostics codegen` says which arms still fire, and
each is deleted at zero. The checker's `exprType`/`iteratedType` keep their scope bindings
and ask `semTypeOfExpr` for every type, so the five proofs stay where they run.

### 8. Calls are spelled from a record (L)

`IlMethod` carries what the extractor resolved: the symbol, the package, the kind, the
template arguments and each argument's conversion. `ilCallNode` stops rebuilding syntax and
the emitter spells the call from the record. Then the 50 name-keyed sites in codegen lose
their input and retire one call kind at a time - construction, extension, native,
protocol, static, machine step.

*Accept:* a call resolves once; no `calleeText ==` remains in `src/codegen/`.

*Default:* `ilCallNode` is replaced by `ilCallText`, spelled from the record, and the
name-keyed sites retire one call kind per commit: construction, extension, native, protocol
(`Opt`/`Res`, `toInt`, `size`), static, machine step.

### 9. `SpanOf`, and the proofs on the form (L)

typed-IL step 3: `spanOf(c)` as an op whose result is `Span<T>`, with typed load and store
over it, so the span hoist and the for-walk are rewrites of ops on a verified form instead
of tree edits before types exist. The last of the 94 names and 34 prefixes go with it: a
template part is an operand, not a spelling.

*Accept:* `--for-walk` measured on the new form before it is considered for a default.

*Default, three commits:* `SpanOf dst, src` first, spelled as today's `spanOf` call, with a
span-typed base for index load and store; then the for-walk as an op-level pass that finds
the loop by its ops, with no `_sm_for` prefix; then the span binding as `ilSpanHoist`, its
stability proof a dataflow. Each tree-level twin is deleted when its decision traces match
as sets. `--for-walk` stays off by default: its flip is measured on the new form, not
assumed.

## Decisions

The first draft left three decisions to the language's author, as "not the compiler's
alone". They are taken here by default (2026-11-27), each the way that breaks nothing and
keeps the measurement as the judge; the reasoning stays with each, so a different choice
can see what it trades.

- **`advance()` and `current` stay, and the step folds anyway.** Retiring the pair would
  break every program that steps a machine by hand (`stress/machines` does), and it would
  cost more than a migration: `toPtrs` yields the *place* of the source's `current` by
  choice (`impl_specs/for.md`), which a contract that hands out values cannot give - one
  more copy per element. The gain the retirement was for - a field store and a copy off
  every element of every loop - does not need it. The step takes the `out` parameter
  itself and `advance()` becomes the wrapper (step 6b): nothing breaks, and `current`
  stays a field for the hand-stepped surface. `specs/functions.md` says "advanced once per
  iteration" and names no method; it gains the contract's text - `tryAdvance` as the one
  method every loop steps through, the pair as what a program may still call by hand -
  with the step that changes it. The fold is kept only if the bench rows move.
- **`&place` is the out argument, for every callee, by signature.** An argument spelled
  `&x` and bound to a `*P` parameter where `x: P` is `x`'s address; every other `&x` stays
  what `specs/memory-model.md` says, a box of a copy. This is what `m.tryAdvance(&slot)`
  already means, today only because two sites match the name. The spec's own row - "a `*T`
  parameter takes a `T` argument's address" - covers a value-typed slot on its own, but for
  a pointer-typed one it is ambiguous against the same-shape row wherever the parameter's
  pointee is a bare type parameter, which is exactly the machine contract's; and every
  authored call site, the `for` template and the docs already write `&slot`. So no program
  is rewritten and the hole closes for any slot type. One row joins "Handles at a call",
  and the IL spells it `Addr` (step 3).
- **Type errors become errors as soon as they are clean.** Step 1 is report-only for one
  commit; the next turns it on (step 1b). When it turns on, a program that compiled
  yesterday may stop. That is the right outcome for a static language and still a release
  note.

## What this plan does not do

It does not rewrite the form, add a second one, or port the sixteen statement-level passes
to ops. The statement level keeps what it has; new optimizations go on the ops, and a
statement-level pass is retired when an op-level one makes it redundant, measured. It does
not touch the bootstrap chain's rule: every step is green in the loop, on the default path,
before it is committed.

## Re-taking the measurements

All on the compiler's own tree, from the repository root:

```sh
./simse.exe --root src --root modules -o build/self.cpp --showLinearRepresentation 2> build/self.txt
grep -cE '^[[:space:]]*auto[[:space:]]+[A-Za-z_]' build/self.cpp     # 0
grep -c 'var [A-Za-z_0-9]*: ?' build/self.txt                         # 0
./simse.exe --root src --root modules -o build/x.cpp --diagnostics sema 2> build/trace.txt
./simse.exe --root src --root modules -o build/x.cpp --showBorrow     2> build/borrow.txt
```

- **Names** (section 1): grep `(calleeText|calleeName|memberText|methodName|fnName|nameText|
  recvName|baseName) *== *"..."`
  over `src/sema`, `src/codegen` and `src/linear`.
- **Rule-sets** (section 2): for each typing function, the distinct `AstNodeCategory.Expr*`
  kinds its body mentions.
- **The out-slot scan** (section 3): walk each body's ops in order, classifying operands by
  the signature table's roles - the first `Var` is a write, every other slot operand a read,
  a `Declare` is storage only - and report a read of a slot not yet written. Group by the
  method operand of the call that first took the slot.
- **Single-use temporaries** (section 5): per body, the slots of kind `Temp` or `Expression`
  with exactly one write and one read.
- **Diagnostics** (section 6): thirteen small programs with one mistake each; the five that
  transpile cleanly are the finding.
- **Equivalence of a rewrite**: the same tree transpiled by two compilers, the decision
  traces compared as sets per file and reason - **with `--for-walk`**, and with the walk's
  own cases in mind - and the bench rows interleaved, both compilers, at the harness's flags
  (`bench/run.js` names them; its Simse rows alone are what a compiler change can move).
