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
List<Int32>
List<Str>
Array<Point>
Array<Int64>
```

The compiler emits or specializes a separate C++ type for each instantiation
that is used by the program. In particular, changing either the inline capacity
or the element type of `SmallVector` creates a new output type:

```text
SmallVector<4, Int32>   // one generated C++ type
SmallVector<8, Int32>   // a different generated C++ type
SmallVector<4, Str>     // another generated C++ type
```

The generated names are compiler-controlled and need not match source names,
but the output types must remain distinct for layout, overload resolution,
copy/move operations, and type checking. A generic type with a value parameter,
such as `SmallVector<N, T>`, is reified for each distinct value/type argument
combination used in the program.

## Generic declarations

Generic data structures and functions declare type parameters explicitly. Type
arguments may themselves be generic instantiations, references, pointers, or
callable types:

```text
data class Pair<A, B>(var first: A, var second: B)

fun identity<T>(value: T): T {
    return value
}

typealias Action<T> = (T) -> Unit
typealias PtrAction<T> = (*T) -> Unit
```

Generic aliases are transparent names for the fully substituted target type;
they do not prevent reification. For example, `Action<Point>` is the callable
type `(Point) -> Unit`, while `PtrAction<List<Int32>>` is the callable type
`(*List<Int32>) -> Unit`.

## Type identity

Two generic instantiations are compatible only when their fully substituted
types are compatible. In particular:

- `List<Int32>` and `List<Int64>` are different types.
- `SmallVector<4, Int32>` and `SmallVector<8, Int32>` are different types.
- `Array<T>` remains a reference-counted type, but `Array<Int32>` and
  `Array<Str>` are distinct reified element-specialized types.
- Type aliases do not create additional types; they name an existing
  instantiation.

No implicit conversion exists between different instantiations merely because
their layouts happen to be compatible. Conversion requires an explicit
operation or a separately declared function.

## Runtime and code generation

Reification permits the compiler to use the concrete representation of `T` and
any value parameters while generating C++. It also means that generic code may
be specialized for each used instantiation. Unused instantiations do not need
to be emitted.

The `typeId` stored in ref-counted allocations identifies the concrete emitted
type for the allocation within one compilation output. It is currently unused
for dispatch or casting; reification does not add virtual dispatch.
