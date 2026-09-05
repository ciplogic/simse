# Memory model

Status: draft — being defined interactively.

## Layout types (`class`)

Writing `class C { ... }` declares only the **layout** (the shape of data) of
`C`. By itself the name `C` in a type position denotes a *value*: its storage
is inline where the variable lives, and copying a value deep-copies it.

- `class List<T> { ... }` is a mutable value type backed by a growable inline
  buffer, analogous to C++ `std::vector<T>`. Copying a `List<T>` deep-copies
  its elements and buffer.

### Arrays

Arrays live on the heap and behave like Java/C# arrays: the variable holds a
reference, the elements are a heap allocation, and assignment shares the
reference (no deep copy). Arrays are already a reference; they take no memory
operator.

## Memory operators on types

The memory operators apply at the *type level* and define how storage is
reached.

### `&T` — counted reference (reference class)

`&T` is a **counted/shared reference** to a value of layout `T`.

- To obtain a `&T`, a `T` value is *boxed* into a ref-counted heap object; the
  handle owns one count.
- Copying a `&T` increments the count; the last handle to drop decrements it,
  and at zero the box is freed.
- `&` is not allowed on arrays or other already-reference types (nothing to
  promote), so its operand is a value/layout type.

### `*T` — raw pointer

`*T` is a **raw pointer** to a value of layout `T`. It is a low-level,
unmanaged pointer into the box's storage; it does not participate in
refcounting. Getting a raw pointer out of a counted reference is analogous to
`shared_ptr<T>::get()`.

### Extraction (`unbox`)

The underlying value of a reference (`&T`) or a raw pointer (`*T`) is obtained
by an extraction operation that copies the value out into a plain local.

## Expression semantics

The memory operators also appear as expressions.

- `b = &a` — **boxes a copy** of `a` into a fresh counted reference. The box is
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
class Point { x: int; y: int }

var a: Point = makePoint()
var b: &Point = &a   // b is a counted reference to a copy of a
var p: *Point = *b   // p is a raw pointer to the same box that b manages
```

## Type aliases

`typealias` names a (possibly composed) type, so memory operators can be given
a readable name:

```text
typealias PList<T> = &List<T>   // named counted reference to a List<T>
```

## Open questions

- Spelling of the extraction operation: `unbox` or `inbox`?
- Does extraction apply to both `&T` and `*T`?
- Can a `&T` be null/absent, or does it always reference a valid box?
- Safety/nullability semantics of `*T` raw pointers (dereferencing a raw
  pointer whose box/storage has been freed).
