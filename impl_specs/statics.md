# Statics: implementation plan

Status: slice 1 (file-level `var`/`val`) implemented in both rings and green; the
`object` slices (2-5) are specified here, not implemented. The language rules are
`specs/statics.md`. This file is the contract for the work: the AST schema, the
emitted shapes, and the slices each ring implements in lockstep.

## AST schema

Two new declaration roles alongside `DataClass`/`Enum`/`TypeAlias`/`Function`:

```xml
<!-- a file-level static variable: var requests: Int = 0 -->
<Var kind='Var' line='3' column='5' name='requests' isVar='true'>
  <Type kind='Type.Named' name='Int'/>        <!-- same Type schema as fields -->
  <Init> ...expression... </Init>             <!-- omitted when there is no initializer -->
</Var>

<!-- object Defaults { var retries: Int = 3 ... } -->
<Object kind='Object' line='7' column='1' name='Defaults'>
  <TypeParam name='T'/>                       <!-- zero or more, as data classes -->
  <Var .../>                                  <!-- the static variables -->
  <Function .../>                             <!-- methods: later slice -->
</Object>
```

`Var` reuses the `Stmt.VarDecl` shape (`type`, `isVar`, `Init`) so the parser and
the emitters have one variable shape to handle. `Object` reuses `TypeParam` from
data classes and `Var` for its fields.

## Emitted shapes

The language's rules (`specs/statics.md`) map onto plain C++ globals plus one
generated pass. Storage is value-initialized, so it starts empty; the pass
assigns the initializers; `main` calls the pass first.

```cpp
// package counters:  var requests: Int = 0
Int ns1_requests{};

// object Defaults { var retries: Int = 3   var label: Str = "simse" }
SIMSE_PACK_PUSH
struct ns1_Defaults { Int retries; Str label; };
SIMSE_PACK_POP
ns1_Defaults ns1_Defaults_instance{};

// package app:  fun main(): Int { ... }
void simse_initStatics() {                 // emitted before main, called first
    ns1_requests = 0;
    ns1_Defaults_instance.retries = 3;
    ns1_Defaults_instance.label = "simse";
}

int main() {
    simse_initStatics();
    // ... the body
}
```

A generic object cannot be enumerated by looking at its declaration, so the
emitter **collects its instantiations at reification time**
(`impl_specs/reification.md`, below in this file):

- every mention of an object with **concrete** type arguments
  (`Cache<Int>`, in a type position, a member access or a call) is recorded as
  (object, rendered arguments), deduplicated by that rendered text - the same text
  the emitted C++ uses, so the pass names the instance exactly as the program
  does;
- a mention inside a **generic body** is recorded against that body as a template
  mention (object name plus the argument expression as written, type parameters
  and all). When the body is itself reified with concrete arguments - the only
  way its code can run - the recorded mentions are substituted and joined to the
  concrete set.

The pass then emits, for each collected instantiation, that instantiation's
initializers against its instance. The mention that never gets a concrete
reification stays on the accessor's function-local static (the shape below),
which initializes it on first use.

Emitted shape: every object field is reached through an accessor that owns the
run-once guarantee, so the concrete and the residual case share one storage shape
and an initializer runs **once**, whichever path reaches it first:

```cpp
// object Cache<T> { var count: Int = 0   var label: Str = "c" }
template <class T>
SIMSE_PACK_PUSH
struct ns1_Cache { Int count; Str label; };
SIMSE_PACK_POP

// Storage is a zero-initialized variable template: no C++ dynamic initialization
// runs before the pass, and an untracked instantiation starts empty (never
// indeterminate).
template <class T> inline ns1_Cache<T> ns1_Cache_instance;
template <class T> inline bool ns1_Cache_initialized = false;

template <class T> void ns1_Cache_init();

template <class T>
ns1_Cache<T>& ns1_Cache_access() {
    if (!ns1_Cache_initialized<T>) {
        ns1_Cache_initialized<T> = true;      // first, so an initializer that reads
        ns1_Cache_init<T>();                  // the object sees the same state
    }
    return ns1_Cache_instance<T>;
}

template <class T>
void ns1_Cache_init() {
    ns1_Cache_access<T>().count = 0;
    ns1_Cache_access<T>().label = "c";
}

// collected: run each reified instantiation's initializers once, before main
void simse_initStatics() {
    ns1_Cache_initialized<Int> = true;
    ns1_Cache_init<Int>();
    ns1_Cache_initialized<Str> = true;
    ns1_Cache_init<Str>();
}
```

A field initializer is therefore emitted exactly once and runs exactly once: the
pass marks the instantiation initialized before running its initializers, and
the accessor does the same on the first use of an instantiation the collection
did not see (`tools/statics_probe.cpp` counts the runs to pin that). Default
member initializers are deliberately **not** used - with a pass that assigns the
same expressions, a constructor-applied initializer would run twice.

Accesses: `requests` -> `ns1_requests`; `Defaults.retries` ->
`ns1_Defaults_instance.retries`; `Cache<Int>.count` ->
`ns1_Cache_instance<Int>().count`. Prefixing follows the package rules
(`impl_specs/rtl-abi.md`).

## Slices

Each slice lands in BOTH rings (`cppsrc/**/*.kt` and the C++ mirror), keeps the
five differentials byte-identical and the bootstrap fixed point intact, and adds
its own `stress/<name>` case.

1. **File-level `var`/`val`.** Parser: a top-level `var`/`val` declaration.
   Sema: statics in the module symbol table, and expression-name resolution
   (locals and parameters shadow statics). Codegen: storage, the pass, and the
   call at the top of `main`, plus expression names.

   *Landed.* The declaration role is `Var`, the parser requires the `:` type (a
   missing annotation reports `expected ':'`), the module scope is pushed per
   file by `buildVisible` and popped by `run`, and codegen collects the
   declarations in source order into `statics`/`staticsByName` (prelude statics
   are skipped) and emits them before the function prototypes, so
   `simse_initStatics` and `main` can both call them. `stress/statics` pins it;
   `tools/array_layout_probe.cpp` pins the empty-array sharing that slice 4
   depends on.

   *First use in the compiler:* the scanner's three tables (`reservedWordTable`,
   `multiCharOperatorTable`, `tokenRuleTable` in `cppsrc/lex/Scanner.kt`) are
   file-level statics: the pass builds each one once and the hot comparisons read
   them through a raw pointer (`*List<T>`), where an accessor returning a
   `List<Str>` rebuilt the table per call - `matchOperator` runs for every token,
   so that was an allocation per token. The driver contract this adds: the emitted
   pass is named `simse_initStatics` and `main` calls it when the program has one
   (`emitFunction` emits the call), so a host that links a generated component
   **without** a `main` of its own - `tests/*_simse_main.cpp`, the differential
   drivers - must call `simse_initStatics()` before using the component. Those
   four drivers do.
2. **`object` (non-generic).** Parser: `object` with `Var` members. Sema: the
   object name in the declaration namespace; `Name.field` in expressions and as
   an assignment target. Codegen: the struct, the instance, pass entries, member
   access.
3. **Generic `object`.** Sema: type arguments on object access. Codegen: the
   template struct, the per-instantiation accessor, the reification-time
   **instantiation collection** (concrete mentions plus substitutions through
   reified generic bodies), the pass entries for the collected instantiations, and
   instantiation-aware access (`Cache<Int>.count`). The collection is what makes
   "initialized before `main`" hold for every instantiation a program can reach.
4. **The payoff: `arrayEmpty` moves to Simse.**

   ```text
   object EmptyArrayData<T> { var empty: Array<T> = Array<T>(0) }

   fun arrayEmpty<T>(): Array<T> {
       return EmptyArrayData<T>.empty
   }
   ```

   `Array<T>(0)` is the spec's zero-length construction
   (`specs/built-in-types.md`) and is already allocation-free in the shim - `count
   <= 0` returns the shared zero-length block - so the object's initializer needs
   nothing below it: there is no circularity between the object and
   `arrayEmpty`. What leaves C++ is the `native("simse_arrayEmpty")` declaration
   and its implementation (the `listops` section of `cppsrc/rtl/_res.md`); the shim's
   internal zero-length block
   stays, because it is what a C++ `Array<T>` default-constructs to (the type's
   own default, not a language-level static). The first piece of the *language*
   surface written in Simse.
5. **Methods inside `object`** (not in the first three slices): a function in an
   `object` body, lowered to a free function that reads the instance's fields by
   simple name.

## Verification per slice

- `_msvc_build.bat` in `cmake-build-debug`: all five differentials byte-identical
  and `stage1_check` (the two-ring fixed point) still passing.
- `simse_tests.exe`: goldens regenerate only where the emission changed on
  purpose (`--update`, then review, then check mode).
- `bun tools/stress.js` on both rings, including the slice's new case.
- `cppsrc/simse_bootstrap.cpp` regenerated when the compiler's own emission changes
  (the tracked amalgamation is the stage-1 output; `bun build.js --release --out
  cppsrc/simse_bootstrap.cpp` writes it and compiles it).

`tools/statics_probe.cpp` pins the runtime shape of the design - the empty
storage, the pass, and the per-instantiation generic accessor - before the
language features exist.
