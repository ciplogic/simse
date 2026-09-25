# The linear IL

The form `impl_specs/linear-lowering.md` builds is already *almost* an instruction list:
control flow is labels, gotos and conditional jumps, every value position is one operation
deep, and every declaration sits once at the top of the body (slot hoisting). What is left
that is not linear: a statement is still a small tree (`Assign` holds a target *path* and a
value, `Call` a callee and N arguments, `IfTrue` a condition), and a lambda is an expression
with a body inside it. The IL removes both, so a later optimization reads one instruction at
a time. The model is Smali's (`.method` / `.registers` / `.local` / `const-string` next to the
`invoke-*` family).

Status: **implemented, and the only codegen.** `cppsrc/linear/LinearForm.kt` holds the model
(projection, printer, dump flag) and `cppsrc/codegen/IlCodeGen.kt` the backend
(`emitIlBodyText` / `ilEmitOps` / `emitClosureClass`); the tables, operand kinds and
instruction list below are what the code does. `--showLinearRepresentation` dumps the IL; the
statement emitter and its `--linearCodegen` / `--statementsCodegen` flags are deleted, so what
the IL cannot spell is a hard error, not a fallback. "What the corpus says" is the measured
state over the whole compiler.

## Body

```
IlBody {
    file:    Str                # for the source comments (`// file:line`)
    types:   List<Str>          # every type this body mentions
    vars:    List<IlVar>        # the frame
    pool:    List<Str>          # text: string literals, field names, operators
    inferred: Map<Str, Type>    # what the *type pass* proved for every name
    methods: List<IlMethod>     # every callee this body calls
    labels:  List<Str>          # label names; an operand indexes this
    ops:     List<IlOp>         # the instructions
    lines:   List<Int>          # source line per instruction (parallel to `ops`)
}

IlVar    { name: Str; typeIndex: Int; kind: IlVarKind }
IlMethod { name: Str; kind: IlMethodKind; argCount: Int; staticBase: Int;
           returnType: Int; argTypes: List<Int> }
IlOp     { kind: IlOpKind; operands: List<Int> }

IlVarKind    = Argument | Local | Expression
IlMethodKind = Function | Method | Native | Constructor
IlOpKind     = Label | Goto | IfTrue | IfFalse | ...   # one per row of the table below
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
  before the IL, by the lowering's rename (`renameShadowed`,
  `impl_specs/linear-lowering.md`); the dump may keep the original name.
- **Text is a pool index**: a string literal, a field name, an operator spelling
  (`"+"`), a native's symbol. A callee is **not** here - it has its own table (below) -
  so a call operand is never ambiguous with a string.
- **An opcode is an enum, not a string** (`IlOpKind`), and the signature table is keyed
  by it: a row *is* its `IlOpKind`, so `ilSignature(kind)` indexes the table and reading
  a row's operand kinds is a lookup, not a search over spellings. The spelling
  (`"CallIndirectVoid"`) exists only for the dump (`ilOpKindText`) and for diagnostics;
  the backend switches on the enum and never compares text. An instruction the
  extractor cannot build is `IlOpKind::Unsupported` - a value in the enum, not a
  missing string.
- **The IL infers nothing.** Every slot has the type the type pass gave it, and the
  backend decides spelling from those types (`+` on two `Str`s is an append,
  `simse_addressOf(x)` vs `x.get()` follows `*T` vs `&T`): that is a *lookup*, not an
  inference. What the IL needs instead is a **verifier** - operand counts and kinds
  from the signature table, jump targets in range, a call's argument count against its
  `IlMethod`, a `BinaryOp` whose operand types make sense.
- **`inferred` is the type pass's whole record, and it says more than `vars` does.** A
  slot holding a state machine is `..T`, a type a *declaration* is never written with
  (the emitted C++ types it `auto`, and `cppsrc/linear/Yield.kt` relies on that to reject a
  `for` over a machine crossing a `yield`), so `vars[i].typeIndex` is `?` for it. The frame
  still has to know: a `for` wraps what it iterates in `iter()` and on a machine that wrap
  is the *identity*, a decision only the receiver's type can make (`impl_specs/for.md`). So
  the body carries what the pass proved, and a backend seeds its spelling frame from it -
  declared slot types seeded after and winning, since they are the spelled ones
  (`ilSeedFrameTypes`).
- **`Label` is an instruction**, so a label's position *is* its position in `ops`, and
  a jump carries a **label index**, not an op index:

  ```
  12,  Label   3                      # L4:  (labels[3] == "L4")
  13,  IfFalse 5, 3                   # if (!(vars[5])) goto L4
  ```

  That is why labels are kept: inserting or removing instructions does not invalidate an
  operand, which matters because the peephole and the folding rewrite the list. A backend
  prints `L4:;`/`goto L4;` and resolves nothing; a bytecode backend resolves a label index
  to an offset in one pass over `ops`.
- **`lines` is not optional**: the emitter's `// file:line` comments, the goldens and
  every diagnostic come from it. One line per instruction is enough - a body is in one
  file.

## Operand kinds

An operand is an `int`; *what it indexes* is the opcode's signature, stated once here and read
by the verifier and the printer alike. The table is keyed by the opcode itself
(`ilSignature(op.kind)` is the row, one row per `IlOpKind`). **The first `Var` operand of an op
that produces a value is its destination** (Smali's convention), and the table says which ops
produce a value.

| Kind | Indexes |
| --- | --- |
| `Var` | a frame slot - a **destination**, or a place read (`GetField`'s base) |
| `Value` | a frame slot **or a constant** (a negative operand: `pool[-1-n]`) |
| `Text` | a pool entry (`pool`) |
| `Type` | a type (`types`) |
| `Method` | a callee (`methods`) |
| `Label` | a label (`labels`) |

The split keeps the opcodes free of types - the frame says what a slot holds, so `SetVar`
needs no `_Int`/`_String` suffix and a constant rides the instruction as text the backend
prints verbatim - and states which positions must be slots (`Var`) and which accept a
constant (`Value`): what a verifier and a backend need, nothing more.

**One instruction is one operation, over declared slots.** Every operand is a frame slot or a
constant, never an expression, and every slot a body uses is declared by the frame
(`Declare`) with a type: a value position the statements did not hold in a slot of its own (a
read's base, a call's receiver, a borrow's operand) gets one from the extractor, typed by the
same rules the type pass uses (`sema::typeOfExpr`), and the declaration joins the frame at the
top of the instruction list. So `attributes[i].size()` is three instructions, never one
expression:

```
IndexAddr        _sm_base1, attributes, i       # _sm_base1: *Attribute
GetField         _sm_expr1, _sm_base1, "n"      # _sm_expr1 = _sm_base1->n
Call             _sm_expr2, size, _sm_base1     # _sm_expr2 = size(_sm_base1)
```

The **place** instructions are what makes that possible without copying: `IndexAddr`
and `FieldAddr` write the *address* of a place into a `*T` slot, so a call that mutates
its receiver reaches the original, a read copies nothing, and a write through the place
is a write to the original. A base whose *value* is already a handle (`&T`/`*T`) needs
no address instruction - the handle is the base - which is also why an unnameable base
keeps the value form: the address of something whose type is unknown is not a thing the
emitted C++ can spell.

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
```

### The conversion: one operation, spelled by its types

`Box`, `Deref` and `CopyValue` are **one operation seen from three call sites** - convert
the value in `src` to the type of `dst`. All three opcodes stay accepted and must spell the
same text for the same pair of types: the instruction *means* the pair, not the opcode, and
the backend reads the spelling from the types.

| `src` | `dst` | C++ | opcode |
| --- | --- | --- | --- |
| `T` | `T` | `(x)` - a copy, C++ value semantics | `CopyValue` |
| `T` | `*T` | `&x` / `simse_addressOf(x)` - the address, no copy | `Deref` |
| `T` | `&T` | `std::make_shared<T>(x)` - a fresh box around a copy | `Box` |
| `&T` | `*T` | `(x).get()` - the pointer inside the box | `Deref` |
| `&T` | `T` | `*(x)` - the value behind the box | `CopyValue` |
| `*T` | `T` | `*x` - a load | `CopyValue`, and `Deref` (which spelled it `*x`) |

Rows not in the table are **illegal**, which the verifier must reject rather than C++: `*T` ->
`&T` is the one that matters (adopting a raw pointer into a box would claim an ownership
nobody granted), and `src == dst` needs no instruction. An illegal pair currently leaks out
as a C++ error in the generated file (`cannot convert from 'AstXmlNode *' to 'const
AstXmlNode &'`) instead of a diagnostic.

Two things the unification must **not** blur, because they are per row:

- **`&T` -> `*T` can be null and `*T` -> `T` can dangle.** Both are the caller's hazard
  and belong to whatever later guards raw pointers (`specs/memory-model.md`); narrowing
  the opcode set is not what makes them safe.
- **`T` -> `&T` is a *snapshot*.** It boxes a copy, so later writes to `x` are not seen
  through the handle and vice versa. It is the only row that allocates.

The table is also what the implicit copy becomes: wherever a `T` is required and the
expression has type `*T`/`&T`, the same instruction is inserted - no new opcode, and no
`copy` in the language. `copy(v)` on a value is the first row (the identity), so `copy`
disappears without the IL gaining anything to replace it - `cppsrc/**/*.kt` spells no
`copy(...)`, and every conversion folds into the destination that asks for it. The positions
that ask are a call argument (`specs/functions.md`, "Handles at a call") and a **binary
operand** whose fellow operand is a value (`specs/memory-model.md`): `out + separator` with
`separator: *Str` is `CopyValue` into a slot and then `out + that` - the same row the
explicit `*separator` would spell.

**A parameter the call cannot type is read from the argument.** Three parameter shapes
carry a type the callee's own signature does not fix, so the table above cannot be entered
from `param` alone; the extraction resolves them from the *argument* instead:

- **A native extension's receiver**, which the declaration spells as an explicit `this`
  first parameter (`...has<K, V>(this: Dictionary<K, V>, key: K): Bool`).
  Two consequences beyond the conversion: the fact's own `receiver` is *empty*, so
  `callTarget` counts such a declaration as a member through
  `extensionReceiver`/`receiverParams`/`isExtensionDecl` - which is also what tells two
  same-named overloads apart (`append` on a `List<T>` and on a `Str`), and `functionReturn`
  must *not* match a plain call to one, or `fun find(...)` picks up `Str.find`'s signature -
  and the parameters an argument converts against start *after* the receiver, which is what
  `packStart` and the pack-arity test count.
- **A bare type parameter** (`key: K`, `value: T`), which the *receiver* binds and no
  argument can be compared against - a slot typed `K` could not even be declared outside
  the callee's template. Its form is a value, so a handle argument is read through to the
  pointee the argument itself names (`isBareTypeParam`, then the `*T` -> `T` row): this is
  what makes `names.append(xmlAttr(param, Name))` a copy of the string and not a complaint
  about `Str *` against `const Str &`.
- **A construction** the extractor sees as a plain call - a prelude data class, whose C++
  is the struct's own constructor and whose arguments are its `Field` nodes - converts
  against those fields by the same rule (`dataClassDecl`).

What stays *out* of the conversion is the type itself: `List<Int>` against a `*List<Str>`
parameter is the type error it always was. Only the handle is inferred, never the type.

**The destination's type is what spells the conversion, and the emitter reads it there.**
Every instruction whose operand is a value with a declared destination type already says
which row it is, so `emitIlBodyText` reads the spelling off the two types rather than off
the opcode (`needsReadThrough`): a `*T`/`&T` spelled where the destination, the returned
type, the assigned slot or a static's declared type is a `T` is read through, `*(x)`, and
nothing else in that position has to be said. This is what lets a **borrowing accessor**
work: `xmlAttr` returns `*Str` - the address of the attribute's own storage, no string
constructed (`ns2_xmlAttr` is a pointer walk with one `simse_addressOf`) - and its callers
are unchanged, because a `Str` is what they ask for. The two operand sides must agree:
`xmlAttr(a, Name) == xmlAttr(b, Name)` is a *string* comparison, a pair of handles of the
*same* pointee is read through on both sides (`LinearForm.kt`'s `binaryOperand`), and a pair
of *different* handles - and a handle against `null` - is left as it is.

The rest of the instruction list, unchanged:

```
GetField         dst=Var, base=Var, name=Text     # x = a.f
SetField         base=Var, name=Text, value=Value # a.f = b     -> ["f", a, b]
GetIndex         dst=Var, base=Var, index=Value   # x = a[i]
SetIndex         base=Var, index=Value, value=Value  # a[i] = b
IndexAddr        dst=Var, base=Var, index=Value   # the address of a[i] (`ldelema`)
FieldAddr        dst=Var, base=Var, name=Text     # the address of a.f (`ldflda`)
GetStatic        dst=Var, name=Text               # an enum member or a file-level var
GetStaticAddr    dst=Var, name=Text               # the *address* of a file-level var
SetStatic        name=Text, value=Value

Call             dst=Var, callee=Method, args...=Value      # x = f(a, b)
CallVoid         callee=Method, args...=Value               # f(a, b)
CallIndirect     dst=Var, callee=Var, args...=Value         # x = f(a, b), f a callable slot
CallIndirectVoid callee=Var, args...=Value
CallCtor         dst=Var, type=Type, args...=Value          # Point(1, 2), List<Str>(), a closure
Pack             dst=Var, values...=Value                   # List<Str>{a, b}: a container from values (`newarr` + fill)
Concat           dst=Var, parts...=Value                    # the fused `Str` concatenation (`concat`, MergeConcat.kt)

Return           value=Value
ReturnVoid

Lambda           dst=Var            # a body still inside an expression (marker)
Unsupported      dst=Var, why=Text  # anything the extractor cannot express (marker)
```

The two markers are instructions *on purpose*: a shape the extractor cannot write down shows
up as a gap in the dump and a count in the verifier rather than being silently dropped, so a
body whose dump has neither is fully covered by the IL.

- **`BinaryOp` carries the source operator and nothing about types.** `a = b + c` is
  `["+", a, b, c]` whether the operands are `Int`s or `Str`s; the frame's types decide
  what the backend prints, and an optimizer can read the resolved operation from the
  operand types the same way.
- **Calls to natives and to methods are the same op.** A *native* is a `Method` entry
  whose kind says so (the backend's receiver convention differs there, the rule T47
  already follows); a *method* puts the receiver in `args[0]`, whose slot type (`*T` or
  `&T`) decides `simse_addressOf(x)` vs `x.get()`.
- **`CallIndirect` is the interface tier.** A callable slot (a lambda, a
  function-typed `Local`, an `&lambda` handle) is called through the slot, which is
  exactly Smali's `invoke-interface` next to `invoke-static`.
- **`Cast` exists because the language has exactly one cast**: `Enum.toInt()` is
  `static_cast<Int>(x)`, and `Enum.fromInt(v)` is a *call* to a synthesized helper
  (`ns1_simse_NameKind_fromInt`), so the method table covers it. Every other conversion
  (`toInt`, `toString`, `toFloat`, an integer widening) is a call.
- **Address slots** (`IndexAddr`, `FieldAddr`) are .NET's `ldelema`/`ldflda` pair: a path
  becomes a value of type `*T`, the alias semantics stay visible, and `a[i].append(x)` is
  a call with an address operand.
- **Borrow of a temporary**: `*f()` stays inline (`simse_addressOf(f())`, valid for the call
  it is passed to). An instruction list cannot nest, so the *owned* value moves to a slot
  first (`dst = Call f`, then the address of that slot) - which *extends* the temporary's
  life to the slot's, i.e. safe, never dangling. The one place the IL is deliberately
  blunter than the C++ it emits.
- **`Pack` builds a container from values in one instruction** - the bytecode's
  `newarr`/`fill-array-data`, and the reason a list literal is one operation. Its
  destination's *type* says which container it is, so the instruction carries no type
  operand: `List<Str>{a, b, c}` is `Pack dst, a, b, c` with `dst : List<Str>`. Two
  shapes produce it: the list literal `listOf<Str>(a, b, c)` (a call the extractor turns
  into the construction - `CallCtor` stays `List<Str>()`, the empty list, and
  `List<T>(n)` stays the RTL's count construction, `specs/containers.md`), and a call
  that *packs its trailing arguments* into a last parameter that is a `List<T>` or
  `*List<T>` (`specs/functions.md`), where a `*List<T>` parameter takes the address of
  the fresh slot (`Pack` into a `_sm_base` slot, then `Deref`) so not even the list is
  copied. A backend spells it as the RTL's initializer-list construction, which is what
  makes a packed list of up to four elements allocate nothing (`List` is
  `SmallVector<T, 4>`).
- **`Concat` is the fused string concatenation** (`cppsrc/linear/MergeConcat.kt`). The
  language's `+` is binary, so `a + b + c` is two `BinaryOp`s with a `Str` temporary
  between them and one allocation per link, and an `fmtStr` whose format is a literal is
  a `Pack`/`Deref`/`Call` that re-scans that format at run time. A post-pass over the
  instruction list - the one place a slot's type and a chain's shape are both visible -
  merges the whole chain, and the split format, into *one* instruction over every part,
  which the backend *expands* into a length sum, one `resize` and one slot write per part
  (the Java 9 `StringConcatFactory` shape, `ilConcatStatements` in
  `cppsrc/codegen/IlCodeGen.kt`, `cppsrc/rtl/_res.md`'s `strcat` section): no call, so a
  *literal* part contributes a compile-time integer to the sum and a destination that is
  the chain's own first part is appended onto in place. It declines rather than guess - a
  `+` operand that is a number (`s + n` appends one *byte*), a format that is not a
  literal or holds an escape, a `+`-count that does not match the packed items, a
  destination the chain reads somewhere but first - and leaves the code as it was, where
  the runtime's own `fmtStr` still answers.
- **A call argument's handle is inferred by the extractor** (`specs/functions.md`,
  "Handles at a call"): the instruction list gets a `Deref` (an address, or a counted
  reference's `.get()`), a `CopyValue` (a copy of a pointee) or a `Box` (a boxed copy)
  between the argument and the call - one more instruction, never a change of the
  callee. The types have to match: the conversion is skipped when the argument's
  pointee is not the parameter's, which leaves the call the type error it was.

- **A compound assignment is a read, a fold and a write through the same place.**
  `x op= v` (`+= -= *= /= %=`) and the step forms (`i++`/`i--`, which the parser writes as
  `+= 1`/`-= 1`) lower per target shape, and every shape locates the place *once*
  (`specs/memory-model.md`, "Compound assignment and the step operators"):

  | target | instructions |
  | --- | --- |
  | a local slot `i` | `BinaryOp i, op, i, v` - one instruction, no address |
  | a closure field | `GetField t, self, name` · `BinaryOp u, op, t, v` · `SetField self, name, u` |
  | a file-level `var` | `GetStatic t, name` · `BinaryOp u, op, t, v` · `SetStatic name, u` |
  | `a.f` | `GetField t, base, f` · `BinaryOp u, op, t, v` · `SetField base, f, u` |
  | `a[i]` | `GetIndex t, base, i` · `BinaryOp u, op, t, v` · `SetIndex base, i, u` |
  | `*p` | `Deref t, p` · `BinaryOp u, op, t, v` · `Store p, u` |

  `base` is what the plain write already uses (`receiverOf`: `FieldAddr`/`IndexAddr`/
  `GetStaticAddr` for an inline value, the slot or the handle itself for a name), so a
  write cannot land in a copy - and the base and the index are computed *once* and shared
  by the read and the write, so an index with an effect runs once. The `Deref` in the
  last row is a load (its operand is a `*T`), the same instruction the conversion table
  above spells `*x`.

## Printing it

`--showLinearRepresentation` prints one body per function: the file and signature, the tables,
then one line per instruction with the operands resolved and the source line appended. Real
output, verbatim:

```
# cppsrc/codegen/Codegen.kt:54  ns1_cgJoin (*List<Str> parts, Str separator) -> Str
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

A declaration followed by the instruction that writes it is one line in the emitted C++
(`Str out = "";`, `ilWritesDestination`); a declaration left behind by the slot hoisting has no
instruction of its own and stays a bare `Str _sm_expr1;`.

(`methods:` shows `name:kind:argCount`, plus `:static=<type>` for a static call and
`:ret=<type>` when the call has a result; a `Method`'s receiver is its first
argument, so `size:Method:1` is `parts.size()`.)

The dump is **deterministic** (tables in first-use order, one line per op) because the
build compares outputs byte for byte, so it can become a golden category
(`tests/golden/<fixture>.il.expected`). The extraction is pure and the dump goes to
stderr, so the emitted C++ is byte-identical with and without the flag (checked).

## What the corpus says

Measured over the whole compiler (`--root cppsrc`); the numbers come from
`bun tools/_il_report.mjs il.txt` over the dump
(`--showLinearRepresentation 2> il.txt`):

| | |
| --- | --- |
| bodies | 545 |
| instructions | 38,151 (14,268 of them are `Declare`) |
| `Unsupported` markers | **0** - the instruction set covers every statement shape the lowering produces |
| `Lambda` markers | **0** - a lambda is a closure construction (`CallCtor`) |
| extractor-synthesized slots (`_sm_base<n>`) | 1,595 |
| ... of those, untyped (`?`) | **93** (a `for`'s machine slot is one: the lowering built its class, so no rule names it - the count of them moves with the number of `for` loops in the sources) |
| ... folded at their single use | 15 |
| place instructions (`FieldAddr`/`IndexAddr`/`GetStaticAddr`) | 944 / 139 / 9 - each a declared `*T` slot |
| `Pack` (a container built from values) | 18 - the compiler's own list literals (`List<IlSignature>(...)`, `List<Str>(...)`, ...), one instruction each |
| materialised literals | **0** (was 1,486 before operand literals) |
| assignments that copy a slot (`SetVar x, y`) | 408 |

- **A synthesized slot is a frame slot, not a hole in the shape.** It exists because a value
  position held more than a name (`a.f`, `a[i]`, `*p`); the type rules name it in all but 25
  cases over the compiler, so it is declared with the frame and read by name wherever it is
  used.

## Codegen from the IL

The emitter reads the instruction list for every body. Over the whole compiler source set
(`--root cppsrc`, 502 bodies) the record was:

| | |
| --- | --- |
| byte-identical with the statement path | 276 |
| the **same code**, with only the forced braces/indentation left | 224 |
| differing (the closure model: a class here, `[=]` in the statement path) | 2 |
| not expressible | **0** |

The backend does not re-spell anything: an operand becomes a leaf node - a slot is a
name, a constant is its literal - and `expr`/`call`/`memberAccess` write the text. A
place instruction is the one operand that has to be *built* rather than named: its text
is the address of what it names (`&x`, or `simse_addressOf(...)`), because that is what
the slot holds.

Four rules do this:

- **One instruction, one statement.** `attributes[i].size()` is an address instruction, a read
  and a call - three lines of C++, one operation each - so a backend never reconstructs an
expression.
- **`Declare`/`DeclareInit`**: a slot with a spelled type is declared with the frame
  (`T name;`, at the top of the body, where every `Declare` of a typed slot stands); a
  slot the type rules could not name is declared where its single definition is
  (`auto name = <value>;`), which is also the only place a value is ever inlined at its
  use - a shape with no type of its own, such as a bare `null`, prints where it is read.
  Either way the *value* is assigned where the instruction stands, and where a jump
  crosses such a declaration the backend opens the one block C++ requires
  ([stmt.dcl]/3, and that is the only reason a body has braces).
- **The flags**: `--showLinearRepresentation` (dump only) is the only one left;
  `--statementsCodegen` and `--linearCodegen` are gone, and a body the IL cannot spell fails
  with the reason instead of falling back.
- **The one block the flat form keeps**: where a jump crosses a declaration, C++
  wants a scope (a `goto` may not skip an initialization, MSVC C2362). The backend
  opens exactly that block and closes it at the label the jump lands on. So "linear"
  means *no scope the language does not force*, which is also why the emitted text
  still has 154 bodies with braces.

Verified end to end:

```sh
./cmake-build-debug/simse_transpile.exe --root cppsrc -o a.cpp   # from the IL
bun build.js --cpp a.cpp --exe il_simse.exe     # the compiler, from the bytecode
./il_simse.exe --root cppsrc -o b.cpp           # it transpiles itself
cmp b.cpp a.cpp                                 # byte-identical
bun tools/stress.js --simse ./il_simse.exe      # 30/30
bun tools/bootstrap.js                          # and the fixed point holds (~0.8 s)
```

The port itself cost no runtime speed: an IL-emitted compiler against a
statement-emitted one measured 0.95 s against 0.93 s, best of 3, release.

The two differing bodies are the two lambdas in the compiler (`ns1_collectPackages`,
`ns3_driverGatherFiles` pass a lambda to `sort`): there the statement path's `[=]`
becomes the closure class the language specifies, which is the one difference the model
owns - and it is the text that is emitted, so a lambda *is* an instance of its class in
the output too.

## Lambdas: the closure, and the class it is

A lambda is projected the way `specs/memory-model.md` models it: a class with one field per
captured value and one method (`invoke`, which C++ spells `operator()`), so the value is an
instance and `&lambda` a counted handle to one.

- **The closure is computed**, not guessed: `IlClosure::captures` is the free variables of the
  body - the names it reads that are not its own parameters and not names it declares - in
  first-read order (reproducible, because the field order is what every downstream table
  follows). `makeAdder`'s `(v: Int) -> v + factor` captures `factor`; the five lambdas in
  `stress/objects`' `lambdas` part capture nothing.
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
| the 2 differing | `collectPackages` and `driverGatherFiles`: the closure class against the statement path's `[=](...)` - the closure model, by construction |
| `stress/lambdas` (capturing lambda, block-bodied lambda, lambdas as arguments) | builds and prints the golden output; the emitted compiler self-transpiles **byte-identically** and the corpus is 28/28 |

What is *not* projected yet, all outside the corpus: a nested lambda reading an
enclosing lambda's capture (the capture is a field, so it would have to be read into
a slot first), and an explicit capture list or capture by reference (`specs/`
deferred). `&lambda` rides the existing `Box`, i.e. `std::make_shared<Class>(instance)`.

## What stands between here and the flat form everywhere

| # | Item | State | Blocks |
| --- | --- | --- | --- |
| 1 | Every slot has a spelled type | **done for the frame**: the extractor asks `sema::typeOfExpr` for the slots it synthesizes, so 1,611 of the compiler's 1,636 are declared with the frame and 25 stay `?` (a shape with no type of its own, such as a bare `null`), which a backend folds at its single use | - |
| 2 | Shadowed source names become unique frame entries | the frame already keeps them apart (analysis is slot-keyed); a rename in the lowering is still wanted before a bytecode backend sees two `x` | codegen |
| 3 | Borrow-of-temporary becomes an owned slot | work, small | codegen |
| 4 | Paths become address instructions (`IndexAddr`/`FieldAddr`) | **done**: 949 `FieldAddr`, 216 `IndexAddr`, 9 `GetStaticAddr` - declared `*T` slots the instructions read by name | - |
| 5 | Lambdas become closure classes + an `invoke` method | **done**: the closure is computed, the class carries it as fields, the method is the lambda's own `IlBody` | - |
| 6 | Value-position `&&` / `||` / `?:` materialise into slots through labels | not needed for codegen: `BinaryOp "&&"` prints as C++ `&&`, which already short-circuits; a *bytecode* target would need it (163 synthesized slots are `||`/`&&` chains) | - |
| 7 | Frame size: every slot is live for the whole body | deferred, by design | - |
| 8 | Literals ride the pool as **operand literals** (a negative operand is `pool[-1-n]`) | **done**; 1,486 materialised constants gone | - |
| 9 | Declarations are `Declare`/`DeclareInit` instructions | **done**; the placement is the lowering's, and the backend adds only the scope C++ forces | - |
| 10 | The IL backend (`LinearForm.kt` + `IlCodeGen.kt`) | **done**: the extractor, the printer, the backend (`emitIlBodyText`/`ilEmitOps`/the closure classes) and the two flags | - |

## Next

1. `verifyIlBody` (operand counts and kinds from the signature table, jump targets in
   range, a call's argument count against its `IlMethod`, every label defined).
2. The 25 untyped slots in the compiler, one shape at a time: a bare `null` in a value
   position is the bulk of them, and it needs the *expected* type - which a call's
   signature could supply where the type rules cannot. Each one closed removes a fold and
   makes another body purely instructions.
3. A *bytecode* target, if one is ever wanted: the instruction list is already one
   operation per instruction over declared slots, so what a VM would need on top is a
   register allocator (the frame keeps every slot live for the whole body, by design) and
   the value-position `&&`/`||` materialisation (item 6 below).

## Dropping the statement emitter

The IL is the *only* input to code generation. Measured:

- **The IL expresses every body of the compiler**: over `cppsrc` the report is
  `502 bodies, 276 byte-identical, 224 identical without blocks, 2 differing, 0 not
  expressible`. The two differences are the closure model's (`[=]` capture list against
  the class the language specifies), not gaps in the instruction set.
- **A machine is expressible too.** A state machine's method bodies go through the same
  paths as any other body (`emitMachine` -> `emitBodyCheckedAt`, with the machine's class
  as the frame's `self`), and `stress/yield` reports `5 bodies, 5 identical without
  blocks, 0 differing, 0 not expressible` - the emitted machine compiles and prints the
  expected output for both `for` forms, `continue` and `break` included.
- **A compiler built from IL-emitted output works**: transpiling `cppsrc` (the default
  now) and compiling that file gives a compiler that passes the whole stress corpus and
  reproduces its own source byte-for-byte - so the backend is complete for everything the
  compiler's own source needs.

`yield` is done (`emitYieldable`/`emitMachine` plus the lowering of `Yield.kt`, with the
machine's bodies already just another IL body - `stress/yield`), and `iter`
(`impl_specs/for.md`) is still open.

## Open decisions

1. ~~Operator opcodes typed or untyped?~~ **Untyped**: everything is already typed in the
   frame, so `["+", a, b, c]` and a lookup are enough.
2. **`IlMethod`'s fields**: implemented as `name` + `kind` + `argCount` +
   `staticBase` + `returnType` + `argTypes` - more than the minimum, because the
   verifier and the dump both want them and a call site is where a bug shows.
3. **Small integers inline as `Imm`** - *decided the other way*: there is no `Imm` operand
   kind. A constant's *text* is what a backend prints, so the pool holds it (`0`, `3.14`,
   `"abc"`, `'a'`, `true`) and the operand references it; a numeric operand would be a second
   spelling of the same thing, and one more kind to resolve.
4. **`SetVar_<Type>` opcodes** - *dropped*: the destination slot is typed, so
   `SetVar dst, value` says everything, and the value's own kind (a slot or a
   constant) is the operand's, not the opcode's. Six opcodes and `Move` collapsed into
   `SetVar`, plus `SetVar_Null` for the one constant whose spelling comes from the
   type rather than from a literal (`Opt<T>()` vs `nullptr`). A `SetVar_Constant`
   opcode was the alternative; it was left out because `SetVar x, 5` and
   `SetVar_Constant x, pool[5]` would then be two spellings of one instruction, and
   because the `Value` operand kind already states that a position accepts a constant.
5. **The dump's operand style**: resolved names next to the raw index only in the
   `?m5`-style fallbacks (which cannot happen for an instruction the extractor built).
6. ~~Does the IL replace the `Stmt` tree, or is it projected from it?~~ **Projected**: the
   extraction is pure and the flag proves the emitted C++ is unchanged.
7. **Literal operands, or materialised constants?** *Decided: the pool carries the literal,
   and a negative operand in a `Value` position means "the literal at `pool[-1 - n]`".* That
   is the one encoding that keeps what the emitter prints *verbatim*: the pool holds a string
   token's own text (`"abc"`, quotes included), an integer's digits and `true`/`false`, which
   is exactly the C++ emitted verbatim - so `i > 0` stays `i > 0` instead of `_sm_base1 = 0;
   ... i > _sm_base1`. A `null` is the exception and keeps its own op (`SetVar_Null`), because
   its spelling depends on the expected type (`Opt<T>()` vs `nullptr`) and the destination slot
   is where the IL has that type for certain.
8. **Declarations: a prologue, or a `Declare` instruction?** *Decided: a `Declare`
   instruction* (`Declare dst=Var`), and the *prologue too*: `hoistSlots` moves every
   declaration to the top of the body and turns its initializer into an assignment where it
   stood, so a body has one scope and the emitted code is a prologue by construction
   (`impl_specs/linear-lowering.md`, "Slot hoisting: one scope per body"). The `Declare` op
   is what is left for the slots that *stay* in place - the ones the type pass could not spell
   in full, where `auto x;` is not a declaration. A backend folds `Declare` + the next
   instruction when that instruction writes the slot it declared (`ilWritesDestination`), which
   is how a declaration with an initializer stays one line.
