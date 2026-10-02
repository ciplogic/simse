The comments a `res` section carries
====

What a program's own generated C++ looks like: the declarations and bodies below name the
symbol the `@SmGen` argument does, and the comments between them are written for a reader of
this file, so the emitted program must not carry them.

!rescom
====
symbol: fixtures_resCom
forward:
```cpp
// The declaration's own comment, on a line of its own: gone from the program.
/* A block comment on one line: gone as well. */
/* A block comment
   spanning lines: the whole thing goes, and nothing of it reaches the output. */
Int fixtures_resCom(Int value); // and a trailing comment: gone too.
```
bodies:
```cpp
inline Int fixtures_resCom(Int value) {
    // Every `//` below is inside a literal, so it is data and survives.
    const char* url = "http://example.com/a//b";
    Char slash = '/';
    const char* raw = R"tag(// data
still data)tag";
    Int score = 0;
    if (slash == '/') {
        score = score + 1;
    }
    if (url[5] == '/' && url[6] == '/') {
        score = score + 1;
    }
    if (raw[0] == '/' && raw[1] == '/') {
        score = score + 1;
    }
    return value + score;
}
```
