# The linear IL (draft)

The form `impl_specs/linear-lowering.md` builds is already *almost* an instruction
list: control flow is labels, gotos and conditional jumps, every value position is one
operation deep, and the lowering's own storage is declared once at the top of the body
(the slot hoisting). What is left that is not linear:

- a statement is still a small tree (`Assign` holds a target *path* and a value,
  `Call` holds a callee and N arguments, `IfTrue` holds a condition);
- a body is still a tree of `Stmt.Block`;
- a lambda is an expression **with a body inside it**.

This is the shape that removes all three, so a later optimization can read one
instruction at a time without knowing anything about scopes or expression trees. The
model is Smali's (`.method` / `.registers` / `.local` / `const-string` next to the
`invoke-*` family), which is a good fit because it is *designed* to be printed and
read, not just executed.

Status: **the projection, the printer, the dump flag and the backend are implemented in
the C++ ring** (`cppsrc/linear/LinearForm.{h,cpp}`, `--showLinearRepresentation`,
`--linearCodegen`, `--linearCodegenEmit`); the tables, the operand kinds and the
instruction list below are what the code does. The **Simse ring has none of it yet** -
`Codegen.simse` still reads statements, which is the one thing that keeps the
statement emitter alive in both rings (see "Dropping the statement emitter").
"What the corpus says" is the measured state of the projection over the whole
compiler.

## Body

```
IlBody {
    file:    Str                # for the source comments (`// file:line`)
    types:   List<Str>          # every type this body mentions
    vars:    List<IlVar>        # the frame
    pool:    List<Str>          # text: string literals, field names, operators
    methods: List<IlMethod>     # every callee this body calls
    labels:  List<Str>          # label names; an operand indexes this
    ops:     List<IlOp>         # the instructions
    lines:   List<Int>          # source line per instruction (parallel to `ops`)
}

IlVar    { name: Str; typeIndex: Int; kind: IlVarKind }
IlMethod { symbol: Str; argCount: Int; kind: IlMethodKind }
IlOp     { name: Str; operands: List<Int> }

IlVarKind    = Argument | Local | Expression
IlMethodKind = Function | Method | Native | Constructor
```

- **Arguments come first**, `self`/`this` among them, so a callee's parameter *i* is
  variable *i* - the frame is the calling convention, and a call's operands are
  already in the callee's order.
- **`Local` is a variable the program wrote** (`var`/`val`); **`Expression` is storage
  the lowering introduced** (`_sm_expr<n>`, `simse_sw_<n>`, the closure slot of a
  lambda). The kind is what a later liveness/slot-reuse pass leans on: an
  `Expression` slot is not observable, a `Local` is.
- **Types are canonical text**, fully qualified and spelled the language's way:
  `rtl_Str`, `ns1_Point`, `rtl_SmallVector<Int, 4>`, handles as `&T` / `*T`, type
  parameters symbolic (`Box<T>`). The backend reads the index, the dump reads the text.
- **A name is unique in the table.** The frame is flat, so shadowing is resolved
  before the IL (a rename in the lowering); the dump may keep the original name.
- **Text is a pool index**: a string literal, a field name, an operator spelling
  (`"+"`), a native's symbol. A callee is **not** here - it has its own table (below) -
  so a call operand is never ambiguous with a string.
- **The IL infers nothing.** Every slot has the type the type pass gave it, and the
  backend decides spelling from those types (`+` on two `Str`s is an append,
  `simse_addressOf(x)` vs `x.get()` follows `*T` vs `&T`): that is a *lookup*, not an
  inference. What the IL needs instead is a **verifier** - operand counts and kinds
  from the signature table, jump targets in range, a call's argument count against its
  `IlMethod`, a `BinaryOp` whose operand types make sense.
- **`Label` is an instruction**, so a label's position *is* its position in `ops`, and
  a jump carries a **label index**, not an op index:

  ```
  12,  Label   3                      # L4:  (labels[3] == "L4")
  13,  IfFalse 5, 3                   # if (!(vars[5])) goto L4
  ```

  That is the point of keeping labels: inserting or removing instructions does not
  invalidate a single operand, which matters because the peephole and the folding
  rewrite the list. The C++ backend prints `L4:;` and `goto L4;` and needs no
  resolution at all; a future bytecode backend resolves a label index to an offset in
  one pass over `ops`.
- **`lines` is not optional**: the emitter's `// file:line` comments, the goldens and
  every diagnostic come from it. One line per instruction is enough - a body is in one
  file.

## Operand kinds

An operand is an `int`; *what it indexes* is the opcode's signature, stated once here
and read by the verifier and the printer alike. **The first `Var` operand of an op that
produces a value is its destination** (Smali's convention), and the table says which
ones produce a value.

| Kind | Indexes |
| --- | --- |
| `Var` | a frame slot - a **destination**, or a place read (`GetField`'s base) |
| `Value` | a frame slot **or a constant** (a negative operand: `pool[-1-n]`) |
| `Text` | a pool entry (`pool`) |
| `Type` | a type (`types`) |
| `Method` | a callee (`methods`) |
| `Label` | a label (`labels`) |

The split is what keeps the opcodes free of types: the frame says what a slot holds,
so `SetVar` needs no `_Int`/`_String` suffix and no numeric operand kind - a constant
rides the instruction as text the backend prints verbatim. The table also states
which positions must be *slots* (`Var`: a destination, a place) and which accept a
constant (`Value`: nearly every read position), which is exactly what a verifier and
a backend need to know and nothing more.

```
Label            label=Label
Goto             target=Label
IfTrue           cond=Value, target=Label
IfFalse          cond=Value, target=Label

Declare          dst=Var                          # a slot comes into being here
SetVar           dst=Var, value=Value             # x = y | x = 5 | x = "abc" | x = true
SetVar_Null      dst=Var                          # x = null (the spelling is the type's)
BinaryOp         dst=Var, op=Text, a=Value, b=Value  # a = b + c   -> ["+", a, b, c]
UnaryOp          dst=Var, op=Text, a=Value        # a = !b      -> ["!", a, b]
Cast             dst=Var, src=Value               # a = Enum.toInt() -> static_cast

Box              dst=Var, src=Var                 # a = &x   a counted handle (std::make_shared)
Deref            dst=Var, src=Var                 # a = *x   a borrow (&x), a `.get()` or a
                                                  #          load of a `*T`, by src's type
CopyValue        dst=Var, src=Var                 # a = copy(x)  the value behind a handle
Store            ptr=Var, value=Value             # *p = v

GetField         dst=Var, base=Var, name=Text     # x = a.f
SetField         base=Var, name=Text, value=Value # a.f = b     -> ["f", a, b]
GetIndex         dst=Var, base=Var, index=Value   # x = a[i]
SetIndex         base=Var, index=Value, value=Value  # a[i] = b
IndexAddr        dst=Var, base=Var, index=Value   # an address slot (*T): a[i].f = v
FieldAddr        dst=Var, base=Var, name=Text     # ... and a.f().f = v
GetStatic        dst=Var, name=Text               # an enum member or a file-level var
SetStatic        name=Text, value=Value

Call             dst=Var, callee=Method, args...=Value      # x = f(a, b)
CallVoid         callee=Method, args...=Value               # f(a, b)
CallIndirect     dst=Var, callee=Var, args...=Value         # x = f(a, b), f a callable slot
CallIndirectVoid callee=Var, args...=Value
CallCtor         dst=Var, type=Type, args...=Value          # Point(1, 2), List<Str>(), a closure

Return           value=Value
ReturnVoid

Lambda           dst=Var            # a body still inside an expression (marker)
Unsupported      dst=Var, why=Text  # anything the extractor cannot express (marker)
```

The two markers are instructions *on purpose*: a shape the extractor cannot write
down shows up as a gap in the dump and a count in the verifier, instead of being
silently dropped. A body whose dump has neither is a body the IL covers in full,
and that is the property a backend needs.

- **`BinaryOp` carries the source operator and nothing about types.** `a = b + c` is
  `["+", a, b, c]` whether the operands are `Int`s or `Str`s; the frame's types decide
  what the backend prints. If an optimizer ever wants the resolved operation it can
  read the operand types the same way.
- **Calls to natives and to methods are the same op.** A *native* is a `Method` entry
  whose kind says so (the backend's receiver convention differs there, the rule T47
  already follows); a *method* puts the receiver in `args[0]`, whose slot type (`*T` or
  `&T`) decides `simse_addressOf(x)` vs `x.get()`.
- **`CallIndirect` is the interface tier.** A callable slot (a lambda, a
  function-typed `Local`, an `&lambda` handle) is called through the slot, which is
  exactly Smali's `invoke-interface` next to `invoke-static`.
- **`Cast` exists because the language has exactly one cast**: `Enum.toInt()` is
  `static_cast<Int>(x)` in the emitter today, and `Enum.fromInt(v)` is a *call* to a
  synthesized helper (`ns1_simse_NameKind_fromInt`), so the method table covers it.
  Every other conversion (`toInt`, `toString`, `toFloat`, an integer widening) is a
  call, as the user expected.
- **Address slots** (`IndexAddr`, `FieldAddr`) are .NET's `ldelema`/`ldflda` pair and
  are what lets `Slot::Path` disappear: a path becomes a value of type `*T`, the alias
  semantics stay visible, and `a[i].append(x)` is a call with an address operand.
- **Borrow of a temporary**: today `*f()` stays inline (`simse_addressOf(f())`, valid
  for the call it is passed to). An instruction list cannot nest, so the *owned* value
  moves to a slot first (`dst = Call f`, then the address of that slot) - which
  *extends* the temporary's life to the slot's, i.e. it is safe, never dangling. That
  is the one place where the IL is deliberately blunter than the C++ it emits.

## Printing it

`--showLinearRepresentation` (the C++ driver) prints one body per function: the file
and signature, the tables, then one line per instruction with the operands resolved
for the reader and the source line appended. Real output, verbatim:

```
# cppsrc/codegen/Codegen.simse:54  ns1_cgJoin (*List<Str> parts, Str separator) -> Str
types:   0 *List<Str>   1 Str   2 Int   3 Bool
vars:    0 parts:0:Argument   1 separator:1:Argument   2 _sm_expr1:2:Expression   3 _sm_expr2:3:Expression   4 _sm_expr3:3:Expression   5 _sm_expr4:1:Expression   6 out:1:Local   7 i:2:Local
pool:    0 ""   1 0   2 "<"   3 ">"   4 "+"   5 1
methods: 0 size:Method:1:ret=Int   1 appendStr:Method:2
labels:  0 L1   1 L2   2 L4
4   ,  Declare         out                                # var out: Str  (line 55)
5   ,  SetVar          out, ""                            # out = ""  (line 55)
6   ,  Declare         i                                  # var i: Int  (line 56)
7   ,  SetVar          i, 0                               # i = 0  (line 56)
8   ,  Label           L1                                 # L1:  (line 57)
9   ,  Call            _sm_expr1, size, parts             # _sm_expr1 = parts.size()  (line 57)
11  ,  IfFalse         _sm_expr2, L2                      # if (!_sm_expr2) goto L2  (line 57)
12  ,  BinaryOp        _sm_expr3, ">", i, 0                # _sm_expr3 = i > 0  (line 58)
```

A declaration followed by the instruction that writes it is one line in the emitted
C++ (`Str out = "";`), which is what `ilWritesDestination` is for; a declaration left
behind by the slot hoisting has no instruction of its own and stays a bare
`Str _sm_expr1;`.

(`methods:` shows `name:kind:argCount`, plus `:static=<type>` for a static call and
`:ret=<type>` when the call has a result; a `Method`'s receiver is its first
argument, so `size:Method:1` is `parts.size()`.)

The dump is **deterministic** (tables in first-use order, one line per op) because the
build compares outputs byte for byte: it can become a golden category
(`tests/golden/<fixture>.il.expected`) and a *sharper* differential than the emitted
C++ - two rings whose IL dumps agree are far harder to be "accidentally equal" than
two whose C++ text agrees. The extraction is pure and the dump goes to stderr, so the
emitted C++ is byte-identical with and without the flag (checked).

## What the corpus says

Measured over the whole compiler (`--root cppsrc`, 321 bodies, 24,144 instructions);
the numbers come from `bun tools/_il_report.mjs il.txt --fold` over the dump
(`--showLinearRepresentation 2> il.txt`):

| | |
| --- | --- |
| bodies | 321 |
| instructions | 24,144 (the emitted C++ is ~23.6k lines, 8,575 of these are `Declare`) |
| `Unsupported` markers | **0** - the instruction set covers every statement shape the lowering produces |
| `Lambda` markers | **2** - only two bodies in the compiler hold a lambda |
| extractor-synthesized slots (`_sm_base<n>`) | 845, and **every one is defined once and used once** |
| ... of those, currently untyped (`?`) | 637 |
| materialised literals | **0** (was 1,486 before operand literals) |
| assignments that copy a slot (`SetVar x, y`) | 21 (the `Move` this replaced) |

Three consequences worth stating plainly:

- **The projection is as complete as the design says.** Everything the emitter can
  read is expressed, and the two bodies that are not are the two lambdas - so the
  closure work (item 5) is a two-body problem in the compiler, not the wall it
  looked like.
- **The synthesized slots are exactly the inlining a backend must do.** They exist
  because a value position held more than a name (`a.f`, `a[i]`, `*p`, `x == "List"
  || y`), which is what the emitter prints inline; since each one has a single use,
  substituting its defining instruction into that use reproduces today's text and
  drops the declaration - and with it the last untyped slot in the frame. So the
  fold is not an optimisation here, it is what makes the IL *equivalent* to the
  statement path, and it is the one pass `LinearCodeGen` needs to compare byte for
  byte.
- **The two shape questions resolved in favour of not changing the output.** With
  literals riding the pool as operands and `Declare` as an instruction, the
  projection still generates **byte-identical** C++ with and without the flag (it is
  pure, and the flag only chooses whether the dump is written), and nothing that
  follows has to churn the goldens just to *reach* the IL.

## Codegen from the IL

**Implemented** (`--linearCodegen` / `--linearCodegenEmit`, `impl_specs/linear-il.md`),
for every function body of the compiler:

| | |
| --- | --- |
| bodies | 321 |
| byte-identical with the statement path | 165 |
| the **same code**, with only the forced braces/indentation left | 154 |
| differing | **0** |
| not expressible | 2 (the two lambda bodies) |

The backend lives in the emitter (`Emitter::emitIlBodyText` and the helpers around
it) and it does not re-spell anything: an operand becomes a leaf `ast::Expr` - a slot
is a name, a constant is its literal, a place is the path it came from, folded back
out of the instruction that built it - and `expr`/`call`/`memberAccess` write the
text. The two paths therefore agree *by construction*, which is what makes the
comparison a check instead of an approximation.

Four rules do the work the statement tree used to do implicitly:

- **Folding**: a `Temp` slot with one definition and one use is inlined at that use
  (it exists only because a value position held more than a name), which is exactly
  where the statement path inlined the same expression. Everything else is declared
  and assigned.
- **`Declare`/`DeclareInit`**: one line with the instruction that follows
  (`Str out = "";`), or a bare one where the hoisting left it. The distinction is
  real because the hoisting turns a declaration's initializer into an assignment.
- **The flags**: `--linearCodegen` compares and reports (the output is unchanged -
  the IL's text is used only where the two agree); `--linearCodegenEmit` *uses* the
  IL's text for every body it could express and the statement text for the rest.
- **The one block the flat form keeps**: where a jump crosses a declaration, C++
  wants a scope (a `goto` may not skip an initialization, MSVC C2362). The backend
  opens exactly that block - the one the statement path keeps - and closes it at the
  label the jump lands on. So "linear" means *no scope the language does not force*,
  which is also why the emitted text still has 154 bodies with braces.

What that buys, verified end to end:

```sh
./cmake-build-debug/simse_transpile.exe --root cppsrc -o a.cpp --linearCodegenEmit
bun build.js --cpp a.cpp --exe il_simse.exe     # the compiler, from the bytecode
./il_simse.exe --root cppsrc -o b.cpp           # it transpiles itself
cmp b.cpp <the statement path's output>         # byte-identical
bun tools/stress.js --simse ./il_simse.exe      # 24/24
```

The two bodies it cannot express yet are the two lambdas in the compiler
(`ns1_collectPackages`, `ns3_driverGatherFiles` pass a lambda to `sort`): until the
closure work lands (item 5), those fall back to the statement path, which is why the
flat form can already be the default for everything else.

## Lambdas: the closure, and the class it is

**Implemented.** A lambda is projected the way `specs/memory-model.md` models it: a
class with one field per captured value and one method (`invoke`, which C++ spells
`operator()`), so the value is an instance and `&lambda` a counted handle to one.

- **The closure is computed**, not guessed: `IlClosure::captures` is the free
  variables of the body - the names it reads that are not its own parameters and not
  names it declares - in first-read order (reproducible, because the field order is
  what every downstream table follows). `makeAdder`'s `(v: Int) -> v + factor`
  captures `factor`; the five lambdas in `stress/lambdas`'s `main` capture nothing.
- **The capture is a field, not a copy in the frame**: inside the body a captured
  name is `GetField this <name>` (and a write is `SetField`), and the construction
  passes the enclosing frame's values: `CallCtor dst, <Class>, captures...`, which the
  backend spells `Class{captures}`. An aggregate is not an expression, so its slot is
  never folded into a use - it gets a slot, like a place.
- **The class is emitted just above the first body that constructs it** (a definition
  precedes its construction, and the functions are emitted in a fixed order, so the
  placement is deterministic), and the method's body is the lambda's own `IlBody` -
  flat, and with `self` spelled as C++'s `this`.

```cpp
struct ns1_makeAdder_closure1 {
    Int factor;
    auto operator()(Int v) {
        auto _sm_expr1 = v + this->factor;
        return _sm_expr1;
    }
};
```

The corpus, with the closure classes in the emitted code:

| | |
| --- | --- |
| compiler bodies: byte-identical / same code / differing / not expressible | 170 / 149 / **2** / **0** |
| the 2 differing | `collectPackages` and `driverGatherFiles`: `[=](...)` in the statement path against the class here - the closure model, by construction |
| `stress/lambdas` (capturing lambda, block-bodied lambda, lambdas as arguments) | builds and prints the golden output; the emitted compiler self-transpiles **byte-identically** (`cmp` against the statement path) and the corpus is 24/24 |

What is *not* projected yet, all outside the corpus: a nested lambda reading an
enclosing lambda's capture (the capture is a field, so it would have to be read into
a slot first), and an explicit capture list or capture by reference (`specs/`
deferred). `&lambda` rides the existing `Box`, i.e. `std::make_shared<Class>(instance)`.

## What stands between here and the flat form everywhere

| # | Item | State | Blocks |
| --- | --- | --- | --- |
| 1 | Every slot has a spelled type | done for the emitting path: a slot with no type is only *folded* (never declared), and an untyped declaration falls back | - |
| 2 | Shadowed source names become unique frame entries | the frame already keeps them apart (analysis is slot-keyed); a rename in the lowering is still wanted before a bytecode backend sees two `x` | codegen |
| 3 | Borrow-of-temporary becomes an owned slot | work, small | codegen |
| 4 | Paths become address instructions (`IndexAddr`/`FieldAddr`) | **done**: 239 `FieldAddr`, 9 `IndexAddr`, folded back into their uses | - |
| 5 | Lambdas become closure classes + an `invoke` method | **done**: the closure is computed, the class carries it as fields, the method is the lambda's own `IlBody` | - |
| 6 | Value-position `&&` / `||` / `?:` materialise into slots through labels | not needed for codegen: `BinaryOp "&&"` prints as C++ `&&`, which already short-circuits; a *bytecode* target would need it (163 synthesized slots are `||`/`&&` chains) | - |
| 7 | Frame size: every slot is live for the whole body | deferred, by design | - |
| 8 | Literals ride the pool as **operand literals** (a negative operand is `pool[-1-n]`) | **done**; 1,486 materialised constants gone | - |
| 9 | Declarations are `Declare`/`DeclareInit` instructions | **done**; the placement is the lowering's, and the backend adds only the scope C++ forces | - |
| 10 | The Simse mirror (`LinearForm.simse` + the IL backend in `Codegen.simse`) | **not started**; the ring cannot dump or emit from the IL yet | the other ring |

## Next

1. Turn the flag into the default. The comparison has 0 bodies it cannot express and
   the only differences left are the closure model (a lambda is a class here, `[=]`
   in the statement path) and the blocks the flat form drops - both deliberate. So
   `--linearCodegen` becomes the only path and the statement emitters retire (the
   goldens are regenerated once, deliberately).
2. `verifyIlBody` (operand counts and kinds from the signature table, jump targets in
   range, a call's argument count against its `IlMethod`, every label defined) - the
   check that is cheap once the IL is the source of truth.
3. The Simse mirror (item 10), which is what makes the *self-hosted* compiler emit
   from the IL too.

## Dropping the statement emitter

The goal: the IL is the *only* input to code generation. Then `if`/`while`/`for`
statement shapes, lambda expressions and the yield lowering all have exactly one
interface to satisfy, an optimization pass has one form to read, and the Simse ring's
port gets one target instead of two.

What is already true, measured:

- **The IL expresses every body of the compiler**: `--linearCodegen` over `cppsrc`
  reports `341 bodies, 187 byte-identical, 152 identical without blocks, 2 differing`.
  The two are the closure model's (`[=]` capture list against the class the language
  specifies), not gaps in the instruction set.
- **A machine is expressible too.** A state machine's method bodies go through the same
  two paths as any other body (`Emitter::emitMachine` -> `emitBodyCheckedAt`, with the
  machine's class as the frame's `self`), and the yield example reports
  `5 bodies, 5 identical without blocks, 0 differing, 0 not expressible`. The example
  transpiled with `--linearCodegenEmit` compiles and prints exactly what the statement
  path prints - both `for` forms, `continue` and `break` included.
- **A compiler built from IL-emitted output works**: `simse_transpile --root cppsrc
  --linearCodegenEmit` then compiling that file gives a compiler that passes the whole
  stress corpus, so the backend is complete for everything the compiler's own source
  needs.

What blocks the switch: **T23 pins the two rings together.** The IL's text is *flatter*
than the statement path's (blocks and the gotos that only they needed are gone: 152 of
341 bodies differ that way) and it spells a lambda as the class the language specifies
(`specs/memory-model.md`) instead of a C++ `[=]`. The stage-1 fixed point compares the
C++ ring's output with the Simse ring's own, so switching one ring alone turns T23 red -
somewhere other than in the file that changed.

So the order is:

1. **`cppsrc/linear/LinearForm.simse`** - the extractor, the printer and the backend,
   over the Simse ring's statements (the same algorithms, one XML dialect: roles instead
   of struct fields). The oracle is the C++ ring's own two dumps: `--showLinearRepresentation`
   over `cppsrc` must be byte-identical between the rings, and then
   `--linearCodegen`'s report must read the same in both.
2. **Switch both rings to the IL** in one change: `Codegen.{cpp,simse}` keeps
   `emitBodyCheckedAt` only, the statement emitters (`emitStmts`/`emitStmt` and the
   Simse equivalents) are deleted, and `--linearCodegenEmit` becomes the behavior with
   no flag. T23 stays green because both rings move together, and the emitted text
   changes deliberately (flatter bodies, closure classes) - the goldens are regenerated
   with `--update` after being read.
3. **Then `yield` in the Simse ring** - which becomes `emitYieldable` plus the lowering
   of `Yield.simse`, with the machine's bodies already just another IL body.
4. Then `smToYield` (`impl_specs/for.md`).

What is *not* a blocker: the statement path is still the default, so every step above is
additive until step 2, and each step has an oracle that fails loudly if a ring drifts
(the stage drivers in `tools/_ring`, `--linearCodegen`'s report, T23, the corpus).

## Open decisions

1. ~~Operator opcodes typed or untyped?~~ **Untyped**, as the user put it: everything
   is already typed in the frame, so `["+", a, b, c]` and a lookup are enough.
2. **`IlMethod`'s fields**: implemented as `name` + `kind` + `argCount` +
   `staticBase` + `returnType` + `argTypes` - more than the minimum, because the
   verifier and the dump both want them and a call site is where a bug shows.
3. **Small integers inline as `Imm`** - *decided the other way*: there is no `Imm`
   operand kind at all. A constant's *text* is what a backend prints, so the pool holds
   it (`0`, `3.14`, `"abc"`, `'a'`, `true`) and the operand references it; a numeric
   operand would be a second spelling of the same thing, and one more kind to resolve.
4. **`SetVar_<Type>` opcodes** - *dropped*, as raised: the destination slot is typed,
   so `SetVar dst, value` says everything, and the value's own kind (a slot or a
   constant) is the operand's, not the opcode's. Six opcodes and `Move` collapsed into
   `SetVar`, plus `SetVar_Null` for the one constant whose spelling comes from the
   type rather than from a literal (`Opt<T>()` vs `nullptr`). A `SetVar_Constant`
   opcode was the alternative; it was left out because `SetVar x, 5` and
   `SetVar_Constant x, pool[5]` would then be two spellings of one instruction, and
   because the `Value` operand kind already states that a position accepts a constant.
5. **The dump's operand style**: resolved names next to the raw index only in the
   `?m5`-style fallbacks (which cannot happen for an instruction the extractor built).
6. ~~Does the IL replace the `Stmt` tree, or is it projected from it?~~ **Projected
   first**, and that is what landed: the extraction is pure and the flag proves the
   emitted C++ is unchanged. The peephole moves onto the IL next, then the emitter.
7. **Literal operands, or materialised constants?** *Decided and implemented: the
   pool carries the literal, and a negative operand in a `Value` position means "the
   literal at `pool[-1 - n]`".* That is the one encoding that keeps what the emitter
   prints *verbatim*: the pool holds a string token's own text (`"abc"`, quotes
   included), an integer's digits and `true`/`false`, which is exactly the C++ the
   statement path spells - so `i > 0` stays `i > 0` instead of `_sm_base1 = 0; ...
   i > _sm_base1`. A `null` is the exception and keeps its own op (`SetVar_Null`),
   because its spelling depends on the expected type (`Opt<T>()` vs `nullptr`) and the
   destination slot is where the IL has that type for certain.
8. **Declarations: a prologue, or a `Declare` instruction?** *Decided and
   implemented: a `Declare` instruction* (`Declare dst=Var`), emitted where the
   lowering left the declaration, so the frame table only *describes* the slots and
   the emitted code keeps the placement it has today. A prologue is simpler, but it
   moves every program `var` to the top of the function - a visible change to the
   emitted code, and one the hoisting deliberately avoids for source-level bindings.
   A backend folds `Declare` + the next instruction when that instruction writes the
   slot it declared (`ilWritesDestination`), which is how a declaration with an
   initializer stays one line.

With 7 and 8 decided that way, codegen from the IL can aim at **byte-identical**
output with the statement path, which is what makes the side-by-side comparison a
verification instead of an approximation: build `LinearCodeGen` behind
`--linearCodegen`, emit every body both ways, and let the corpus say where the two
disagree. Anything that cannot be byte-identical (a lambda body) is a *reported*
fallback to the statement path, never a silent difference.

Both rings have to stay in step: `LinearForm.cpp` landed in the C++ ring first, and
the Simse mirror (`LinearForm.simse`, `lin*`/`il*` naming, the AST as `AstXmlNode`)
has to follow before a Simse build can print the same dump. T23 does not see the
dump while the flag is off, which is why the C++ ring could land alone.
