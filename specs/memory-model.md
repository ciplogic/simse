# Memory model

Status: design baseline — suitable for the first self-hosted implementation.

## Layout types (`class` and `data class`)

Writing `class C { ... }` declares only the **layout** (the shape of data) of
`C`. By itself the name `C` in a type position denotes a *value*: its storage
is inline where the variable lives, and copying a value deep-copies it.

User-facing record declarations use `data class C(...)`; see
`declarations.md`. A `data class` is also an inline value/layout type. Its
constructor creates a value, and `&value` explicitly creates a counted boxed
copy. The `class C { ... }` spelling remains the low-level/layout spelling for
compiler and runtime-defined types.

- `class List<T> { ... }` is a mutable value type backed by a growable inline
  buffer, analogous to C++ `std::vector<T>`. Copying a `List<T>` deep-copies
  its elements and buffer.

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

This is normative: the ABI does not promise 8-byte alignment for `Int64` or
`Float64` fields, pointer fields, or any aggregate member, and code that relies
on the host's default (8-byte) alignment is outside the specification. `Str`
and `&T` are themselves built from 4-aligned pieces (`Str` is a `SmallVector`
of `Char`, `&T` is a counted box), so the rule is uniform across the language.

Status: the bootstrap shims still spell `Str` as `std::string`, `&T` as
`std::shared_ptr` and callables as `std::function`. Those host types are
declared with 8-byte alignment, so 4-byte packing under-aligns them; that is
accepted for now and recorded in `impl_specs/rtl-abi.md` (`SIMSE_NO_PACK4`
reverts to the host layout).

### Arrays

`Array<T>` lives on the heap and behaves like a reference-counted Java/C#-style
array: the variable holds a reference, and assignment shares the allocation
with no element deep copy. The allocation contains the reference count, the
element count, and the elements immediately afterward in one block. `Array<T>`
is already a reference and takes no memory operator. `RawArray<T>` is the
separate unmanaged `T*` form. See `built-in-types.md` for the complete layout.

`Array<T>` is mutable through its elements, but its length is fixed at
construction, like a Java or C# array. Assignment such as `a = b` shares the
same allocation, so changing an element through either handle is visible
through the other. Growing, shrinking, inserting, and removing elements are
not array operations.

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

```text
var value: Point = copy(b)   // deep copy from &Point
var value2: Point = copy(p)  // deep copy from *Point; unsafe if p is invalid
```

Taking a raw pointer and copying through it is unsafe unless the compiler can
prove the pointer is valid for the complete expression. The first
self-hosted implementation may require an explicit `unsafe` block for all raw
pointer creation, dereference, and `copy` operations through `*T`.

## Expression semantics

The memory operators also appear as expressions.

- `b = &a` — **boxes a copy** of `a` into a fresh, non-null counted reference. The box is
  a snapshot, so later mutations of `a` are not reflected in `b` (and
  mutations through `b` do not affect `a`).
- `b = *a` — yields a **raw pointer to `a`**; no copy is made.
  - If `a` is a **value type** (`T`), `b` is a raw pointer (`*T`) to `a`'s own
    storage. Reads/writes through `b` are visible in `a` (and vice versa),
    exactly as in C/C++. This introduces aliasing and a possible
    dangling-pointer hazard if `a` goes out of scope while `b` is still used.
  - If `a` is already a counted reference (`&T`), `b` is a raw pointer to the
    **same box** that `a` manages — equivalent to `shared_ptr<T>::get()`: it
    drops refcount management but points at the same storage.

```text
data class Point(var x: Int, var y: Int)

var a: Point = Point(1, 2)
var b: &Point = &a   // b is a counted reference to a copy of a
var p: *Point = *b   // p is a raw pointer to the same box that b manages
```

### Automatic dereference

Status: required for the first implementation.

Member access, indexing, and method calls through a counted reference (`&T`) or
a raw pointer (`*T`) automatically reach the pointee. For example, if `source`
has type `&Str`, then `source[i]`, `source.size()`, and any member call on
`source` operate on the `Str` it points to, not on the handle. This is a
syntactic convenience only: it does not change the ownership, nullability, or
unsafety rules of the reference, and dereferencing a null/dangling handle is
still unchecked undefined behavior.

## Type aliases

`typealias` names a (possibly composed) type, so memory operators can be given
a readable name. An alias may target any type, including a value type, array,
counted reference (`&T`), raw pointer (`*T`), or callable type. The alias does
not remove or change the target type's ownership, nullability, or safety rules.

```text
typealias PList<T> = &List<T>   // named counted reference to a List<T>
typealias Raw<T> = *T            // named raw pointer; still nullable/unsafe
```

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

`Action<T>` accepts a value of `T`; `PtrAction<T>` accepts a nullable,
non-owning raw pointer to `T`. A pointer parameter does not keep its target
alive, and invoking a `PtrAction<T>` with a null or dangling pointer has the
same unchecked undefined behavior as any other raw-pointer use.

Function types use the same `typealias` construct. A callable alias has a
parameter list followed by `->` and its return type:

```text
typealias Mapper = (Int) -> Str
typealias Reducer = (Int, Int) -> Int
```

The no-return form uses `Unit` as the return type:

```text
typealias Action = (Point) -> Unit
```

`Unit` is the single no-useful-value return type. A function declared with no
return value has an `Action`-compatible type, and reaching the end of such a
function is valid. A function returning any other type must return a value on
every path.

Function aliases are transparent names for callable types and lower to the
runtime's `Func<...>`/`std::function` representation. They may hold named
functions, lambdas, or other compatible callable values. Assignment copies the
callable handle/value according to the backend representation; invoking a null
callable is an unchecked runtime error.

```text
typealias Mapper = (Int) -> Str
typealias Action = (Point) -> Unit

var stringify: Mapper = (value: Int) -> value.toString()
var printPoint: Action = (point: Point) -> {
    println(point.x)
    println(point.y)
}

val text: Str = stringify(42)
printPoint(Point(1, 2))
```

Lambda parameters are typed in the lambda when the expected callable type is
not enough to infer them. A lambda expression captures the local values its body
**references, by value**: the closure gets its own copy of each captured value, so
mutating the original afterwards does not change the closure. A captured counted
reference (`&T`) is copied by value, which copies the handle and therefore
**shares** the same box. Explicit capture lists and capture by reference are
deferred.

The set of captured values is **computed**: it is the free variables of the body -
the names it reads that are not its own parameters and not names it declares. That
set is the closure, and a lambda is modelled as

- a **class** with one field per captured value, and
- one method, `invoke`, whose parameters are the lambda's and whose body is the
  lambda's body; inside it a captured name is a field of the instance.

The callable types of `rtl` (`Func<Ret(Params)>`, and `Unit`-returning ones -
the `IInvocableFunc` / `IInvocableAction` of the conventional one-method
interface) are exactly that one method, so a lambda value *is* an instance of its
class and a call through a callable value is a call of `invoke`. A `&lambda` is a
counted handle to that instance (`&T`), which is what lets a closure outlive the
frame that built it.

A lambda's parameter types come from the explicit annotations, or from the
expected callable type when the lambda is assigned to a callable-typed variable
or passed to a callable-typed parameter. Its return type comes from the expected
callable type, or is inferred from a single trailing expression or a `return`.

```text
typealias Mapper = (Int) -> Int
val doubler: Mapper = (v: Int) -> v * 2
val add10: Mapper = makeAdder(10)   // a closure capturing `10` by value
```

Type aliases are transparent: they do not create a new runtime type, ownership
mode, or layout. Generic aliases are permitted when all referenced type
parameters are declared. A counted reference is the only stable way to share a
mutable object; copying a value never creates sharing implicitly.

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
