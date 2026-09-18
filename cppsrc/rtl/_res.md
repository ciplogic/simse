Generated C++
====
The RTL's generator-backed C++ (impl_specs/generators.md). cppsrc/rtl/Span.kt declares
`@SmGen("res", "spanOf")`, a declaration whose C++ is the `spanOf` resource section
below - `symbol` is what a call goes to, `forward` is the declaration the emitter adds
to that section, and `bodies` is the definition. A prose line here is ignored only when
it holds no colon, so keep the spelling of an entry line in mind when editing.

spanOf
====
symbol: simse_spanOf
forward:
```cpp
// `spanOf(items)`: a span over a list's elements, generated (`cppsrc/rtl/_res.md`).
template <class T>
Span<T> simse_spanOf(List<T>* items);
```
bodies:
```cpp
// `spanOf(items)`: a span over a list's elements. It borrows the list - the list has
// to outlive the span - and does not copy it (`&items` would box a copy instead).
template <class T>
inline Span<T> simse_spanOf(List<T>* items) {
    return Span<T>(items->data(), items->size());
}
```
