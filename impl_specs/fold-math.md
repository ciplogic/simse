# The math folds: exact identities and the power-of-two division

Status: landed (`src/parser/FoldMath.kt`, the `modules/math` module). Two rules, both in
one whole-program AST-to-AST rewrite that runs with the `!!` expansion and before sema
(the `ConstParams` shape), so the checker, the lowering and the emitter all see the
folded program and nothing downstream knows the optimization exists.

## The `math` module

`modules/math` (`--module modules/math`, `import math`): the standard math library over
`Float64` and, for the functions a float-heavy program actually calls, `Float32` - one
thin inline wrapper per function in `_res.md`'s `libm` section (`simse_math_sin`, never
a bare `sin`), `<cmath>` included exactly when a program reaches for one of them. Every
declaration is marked `data`: read-only, a pure function of its arguments, so the borrow
fixpoint trusts callers through them and the purity machinery may reuse repeated calls.

## The identity fold: `sin(0.0)` is `0.0`

A call to a math wrapper whose literal argument matches an **exact identity** of the
correctly rounded function is replaced by the literal the function answers - never an
evaluated approximation, so a folded program is bit-identical to the unfolded one:

- the zero-preserving functions (`sin`, `tan`, `asin`, `atan`, `sinh`, `tanh`, `cbrt`)
  answer the zero they were given, sign included (`sin(-0.0)` is `-0.0`);
- `cos`, `cosh`, `exp` answer `1.0` at zero;
- the logarithms (`log`, `log2`, `log10`) answer `0.0` at one;
- `sqrt` answers the zero and the one it was given;
- `pow`'s C99 identities: `pow(x, 0.0)` is `1.0` for every `x` (NaN included),
  `pow(x, 1.0)` is `x` (only when `x` is already a float literal, so the call's type
  never moves), and `pow(1.0, y)` is `1.0` for every `y`;
- `atan2(0.0, 1.0)` is `0.0`;
- `abs` of a float literal drops its sign - an exact negation.

Every replacement is a *float* literal (a zero text gains `.0` when it lacks one), so
the float type the call answered is always what stands in its place. The gate is
name-level and declines on any doubt, the `ConstParams` rule: a name folds only when
**every** declaration the compiled set carries for it is the module's own body-less
`@SmGen` declaration whose symbol is one of that name's wrappers (`simse_math_sin` /
`simse_math_sinf` for the two widths) - a program that declares its own `sin`, or
generates one under another symbol, is left alone. (A same-name cross-package overload
pair does not emit today - `findFunction` picks one - which is a pre-existing emitter
edge, not this pass's; the gate stays sound either way, refusing the fold.)

## The division peephole: `x / 2.0` is `x * 0.5`

The one place a float operation is rewritten without knowing the operands' values. A
division whose divisor is a float literal whose text is an **exact power of two** becomes
a multiplication by the reciprocal:

- the integer-significand family (`2.0`, `4.0`, `1024.0`, up to 2^27) answers
  `"0." + 5^k padded to k digits` (2^-4 is `0.0625`);
- the fraction family (`0.5`, `0.25`, `0.125`) answers `2^k + ".0"`;
- a sign travels (`x / -2.0` is `x * -0.5`); an exponent form declines.

Both operations are bit-identical for every input - signs, zeros, infinities, NaNs and
subnormals included, because scaling a binary float by a power of two is exact in both
directions, and the reciprocal's decimal spelling round-trips exactly because powers of
two are finite decimals (`FoldExprs`' "a Float64 fold needs a spelling that parses back
to the same bits", satisfied by construction). An integer division never qualifies: it
truncates, and `x / 2` is not `x * 0.5`. The reward is the divider: `mulsd` is four
cycles of latency against `divsd`'s ~thirteen, and nothing on the divide unit.

## The walk

One bottom-up rewrite per module with the `foldExprsUnder` discipline - a child that
folds rebuilds its parent - and one lesson this pass had to learn the hard way: the
"changed" signal is a **fresh state per child**, not a shared one-way latch. A shared
latch cannot say "this subtree folded" once an earlier sibling has set it, and the
parent entered after a fold then discards its own folded children - exactly the shape
that lost every fold but the first declaration's (`fm module done changed=true
loud999=1`, the debug line that found it).

## Measured, honestly

The peephole's A/B on this machine (the loop-carried `x / 2.0` / `y / 4.0` chain, old
vs new compiler binaries, interleaved): **~3%, inside noise** - because MSVC lowers a
power-of-two literal float division to a multiplication itself (`dumpbin /disasm`:
`fdiv=1 fmul=16` in *both* binaries, the one `fdiv` in the float printer). The
transformation is still the compiler's to make: it holds at `-O0` and `/Od` (where the
backend does nothing), on backends that decline it, and it composes with the fold
pipeline (`5.0 / 2.0` folds to `5.0 * 0.5` ahead of any later float work). The identity
fold needs no measurement - removing a call is removing a call.

## The pins

`stress/fold-math` (with `--module modules/math`): the identities fold (`0.0 + 1.0` in
the golden, no wrapper call beside them), `sin(0.75)` reaches the wrapper at run time
(the printed value is libm's own), and the peephole shows both families
(`5.0 * 0.5`, `0.5 * 4.0`).
