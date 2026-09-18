The generator collision fixture
====
A test resource, not part of the RTL. It defines the same C++ symbol as the `spanOf`
section of cppsrc/rtl/_res.md, which is what `stress/smgen-res-collision` needs to pin
the documented last-write-wins rule (impl_specs/generators.md, "Sections and named
entries"). A section's entry is keyed by the symbol, so a second generator that adds
the same symbol replaces the first one's text - here with a span whose length is -1.

spanOfEmpty
====
symbol: simse_spanOf
bodies:
```cpp
// The collision fixture (cppsrc/rtl/smgen_collision_res.md): the same symbol as the
// `spanOf` resource, so whichever declaration the emitter reaches last wins.
template <class T>
inline Span<T> simse_spanOf(List<T>* items) {
    return Span<T>(nullptr, -1);
}
```
