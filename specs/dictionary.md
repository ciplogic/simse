# `Dictionary<K, V>`

Status: design baseline — value dictionary for the first implementation.

`Dictionary<K, V>` is a built-in generic **value type** for key/value storage.
Its behavior is equivalent in purpose to `std::unordered_map<K, V>`:

- keys are associated with values;
- keys are unique;
- insertion of an existing key updates or replaces its value according to the
  dictionary operation used;
- lookup, insertion, removal, and membership operations are supported; and
- copying a dictionary copies the dictionary and its contents using value
  semantics.

The exact operation names and lookup-result API are deferred until the
collection API is specified. A dictionary may use heap storage internally, but
the dictionary value owns that storage and does not share it implicitly when
copied. Use `&Dictionary<K, V>` when shared identity is required.

```text
var scores: Dictionary<Str, Int32> = Dictionary<Str, Int32>()
scores.insert("alice", 10)
scores.insert("bob", 20)

var copied = scores       // deep value copy
copied.insert("carol", 30)
// scores does not contain "carol"
```

`Dictionary<K, V>` is reified like every other generic type. For example,
`Dictionary<Str, Int32>` and `Dictionary<Str, Float64>` are distinct concrete
types in the generated C++ output.

## Hashing is unspecified

The language does not currently specify how keys are hashed, how buckets are
organized, how collisions are resolved, or whether hashing is randomized. The
compiler/runtime may choose an implementation appropriate to the target, but
the observable contract must remain dictionary-like: equal keys identify the
same entry, and unequal keys may coexist.

Requirements for key equality, hashability constraints, custom hash functions,
iteration order, and iterator invalidation are intentionally deferred.
