# Core types and error handling

Status: design baseline — suitable for the first self-hosted implementation.

## Policy: no nulls, no exceptions

The language has **no exception mechanism**, and null is discouraged. Absence
and failure are expressed with explicit wrapper types rather than by returning
null or throwing.

Reference variables are nullable. A counted-reference construction such as
`&point` always produces a non-null live box, but a variable of type `&T` may
later be assigned `null` (or receive it from an unsafe/FFI boundary). A raw
pointer (`*T`) may be null or dangling. Dereferencing either kind of null or
invalid reference is unchecked and has undefined behavior, typically a
segmentation fault. Safe language code should use `Opt<&T>` or `Opt<*T>` when
absence is expected and must test the option before use.

### The `null` literal

Status: required for the first implementation.

`null` is a reserved keyword denoting the absence of a value in a nullable
context. Its meaning is fixed by the expected type:

- in a `*T` (raw pointer) context it lowers to a null pointer (`nullptr`);
- in a `&T` (counted reference) context it lowers to an empty reference (a null
  `shared_ptr`, which owns no box); and
- in an `Opt<T>` context it lowers to `Opt<T>()` (the empty optional).

Comparisons `x == null` and `x != null` test the reference directly for `*T` and
`&T`, and test `hasValue()` for `Opt<T>`. Because the meaning is context-driven, a
bare `null` with no expected type (for example `val x = null`) is not useful and
should be avoided; give the binding an explicit type.

## `Opt<T>`

`Opt<T>` models an optional value, following `std::optional<T>`. It either
holds a `T` or is empty. It is the recommended way to represent "no value"
instead of a null pointer/reference.

```text
var found: Opt<Point> = table.find(id)   // Opt: has a Point, or empty
```

## `Str`

`Str` is the language's **inline byte-string** type: a value type whose
characters are stored inline, so copying a `Str` deep-copies its text.
Concretely, `Str` is `SmallVector<24, Char>` and stores up to 23 characters
inline (see `built-in-types.md` and `containers.md`).

## `Res<T>`

`Res<T>` is the **result** type for fallible operations. There being no
exceptions, functions that can fail report it by returning a `Res`:

- On success it carries a `T`.
- On failure it carries an error message as an inline string (`Str`).

```text
Res<int> parse(const Str& s)   // success: int; failure: error string
```

The error is always an inline string, so `Res` takes a single type parameter
(`T`, the success payload). The success payload is stored **by value**. This
means `Res<T>` has the same ownership and copy semantics as any other value
containing a `T`; callers that need shared identity can explicitly use
`Res<&T>`.

`Opt<T>` also stores its payload by value. It has exactly two states, `none` and
`some(T)`, and copying it copies the contained value. `Opt<&T>` is therefore a
valid way to represent an optional shared reference,
with normal reference counting on copies. `Opt<*T>` similarly represents an
optional raw pointer, but does not make the pointed-to storage safe or extend
its lifetime.

The constructors and projections are explicit:

```text
Opt<int>.none()
Opt<int>.some(42)
Res<int>.ok(42)
Res<int>.err("bad integer")
```

Accessing a missing optional or a failed result is a compile-time/error-path
operation, not a null dereference or exception. Code must test or pattern-match
the state before extracting the payload. `&` and `*` do not unwrap `Opt` or
`Res`; they apply only to an actual value/reference expression.

## Inspection of `Opt` and `Res`

Status: required for the first implementation.

`Res<T>` exposes the state and payload as:

- `isOk(): Bool`;
- `value: T` (the success payload); and
- `error: Str` (the failure message).

`Opt<T>` exposes the state and payload as:

- `hasValue(): Bool`; and
- `value(): T`,

in addition to the `none`/`some` constructors above.

Accessing `value`, `error`, or `value()` on an empty (`Opt`) or failed (`Res`)
value is an error-path operation and must be guarded by a test of `isOk()` or
`hasValue()`.
