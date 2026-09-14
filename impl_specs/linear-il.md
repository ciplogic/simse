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

Status: **draft**. The tables, the operand kinds and the instruction list below are
what I would implement; "Open decisions" lists what needs a yes/no first.

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
| `Var` | a frame slot (`vars`) |
| `Text` | a pool entry (`pool`) |
| `Type` | a type (`types`) |
| `Method` | a callee (`methods`) |
| `Label` | a label (`labels`) |
| `Imm` | a literal number, carried in the operand itself |

```
Label            label=Label
Goto             target=Label
IfTrue           cond=Var, target=Label
IfFalse          cond=Var, target=Label

SetVar_String    dst=Var, value=Text            # x = "abc"
SetVar_Int       dst=Var, value=Imm             # x = 42
SetVar_Float     dst=Var, value=Text            # x = 3.14  (the pool is text; the slot's type says Float64)
SetVar_Bool      dst=Var, value=Imm             # x = true
Move             dst=Var, src=Var               # x = y
BinaryOp         dst=Var, op=Text, a=Var, b=Var # a = b + c   -> ["+", a, b, c]
UnaryOp          dst=Var, op=Text, a=Var        # a = !b      -> ["!", a, b]
Cast             dst=Var, src=Var               # a = Enum.toInt() -> static_cast

GetField         dst=Var, base=Var, name=Text   # x = a.f
SetField         base=Var, name=Text, value=Var # a.f = b     -> ["f", a, b]
GetIndex         dst=Var, base=Var, index=Var   # x = a[i]
SetIndex         base=Var, index=Var, value=Var # a[i] = b
IndexAddr        dst=Var, base=Var, index=Var   # an address slot (*T): a[i].f = v
FieldAddr        dst=Var, base=Var, name=Text   # ... and a.f().f = v
Deref            dst=Var, ptr=Var               # copy(*p), the value form

Call             dst=Var, callee=Method, args...=Var       # x = f(a, b)
CallVoid         callee=Method, args...=Var                # f(a, b)
CallIndirect     dst=Var, callee=Var, args...=Var          # x = f(a, b), f a callable slot
CallIndirectVoid callee=Var, args...=Var
CallCtor         dst=Var, type=Type, args...=Var           # Point(1, 2), List<Str>(), a closure

Return           value=Var
ReturnVoid
```

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

`--showLinearRepresentation` (both drivers) prints one body per function: the file and
signature, the tables, then one line per instruction with the operands resolved for the
reader and the source line appended:

```
# cppsrc/codegen/Codegen.simse:54  ns1_cgJoin (List<Str>*, Str) -> Str
types:  0 rtl_List<rtl_Str>*   1 rtl_Str   2 Int   3 Bool
vars:   0 parts:0:Argument  1 separator:1:Argument  2 out:1:Local
        3 i:2:Local         4 _sm_expr1:2:Expression  5 _sm_expr2:3:Expression
pool:   0 "size"  1 "appendStr"  2 ","  3 "+"
labels: 0 L1  1 L2
   0,  Label      0                                   # L1:
   1,  GetField   4, 0, 0                             # _sm_expr1 = parts.size   (line 57)
   2,  BinaryOp   5, 3, 3, 4                          # _sm_expr2 = i < _sm_expr1 (line 57)
   3,  IfFalse    5, 1                                # if (!_sm_expr2) goto L2  (line 57)
  ...
```

The dump is **deterministic** (tables in first-use order, one line per op) because the
build compares outputs byte for byte: it can become a golden category
(`tests/golden/<fixture>.il.expected`) and a *sharper* differential than the emitted
C++ - two rings whose IL dumps agree are far harder to be "accidentally equal" than
two whose C++ text agrees.

## What stands between here and that

| # | Item | Kind |
| --- | --- | --- |
| 1 | Every slot has a spelled type (`auto x` cannot be printed or verified) - the 49 are all in lambda bodies | prerequisite, small |
| 2 | Shadowed source names become unique frame entries | work, mechanical |
| 3 | Borrow-of-temporary becomes an owned slot (the rule above) | work, small |
| 4 | Paths become address instructions (`IndexAddr`/`FieldAddr`) | work, medium |
| 5 | Lambdas become closure classes + an `invoke` method (the closure construction is a `CallCtor`, the call a `Call`/`CallIndirect`) | work, medium |
| 6 | Value-position `&&` / `||` / `?:` materialise into slots through labels | work, medium |
| 7 | Frame size: every slot is live for the whole body; the `Expression` kind is what a later reuse pass needs | deferred, by design |

None of them is a wall; 1 and 3 first, then 5 and 4, then 6, with the dump landing as
soon as 1-3 are in.

## Open decisions

1. ~~Operator opcodes typed or untyped?~~ **Untyped**, as the user put it: everything
   is already typed in the frame, so `["+", a, b, c]` and a lookup are enough.
2. **`IlMethod`'s fields**: `symbol` + `argCount` + `kind` as above - or add the
   receiver's type index and the return type index, so the verifier needs no lookup
   elsewhere? (The receiver type is `vars[args[0]].type` today; the return type is
   only needed for a strict verifier.)
3. **Small integers inline as `Imm`** - or every constant through the pool (uniform,
   one operand kind fewer, one indirection more)?
4. **`SetVar_Float`**: the pool is text, so a float literal is `indexOfText("3.14")`.
   Keep floats in the text pool, or add a numeric pool with kinds?
5. **The dump's operand style**: resolved next to the raw index (as above), or a second
   listing without resolution?
6. **Does the IL replace the `Stmt` tree, or is it projected from it?** My
   recommendation: project first (that is what makes the flag possible in a day), move
   the peephole pass onto the IL next (it is the one that reads instructions), and let
   the emitter follow.
