# Changing the compiler

## The ring

**One ring, and it is Simse.** A compiler behavior change is a change to `src/**/*.kt`. There
is no C++ mirror to keep in step: the runtime's headers (`src/rtl/*.hpp`), the RTL's resource
text (`src/rtl/_res.md`) and the published bootstrap are the only C++, and all of them are
inputs to `cl.exe`, not a second implementation.

After a change run the loop (`ai/building.md`):

```sh
./build.bat --release          # then
bun tools/stress.js            # then
bun tools/bootstrap.js
```

**Never weaken the fixed point** and **never hand-edit `src/simse_bootstrap.cpp`** — it is
generated, and it is the one file that has to keep building the compiler. When the change is
visible in the emitted C++, refresh the published file
(`bun build.js --release --out src/simse_bootstrap.cpp`) and commit it with the source.

**Commit every validated change, one commit each** (imperative subject, no meta-request
needed). Work that is red, or whose validation has not run, is not committed: report what
failed and leave the tree to the user.

## The two-build rule

A surface change the *running* compiler cannot handle needs two builds: an attribute in the
prelude (`src/rtl/*.kt`), or a change to what the parser records, is visible to the compiler
that is transpiling the tree — so build once with the old spelling, then switch the source and
build again. The same shape applies to `@` itself: it landed in the scanner/parser while the
compiler's own sources stayed `@`-free, and only a compiler built *from* those changes could
parse an `@` in the prelude. The published bootstrap never needs a hand-patch: refreshing it
carries the new scanner and parser. If the running compiler cannot compile the tree at all,
stage a compiler from the published file first (`./build.bat --cpp src/simse_bootstrap.cpp
--exe simse.exe`), then build normally.

A `_res.md` under `src` is part of the compiler, not of a program: the `res` generator reads it
*first* while the tree is being compiled, and at run time the compiler reads it from disk for
every other program. Edit one and the next build is the one that sees it — and the amalgamation
changes, so refresh the bootstrap too.

## Invariants

- **The bootstrap fixed point**: the compiler built from the published
  `src/simse_bootstrap.cpp` transpiles `src` back into that same file, byte for byte. This is
  the strongest invariant in the repo.
- **Determinism**: transpiling the same inputs twice is byte-identical.
- **The stress corpus stays green**; a case is a folder under `stress/` with `src/`,
  `expected.stdout`, and optional `args`/`stdin`/`expected.exit`/`expected.cpp`/
  `expected.transpile-error` (`stress/README.md`). A case with no expectations is unfinished,
  not passing. `expected.cpp` is an emission golden: `--update` never rewrites it — copy
  `stress/.work/<case>/out.cpp` after the case passes.
- **One declaration, one spelling**: `@SmGen("cpp", "sym")` names hand-written C++,
  `@SmGen("res", section, sym)` a resource section; the old `native("sym")` keyword is gone.

## Conventions that are not obvious from the code

- **Prefer the container `for`** to an index walk: `for (*x in xs)` binds a pointer (no copy,
  writes through), `for (x in xs)` a copy, `for ((*x, i) in xs)` adds a counter. Keep `while`
  when the index is used for anything besides indexing, or the container is mutated.
- **Borrow AST-carrying structs; don't copy them.** A `val x: T = list[i]` where `T` holds an
  `XmlNode` deep-copies the subtree; in read-only loops use a pointer and let read-only
  functions take `*T`.
- **Read a node's own attributes once**, into the collected struct everyone scans: the AST is
  a uniform attribute list, so `xmlAttr(node, Name)` is a scan, and per-read scans are the
  compiler's profile.
- **Don't spell `copy(...)`**; the compiler converts where the destination says so. A `val x:
  Str = h` where `h: *Str` reads through by itself, and call arguments convert against their
  parameters. Write `*` only for a binding that outlives its expression and for
  `for (*x in xs)`.
- **Build a fixed shape with `fmtStr`, a run of appends with `reserve`** (`out.reserve(len)`
  then `appendStrPtr`, never `out = out + part` in a loop).
- Every `.kt` file starts with a mandatory `package`; update `import` lines when adding files.
- **User-visible changes update the docs**: `README.md`, `docs/state-of-the-field.md`,
  `docs/language-tour.md`, and `ai/language.md`/`ai/status.md` carry claims a reader checks.

## Gotchas

- **The emitter emits from the IL and from nothing else** (`emitIlBodyText`/`ilEmitOps` in
  `src/codegen/`). A body the IL cannot spell is a hard error; an extractor that reports
  `unsupported: <reason>` is a *user* error, not `internal:` — keep the prefix rule intact.
- **`Sections` is passed, never read through**: `sections: *Sections` is a copy in Simse, so
  `sourceGenEmit(*this.sections, ...)` would fill a copy and render an empty file.
- **A lambda is a data class plus a free `<symbol>_invoke(self, params)`** taking the instance
  **by value**, emitted into the `closures` section (all classes before all methods, no `auto`
  block anywhere). A call through a known closure reaches `_invoke`; a `Func<...>` value calls
  as it stands, and the class's one member converts it (`rtl/functional.hpp`'s
  `simse_closureFunc`). `self` stays `self` in the invoke (only *handle* receivers are spelled
  `this`).
- **MSVC will not deduce a template parameter through a dependent `std::function` parameter**
  when the argument is a closure or a named function (a `Func<...>` argument is fine). A
  generic function whose declared parameter mentions its own type parameters gets a forwarding
  overload (`emitDeducedCallableOverload`), the const-params pass refuses to fold such a
  parameter, and the compiler spells explicit instantiations it can infer (`peek<Int>(...)`).
  A lambda that fits both a plain `T` and a callable declaration is an `ambiguous call` report
  on purpose. `ai/language.md` has the user-facing rules.
- **A resource's comments do not reach the program**: `resGenAddSection` filters the text as
  it places it (`ResComments.kt`); `//` inside a string, character or raw-string literal is
  data and is kept. The emitter's own comments (banner, `// <file>` markers) stay.
- **Golden `expected.cpp` files are byte-compared** and `--update` never rewrites them; copy
  from `stress/.work/<case>/out.cpp` after reading the output.
- **`Dictionary.getPtr(key)`** is the value's place (or `null`); `get` copies and `has` is the
  pointer test — use `getPtr` for lookups whose values are big (an `AstXmlNode`, a `List`).
- The auto-borrow rewrite (`BorrowParams.kt`) binds a receiver to a local before a `for` over
  it, which is the shape to keep in compiler code that walks a container it is also passing
  on; `--no-borrow` is the escape hatch if a borrow is ever wrong.
