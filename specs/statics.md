# Static storage: file-level variables and `object`

Status: file-level `var`/`val` implemented; `object` specified, not implemented. The
implementation is sliced in `impl_specs/statics.md`.

A program can declare storage that outlives every call: a **static variable**.
Static storage is written in two places, and the two spellings mean the same
thing - a `var` is a `var` wherever it is declared:

- a **file-level variable**: a `var`/`val` declared directly in a file, outside
  any class, function or object;
- a **field of an `object`**: an `object` is a named, single-instance container
  of static variables.

```text
package counters

var requests: Int = 0                       // a file-level static
val origin: Str = "boot"                    // the same, immutable

object Defaults {
    var retries: Int = 3
    var label: Str = "simse"
}
```

## `object`

An `object` declares exactly one instance. It is a declaration, not a type: it
cannot be constructed, copied or passed around, and it cannot appear in a type
position. Its name lives in the same namespace as the other top-level
declarations, so in one package an `object`, a function, a data class and an enum
cannot share a name.

- An `object` may be **generic**: `object Cache<T> { ... }`. A generic object has
  one instance *per instantiation* - `Cache<Int>` and `Cache<Str>` are separate
  storage.
- Its members are static variables (`var`/`val`) and functions. A function
  declared inside an `object` reads and writes its variables by simple name, and
  is called through the object's name (below).
- An `object` has no constructor and no initializer of its own: the initializers
  of its variables are all it has.

## Access

A static variable is referenced by its simple name (file-level) or through its
object's name:

```text
requests = requests + 1        // file-level, by simple name
Defaults.retries = 5           // object member
println(Cache<Int>.count)      // generic object member
```

Object access is the one qualified form the language has; it is *not* package
qualification. `import` still controls which packages' names are visible, and
there is no `a.b.c.Name` form (`specs/modules.md`). `object` is a reserved
keyword.

## Initialization

Every static variable has the same shape:

- its storage starts **empty**: the zero value for a scalar, an empty
  `Str`/`List`/`Array`/`Dictionary`, a zeroed aggregate;
- its **initializer** - the `= expr` it was declared with, if any - runs in a
  generated **initialization pass** that executes **before the body of `main`**;
- the relative order of two static initializers is **not specified**: a program
  must not depend on one static being initialized before another.

A read before that variable's initializer has run yields the empty value, never
undefined data; what is unspecified is which initializers ran.

Static variables are hoisted like every other module-level declaration
(`specs/declarations.md`), so a function may reference a static declared later in
the file. Hoisting covers the *name*; the initialization order above still
applies.

Generic objects are initialized the same way, one instance per instantiation:
for every instantiation the program **reifies** - a concrete mention such as
`Cache<Int>`, and every concrete instantiation implied by reifying a generic body
that mentions `Cache<T>` - the pass initializes that instantiation's variables
before the body of `main`. Instantiations the compiler cannot enumerate are the
residual case: a mention that stays behind a type parameter and is never reified
concretely from the program's own code is initialized on **first use** instead.
Either way the initializer of a given instantiation runs exactly once, in whichever
path reaches it first; a read never runs it again.

Initializers are ordinary expressions evaluated in the pass: there is no
compile-time evaluation and no `const` in this feature. Constant folding is
separate, later work.
