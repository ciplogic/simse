# Memory model

Status: design baseline — suitable for the first self-hosted implementation.

## Layout types (`class` and `data class`)

`class C { ... }` declares only the layout (shape of data) of `C`. The name `C` in a type
position denotes a value: its storage is inline where the variable lives, and copying a
value deep-copies it.

Record declarations use `data class C(...)` (`declarations.md`), also an inline value/layout
type. The `class C { ... }` spelling is the low-level/layout spelling for compiler- and
runtime-defined types; `class List<T> { ... }` is a mutable value type backed by a growable
inline buffer, and copying it deep-copies its elements and buffer.

### Alignment and packing

The language's layout model is **4-byte packing**: values are laid out on
4-byte boundaries and no type is aligned to more than 4 bytes.

- Scalar alignment: `Bool`, `Char`, `Int8` 1; `Int16` 2; `Int32`, `Int64`,
  `Float32`, `Float64` 4.
- `*T` (raw pointer) and `&T` (counted reference) 4.
- An aggregate (`data class`, `List<T>`, `SmallVector<N, T>`, `Array<T>`, ...)
  is aligned to the widest alignment of its fields, capped at 4, and its size is
  rounded up to a multiple of that alignment. No padding is inserted to give a
  field more than 4-byte alignment.
- Arrays and container buffers use the packed element size as the stride.

This is normative: the ABI does not promise 8-byte alignment for `Int64` or `Float64`
fields, pointer fields, or any aggregate member, and code relying on the host's default
(8-byte) alignment is outside the specification. `Str` and `&T` are built from 4-aligned
pieces too, so the rule is uniform.

Status: the bootstrap shims spell `Str` as the inline `SmString` (not `std::string`),
`&T` as `std::shared_ptr`, and callables as `std::function`. Those host types are declared
with 8-byte alignment, so 4-byte packing under-aligns them; that is accepted for now
(`impl_specs/rtl-abi.md`; `SIMSE_NO_PACK4` reverts to the host layout).

### Arrays

`Array<T>` lives on the heap and behaves like a reference-counted array: the variable holds
a reference, assignment shares the allocation with no element deep copy, and the allocation
holds the reference count, element count, and elements in one block. It is already a
reference and takes no memory operator; `RawArray<T>` is the separate unmanaged `T*` form
(`built-in-types.md`, `ref-counted-layout.md`).

Its elements are mutable but its length is fixed at construction. Assignment `a = b`
shares the same allocation, so a change through either handle is visible through the
other. Growing, shrinking, inserting, and removing elements are not array operations.

## Memory operators on types

The memory operators apply at the *type level* and define how storage is
reached.

### `&T` — counted reference (reference class)

`&T` is a **counted/shared reference** to a value of layout `T`. Its box uses
the common ref-counted allocation header described in `ref-counted-layout.md`:
`[reference count][typeId][boxed value]`.

- To obtain a `&T`, a `T` value is *boxed* into a ref-counted heap object; the
  handle owns one count.
- Copying a `&T` increments the count; the last handle to drop decrements it,
  and at zero the box is freed.
- The expression `&value` always creates a fresh, non-null counted reference by
  boxing a copy of `value`, analogous to `make_shared`. A variable or field of
  type `&T` may subsequently be assigned `null`; a null handle owns no count
  and cannot be safely dereferenced.
- `&` is not allowed on arrays or other already-reference types (nothing to
  promote), so its operand is a value/layout type.

### `*T` — raw pointer

`*T` is a **raw pointer** to a value of layout `T`. It is a low-level,
unmanaged pointer into the box's storage; it does not participate in
refcounting. Getting a raw pointer out of a counted reference is analogous to
`shared_ptr<T>::get()`.

### Extraction (`copy`)

The underlying value of a reference (`&T`) or a raw pointer (`*T`) is obtained
with the explicit `copy` operation. `copy` copies the value out into a plain
local; it never transfers ownership and never consumes the reference.

Taking a raw pointer and copying through it is unsafe unless the compiler can
prove the pointer is valid for the complete expression. The first
self-hosted implementation may require an explicit `unsafe` block for all raw
pointer creation, dereference, and `copy` operations through `*T`.

The operation is rarely written: every position whose type is known reads a handle through
on its own ("Automatic dereference", below), so `copy` is what the extractor emits.

## Expression semantics

- `b = &a` boxes a copy of `a` into a fresh, non-null counted reference: a snapshot, so
  later mutations of `a` are not reflected in `b` and vice versa.
- `b = *a` yields a raw pointer to `a`, no copy made. For a value `a` it points at `a`'s
  own storage (reads/writes through `b` are visible in `a` and vice versa, with the aliasing
  and dangling hazard of C/C++); for a `&T` `a` it points at the **same box**, equivalent to
  `shared_ptr<T>::get()`.

### Automatic dereference

Status: required for the first implementation.

A **call argument** is converted between a value and a handle when the two are the same
type: a `*T` parameter takes a `T` argument's address, a `*T`/`&T` argument is read
through for a by-value parameter, and `&T` boxes a copy of what it is given. The writer
writes the `*` for a *binding* (`val p: *T = x`), never for a call
(`specs/functions.md`, "Handles at a call").

A **binary operand** is converted the same way, since every operator is on values:
`out + separator` with `separator: *Str` is `out + *separator`. The other operand says the
operation is on values, being either a value of the handle's own pointee (a `Str` next to
the `*Str`) or a handle of that pointee too - `xmlAttr(a, Name) == xmlAttr(b, Name)`
compares the two strings. A handle against a different type, or against `null`, is left
alone (`impl_specs/linear-il.md`).

A **value position whose destination type is known** is converted too: a declaration
(`val name: Str = xmlAttr(n, Name)`), an assignment to a typed slot, a `return`, and a
file-level `var`'s initializer read a `*T`/`&T` through to the `T` the position asks for, so
an accessor can hand back a *borrow* (`xmlAttr` returns a `*Str` into the node's storage)
while a caller that wants a `Str` of its own writes nothing.

Two parameter shapes carry a type the callee's own signature does not fix, and the
conversion reads the argument instead: a generated extension's receiver is an explicit
`this` first parameter, so `Dictionary<K, V>.has(key: K)`'s bare `K` is bound by the
receiver, while a construction (`AstNodeAttribute(kind, value)`) converts its arguments
against the data class's fields. So no call spells a `copy`: what the destination decides,
the extractor emits.

Member access, indexing, and method calls through a counted reference (`&T`) or a raw
pointer (`*T`) automatically reach the pointee: if `source` has type `&Str`, then
`source[i]`, `source.size()`, and any member call on `source` operate on the `Str` it
points to, not on the handle. This is a syntactic convenience only: it does not change the
ownership, nullability, or unsafety rules of the reference, and dereferencing a
null/dangling handle is still unchecked undefined behavior.

### Compound assignment and the step operators

Status: required for the first implementation.

`x op= v` (`+=`, `-=`, `*=`, `/=`, `%=`) and the step forms `x++` / `x--` are a
statement-level shorthand for updating a place in place: the target's *place* is
located once, its current value is read out of that same place, the operation is
folded into it, and the result is written back through it.

The place is located **once**, so a receiver or an index with an effect runs once
(`xs[next()] += 1` calls `next()` once), and **nothing is copied on the way**: a target that
is not a local slot is reached through a raw pointer to the place, never through a copy of
the value.

`i++` and `i--` are the same statement with a `1`: the parser writes `i += 1`
and `i -= 1`. Their value is the assignment's, and an assignment has none, so

- a statement may not begin with `++`/`--`: the **prefix** form is rejected; and
- a step inside an expression (`x = i++`, `f(i++)`) is rejected as well - the
diagnostic names the statement form.

A step on a dereference applies to the **place the `*` names**, not to the
pointer: `*value++` is `*value = *value + 1`, not C's `*(value++)`. Advancing a
pointer is not a language operation.

## Type aliases

`typealias` names a (possibly composed) type. An alias may target any type, including a
value type, array, counted reference (`&T`), raw pointer (`*T`), or callable type. The alias
does not remove or change the target type's ownership, nullability, or safety rules.
`PList<T>` (a counted reference to `List<T>`) and `Raw<T>` are such aliases.

Aliases may be generic. Parameters are declared between the alias name and the
equals sign, and every parameter used on the right-hand side must be declared
there. Generic aliases are instantiated by supplying a type argument:

```text
typealias Box<T> = &T
typealias Action<T> = (T) -> Unit
typealias PtrAction<T> = (*T) -> Unit

typealias PointAction = Action<Point>
typealias PointPtrAction = PtrAction<Point>
```

`Action<T>` accepts a value of `T`; `PtrAction<T>` accepts a nullable, non-owning raw
pointer to `T`, which does not keep its target alive, and invoking it with a null or
dangling pointer is unchecked undefined behavior.

Function types use the same `typealias` construct: a parameter list followed by `->` and
the return type (`typealias Mapper = (Int) -> Str`); the no-return form uses `Unit`
(`typealias Action = (Point) -> Unit`). `Unit` is the single no-useful-value return type:
a function declared with no return value has an `Action`-compatible type and reaching the
end of it is valid, while a function returning any other type must return a value on every
path.

Function aliases lower to the runtime's `Func<...>`/`std::function` representation, holding
named functions, lambdas, or other compatible callables. Assignment copies the callable
handle/value according to the backend representation; invoking a null callable is an
unchecked runtime error.

Lambda parameters are typed in the lambda when the expected callable type is not enough
to infer them. A lambda captures the local values its body references, by value: the
closure gets its own copy of each captured value, so mutating the original afterwards does
not change the closure. A captured counted reference (`&T`) is copied by value, which
copies the handle and therefore shares the same box. Explicit capture lists and capture by
reference are deferred.

The set of captured values is computed: it is the free variables of the body - the names
it reads that are not its own parameters and not names it declares. A lambda is modelled
as

- a class with one field per captured value, and
- one method, `invoke`, whose parameters are the lambda's and whose body is the lambda's
  body; inside it a captured name is a field of the instance.

The callable types of `rtl` (`Func<Ret(Params)>` and the `Unit`-returning `IInvocableFunc`
/ `IInvocableAction`) are exactly that one method, so a lambda value *is* an instance of
its class and a call through a callable value is a call of `invoke`. A `&lambda` is a
counted handle to that instance (`&T`), which lets a closure outlive the frame that built
it.

A lambda's parameter types come from the explicit annotations, or from the
expected callable type when the lambda is assigned to a callable-typed variable
or passed to a callable-typed parameter. Its return type comes from the expected
callable type, or is inferred from a single trailing expression or a `return`.

Type aliases create no new runtime type, ownership mode, or layout. Generic aliases are
permitted when all referenced type parameters are declared. A counted reference is the
only stable way to share a mutable object; copying a value never creates sharing
implicitly.

## Nullability and raw-pointer lifetime

Both `&T` and `*T` variables may contain null. However, the `&value`
construction expression always produces a non-null `&T`; null enters a counted
reference only through an explicit `null` assignment, a default-initialized
variable/field, or an unsafe/FFI boundary. A null `&T` owns no box; a null `*T`
points nowhere. There is no implicit conversion between a reference type and
its `Opt` form. `Opt<&T>` and `Opt<*T>` are useful when the type system must
make absence explicit, but they do not change the underlying reference's
runtime behavior.

`*T` is a nullable, non-owning raw pointer. It does not keep its target alive,
and the language does not provide a general lifetime guarantee for it. A null
or dangling `&T`/`*T` may be carried, copied, and passed onward, but using it to
read, write, or `copy` the target is unchecked and has undefined behavior,
typically a segmentation fault. Raw-pointer operations are intended for FFI,
allocators, and low-level runtime code. The compiler must reject storing `*T`
in ordinary value fields, returning it from a safe function, or allowing it to
escape the scope in which its validity is established. An explicit unsafe
escape hatch may be added later for systems code.

## Open questions remaining before implementation

- Exact syntax for declaring and entering an `unsafe` block.
- Whether the first backend will enforce raw-pointer escape rules statically or
  initially lower them to runtime conventions.
