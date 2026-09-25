# Generics

Status: design baseline — reified generic types for the first implementation.

## Reification

All generic types are **reified**. Every distinct generic instantiation is a
distinct concrete type in the generated C++ output. Generic parameters are not
erased and are not represented by one universal runtime object type.

For example, these are separate concrete types:

```text
SmallVector<4, Int32>
SmallVector<4, Float64>
SmallVector<8, Int32>
SmallVector<4, Str>
List<Int32>
List<Str>
Array<Point>
Array<Int64>
```

The compiler emits or specializes a separate C++ type for each instantiation used by
the program, so changing either the inline capacity or the element type of
`SmallVector` creates a new output type. The generated names are compiler-controlled and
need not match source names, but the output types must remain distinct for layout,
overload resolution, copy/move operations, and type checking. `SmallVector<N, T>` is
reified for each distinct value/type argument combination used in the program.

## Generic declarations

Generic data structures and functions declare type parameters explicitly. Type
arguments may themselves be generic instantiations, references, pointers, or
callable types:

```text
data class Pair<A, B>(var first: A, var second: B)
fun identity<T>(value: T): T { return value }
typealias Action<T> = (T) -> Unit
typealias PtrAction<T> = (*T) -> Unit
```

Generic aliases are transparent names for the fully substituted target type; they do not
prevent reification (`Action<Point>` is `(Point) -> Unit`, `PtrAction<List<Int32>>` is
`(*List<Int32>) -> Unit`).

### Closing a nested type-argument list

A type-argument list is closed by `>`, and `>>` is a token of its own (the shift
operator) - so the two lists of `List<List<Int>>` end in a single `>>`, which the
parser takes apart: each closer consumes one `>` of it and the closer of the list
that is nested in takes what is left, however deep the nesting goes. `<<` never
closes anything, so `SmallVector<4, List<List<Int>>>` is the same rule twice.

The tail of a `>>` that is not a closer has no meaning in that position - `>>=` is
the shift-assignment - so a type-argument list written straight into `=` leaves a
space: `var table: List<List<Int>> = ...`, not `...Int>>= ...`. That one spelling is
a diagnostic rather than a silent misparse.

## Type identity

Two generic instantiations are compatible only when their fully substituted
types are compatible. In particular:

- Different instantiations are different types: `List<Int32>` vs `List<Int64>`,
  `SmallVector<4, Int32>` vs `SmallVector<8, Int32>`.
- `Array<T>` remains a reference-counted type, but `Array<Int32>` and
  `Array<Str>` are distinct reified element-specialized types.
- Type aliases do not create additional types; they name an existing
  instantiation.

No implicit conversion exists between different instantiations merely because
their layouts happen to be compatible. Conversion requires an explicit
operation or a separately declared function.

## Runtime and code generation

Reification lets the compiler use the concrete representation of `T` and any value
parameters while generating C++, specializing each used instantiation. Unused
instantiations need not be emitted.

The `typeId` stored in ref-counted allocations identifies the concrete emitted type
within one compilation output; it is unused for dispatch or casting, and reification
adds no virtual dispatch.
