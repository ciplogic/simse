# Core types and error handling

Status: draft — being defined interactively.

## Policy: no nulls, no exceptions

The language has **no exception mechanism**, and null is discouraged. Absence
and failure are expressed with explicit wrapper types rather than by returning
null or throwing.

Raw pointers (`*T`) and counted references (`&T`) are nullable at the type
level, but this is a last resort.

## `Opt<T>`

`Opt<T>` models an optional value, following `std::optional<T>`. It either
holds a `T` or is empty. It is the recommended way to represent "no value"
instead of a null pointer/reference.

```text
var found: Opt<Point> = table.find(id)   // Opt: has a Point, or empty
```

## `Str`

`Str` is the language's **inline string** type: a value type whose characters
are stored inline, so copying a `Str` deep-copies its text. Concretely, `Str`
is `SmallVector<uint8, 24>` and stores up to 23 characters inline (see
`containers.md`).

## `Res<T>`

`Res<T>` is the **result** type for fallible operations. There being no
exceptions, functions that can fail report it by returning a `Res`:

- On success it carries a `T`.
- On failure it carries an error message as an inline string (`Str`).

```text
Res<int> parse(const Str& s)   // success: int; failure: error string
```

The error is always an inline string, so `Res` takes a single type parameter
(`T`, the success payload).

### Open questions

- On success, is the carried payload `T` by value (like `List<T>`), or a
  counted reference `&T`?
- Does `Opt<T>` / `Res<T>` interact with `&`, `*`, `unbox` in any special way
  (e.g. is a `&T` field inside `Opt` ref-counted)?
