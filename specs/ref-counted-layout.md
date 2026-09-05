# Ref-counted allocation layout

Status: design baseline — common header for all ref-counted objects.

Every ref-counted allocation begins with a common header. The first two fields
are always ordered as follows:

```text
ref-counted allocation:
+----------------------+  offset 0
| reference count      |  runtime-managed count
+----------------------+  offset sizeof(RefCount)
| typeId               |  compiler-generated type number
+----------------------+  offset sizeof(RefCount) + sizeof(TypeId)
| type-specific data   |
+----------------------+
```

`typeId` is a number generated during compilation for the concrete allocated
type. Its value is stable for the produced compilation output but is not a
source-level enum, user-defined identifier, or ABI value. The compiler/runtime
must not assume that IDs are stable across separate compilations.

For now, `typeId` is stored but unused. It does not enable inheritance, virtual
dispatch, dynamic casts, or any other polymorphic operation. Virtual dispatch
and similar runtime type operations are not supported by the current language
subset.

The header applies to every ref-counted allocation, including:

- boxes created for `&T`;
- `Array<T>` allocations; and
- future ref-counted runtime objects.

For `Array<T>`, the complete allocation is therefore:

```text
[reference count][typeId][element count][T elements...]
```

The array's element count follows the common header, and the elements follow
the count. All fields are subject to the language's packing and alignment
rules. The array header and its elements are one allocation.

`*T` and `RawArray<T>` do not add or own a ref-counted header. A raw pointer
that points into a ref-counted allocation may be used to inspect or modify that
allocation, but it does not increment the reference count and does not keep
the allocation alive.
