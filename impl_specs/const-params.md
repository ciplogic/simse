# Constant parameters: a whole-program specialization

A parameter every call site passes the *same literal* is that constant, so it becomes a local in
the body and the parameter goes away.

```simse
fun logMe(isDebug: Bool) {          //  fun logMe() {
    if (isDebug) {                  //      val isDebug: Bool = false;
        print("is debug")           //      if (isDebug) {
    }                               //          print("is debug")
}                                   //      }
                                    //  }
logMe(false)                        //  logMe()
logMe(false)                        //  logMe()
```

## Why it is whole-program, and why that is sound here

"Every call site passes the same value" is the premise, so the pass has to see every call site.
Simse is a **closed world** - every module root is scanned and nothing links separately - so the
call sites a program has are exactly the ones the driver parsed. That is what makes the premise
checkable at all, and it is the same property the coloring pass and `!!` rely on.

The resolution is **name-level**, like the other whole-program passes here
(`cppsrc/sema/Async.kt`, `cppsrc/parser/Propagate.kt`): a call spells a name and a declaration
answers it. Where that approximation is not good enough the pass must *decline*, not guess - see
the conditions below.

## Where it runs

In `cppsrc/compiler/Driver.kt`, with the `!!` expansion, **once every module is parsed and before
`sema`** - so the checker, the lowering and the emitter all see the rewritten program and nothing
downstream needs to know the optimization exists. It is an AST-to-AST rewrite, so it belongs beside
`Propagate.kt` (which is in package `parser` for that reason) rather than in `cppsrc/optimizations/`,
where the passes work on the post-sema linear form and cannot change a signature.

The emitted C++ changes, so the published bootstrap is refreshed as part of the change like any
other.

## The two steps

1. **Gather** - walk every module and record, by name, the call sites (an `ExprCall` whose `Callee`
   is an `ExprName`) and the names used as a *value* (an `ExprName` that is not a call's callee).
   Calls are stored as node copies: they are only ever *read* here, so a shared children block is
   fine.
2. **Decide, then rewrite** - for each declaration that passes the conditions, rebuild the function
   (drop the parameter, prepend the local) and every call (drop that argument). Decide first and
   rewrite second, so the decisions all rest on the original program.

## The conditions - each one is a bug if missed

- **The declaration has a body.** A native or prelude declaration has nowhere to put the local.
- **The name is declared exactly once** among the program's declarations and the prelude. Two
  declarations sharing a name mean the call sites cannot be attributed, and an overload set would
  be rewritten wrongly.
- **The name is never used as a value.** `applyOne(21, doubleIt)` makes `doubleIt`'s signature part
  of a function type, and changing it breaks the use (the emitted C++ fails to compile). This is the
  one condition that is easy to forget, because the call sites look perfect.
- **At least one call site.** Zero call sites makes "all of them agree" vacuously true, with no
  value to fold in.
- **Every call site passes exactly `Params.size()` arguments.** A trailing-argument pack
  (`specs/functions.md`) or an overload makes an argument count differ from the parameter count, and
  then an index no longer identifies a parameter.
- **Only plain name calls.** A member call's callee is an `ExprMember`, so an extension is folded
  only when it happens to be called as a plain function - never through its receiver form. (A
  receiver is an implicit parameter and is not in `Params` at all.)
- **Value parameters only.** A `*T`/`&T` parameter takes a *place*, so no literal can be uniform
  for it; test the declared type and skip the parameter rather than trusting the argument shape.
- **The literals must be identical**, not merely both literal: same kind (int, float, str, char,
  bool) and the same value. Compare the `Text`/`Value` attributes and *exclude* `Line`/`Column`,
  which differ per call site - a comparison that includes them silently never folds anything.
- **`main` is never touched.** The runtime calls it, so its signature is not the program's to change
  (the `main(args)` form matters here, `specs/functions.md`).
- **The parameter's declared `Type` moves to the local.** The new binding must be typed: a body that
  yields turns its locals into fields and a field needs a type (`impl_specs/yield.md`), so an
  `auto` local would be rejected by the yieldable lowering.
- **Deterministic.** The walk order is fixed (module order, then declaration order) and the decision
  is a pure function of the call set, so two runs agree byte for byte - the property the bootstrap
  fixed point rests on.

## Implementation shape (the two non-obvious parts)

**How a node is replaced.** `Children` is a *field* of the node struct, so a copy's
`copy.Children = ...` does **not** reach the original - copies only share the *block* the field
points at. So a call site is replaced by writing the element inside its parent's shared block,
which is exactly the idiom `cppsrc/parser/Propagate.kt` and `cppsrc/linear/Yield.kt` already use
(`bodyPtr.Children = out.toArray()`, where `bodyPtr` came from `xmlChildPtr`). A module's
declaration list is the other way round: the driver holds the modules as a `List`, index
assignment on a `List` is not something to rely on, so the pass rebuilds and *returns* the modules
and the driver assigns the list variable (`modules = ...`) - a plain variable assignment.

**Only rebuild where a fold reaches.** A body that mentions no folded name is returned untouched
(its node shared as it is), so the cost is proportional to the folds, not to the program: a
read-only walk decides which functions contain a call to a folded name *before* anything is
rebuilt. Without that pre-check the pass would deep-copy the whole AST of the compiler every
build, and the self-transpile time is a published number.

## Not in the first slice

- A parameter passed a literal at *some* sites and the same literal computed at others, or a
  constant expression (`1 + 1`) - only syntactic literals count, which is what makes the check
  cheap and the fold obviously sound.
- **Multiple parameters at once** is a natural extension (decide per index, drop a set of indices
  from the declaration and from every call), but a single one is the smallest thing worth a stress
  case and is what the example needs.
- `null` literals, and a `typealias` behind a parameter's type - both are conservatively skipped.
- Propagation into a *generic* instantiation (the same function reified for two type arguments with
  different constants): the parameters that differ make it non-uniform, which is the right answer
  anyway for a reified language.

## The stress case

`stress/fold-const-params`: one function whose parameter is the same literal at two call sites (it
folds), a second whose parameter differs between call sites (it must not), a third called through a
receiver form (it must not), and one whose name is also passed as a value (it must not). The output
is what proves the *semantics* are unchanged; `expected.cpp` is what proves the fold actually
happened, since the point of the pass is invisible in a program's stdout.
