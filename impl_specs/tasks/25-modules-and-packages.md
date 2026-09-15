# T25 - Modules and packages

Status: Done
Phase: D - Completion
Depends on: -
Blocks: none

## Goal

Implement the modules-and-packages model in `specs/modules.md`: a **module is a
directory** scanned from one or more module roots, and a **package is a
namespace** declared per file with a mandatory `package a.b.c` declaration.
`import a.b.c` brings a package into unqualified scope and affects name
resolution only, never which files are compiled.

## Motivation

Imports grew out of a directory-based draft (`import a.b.c` meant the directory
`./a/b/c`, with a silent fallback to it when no file declared the package). That
couples resolution to the working directory and the scan root and gives `package`
no fixed meaning. The design in `specs/modules.md` separates the two: modules are
the physical unit (directories on module roots) and packages are the logical unit
(namespaces declared per file, independent of layout). Several modules may
contribute to one package, and resolution by package name is layout-independent
and identical wherever the compiler is invoked.

## Scope

In:

- A **module is a directory**; the compiler is given one or more **module roots**
  and scans them, including every `.kt` file found.
- A **package is a namespace**, declared once per file as the first declaration:
  `package a.b.c`, before imports and other declarations. The declaration is
  mandatory.
- `import a.b.c` brings package `a.b.c` into unqualified scope; it does not add
  files to the compilation, since the module roots already include them.
- Resolution by package name against the set of scanned files, independent of the
  filesystem layout of a package and of the current working directory. Importing
  a package that no scanned file declares is an error; the directory fallback is
  removed.
- The runtime prelude's packages (`rtl`) are implicitly in scope in every file.
- A duplicate top-level name across two files that declare the same package is a
  duplicate-definition error.
- External modules: a separately supplied library is brought in by placing its
  directory on a module root.

Out:

- **Fully-qualified package access** (`a.b.c.Name`): there is no qualified-name
  access form. Declarations are referenced by their simple names, and `import`
  only controls which packages' declarations are visible by those simple names.
- **Multiple packages per file** (a file may later declare `package` more than
  once, each declaration setting the current package for what follows, with
  positional `import`s). Specified in `specs/modules.md` as the intended shape but
  not implemented initially.
- **External-module packaging**: dependency manifests/files, versions, transitive
  dependency resolution, and downloading are unspecified.
- Any visibility, `public`/`private`, or access-control semantics; a package
  carries no metadata beyond its name.
- Any change to hoisting or the visibility of top-level declarations (see
  `declarations.md`).

## Deliverables

- Specs: `specs/modules.md` (normative), with pointers from `specs/functions.md`,
  `specs/declarations.md`, and `specs/language-decisions.md`.
- Compiler: module-root scanning; mandatory `package` as the first declaration;
  package-index resolution by name with the directory fallback removed; implicit
  `rtl` prelude scope; duplicate-definition and unresolved-import diagnostics.
- Mirrors: package declarations on every `cppsrc/**/*.kt` file aligned with the
  mandatory form.
- Tests: fixtures/goldens covering mandatory packages, unresolved imports,
  and duplicate definitions across a package.

## Acceptance criteria

- [x] Every `.kt` file declares exactly one `package a.b.c` as its first
      declaration; a file with no package declaration is an error.
- [x] `import a.b.c` resolves by package name against the scanned files,
      independent of the working directory and of directory layout, with no
      directory fallback.
- [x] Importing a package that no scanned file declares is an error.
- [x] `rtl` prelude packages are in scope in every file without an explicit
      import.
- [x] Two files declaring the same package and defining the same top-level name
      report a duplicate-definition error.
- [x] All five differentials stay byte-identical, the stage-1 fixed point holds,
      and `simse_tests` passes.

## Implementation notes

- **Loader (style A).** A compilation is the project module root (the `simse`
  positional directory, or `--root`) plus each repeatable `--module-root`,
  scanned recursively; every `.kt` found is included, and `simse_transpile`
  also includes its explicit inputs. `import` never adds files. `parser`
  `parseFileWithImports`/`collectImportSet` (and the Simse `ImportLoader`) were
  removed; module scanning is `common::filesInDir` driven by `compiler::transpile`
  and mirrored in `cppsrc/compiler/Driver.kt`.
- **Sema is compilation-wide.** `sema::analyze(List<Input>)` (C++) and
  `analyze(List<SemaInput>)` (Simse) collect declarations grouped by declared
  package, report duplicate top-level names within a package (across files),
  validate imports against the scanned packages, and build each file's
  unqualified scope from its own package, its imports, and the implicit `rtl`
  prelude.
- **Module-root order matters for emission.** Non-prelude files are emitted in
  discovery order, and the C++ emitter forward-declares functions but not structs,
  so module roots are passed dependency-first in `CMakeLists.txt` (`common`
  before `lex` before `parser` before `sema` before `codegen` before `compiler`).

## Steps

1. Make the `package` declaration mandatory and require it first (before imports
   and other declarations) in both parsers.
2. Scan the module roots into a package index; resolve imports by package name
   only, removing the directory fallback.
3. Put the prelude (`rtl`) packages in scope implicitly.
4. Add duplicate-definition checking for top-level names shared across a package.
5. Align the mirrors, fixtures, and goldens with mandatory package declarations.
6. Validate the CLI behavior, the clean build, the differentials, the fixed
   point, and `simse_tests`.

## Risks / notes

- The bootstrap currently relies on the silent directory fallback for unmatched
  import names; removing it requires every file, including fixtures, to declare a
  package (or the fallback to be retained only transiently).
- A mandatory package changes the AST (`Module.package`) and therefore the
  goldens for files that previously had no package.
- The stage-1 fixed point is sensitive to the file set and order the import
  resolver produces.
- The deferred items (multiple packages per file, external-module manifests) must
  not be depended on by the parser or the mirrors.

## References

- `specs/modules.md`
- `specs/declarations.md` (hoisting, package declarations),
  `specs/functions.md` (imports pointer), `specs/language-decisions.md`.
- `impl_specs/capability-matrix.md`, `impl_specs/plan-to-selfhost.md`.
