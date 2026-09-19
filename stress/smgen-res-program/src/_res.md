The program's own generated C++
====
The text a `@SmGen("res", ...)` declaration reaches, carried by the *program* - the
emitter reads the tree's own `_res.md` files before the compiler's, which is what makes a
program able to supply generated C++ for itself (impl_specs/generators.md, "The `res`
generator").

!triple
====
symbol: fixtures_triple
forward:
```cpp
// `triple(value)` (src/main.kt), the program's own generated function: the declaration
// reaches this text, and the emitter never emits a prototype of its own for it.
Int fixtures_triple(Int value);
```
bodies:
```cpp
inline Int fixtures_triple(Int value) {
    return value * 3;
}
```
