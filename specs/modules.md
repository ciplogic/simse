---
# Modules and packages

Status: design baseline — modules, packages, and imports for the first implementation.

## Modules (physical)

A **module is a directory** containing Simse source files (`.simse`); any
subdirectory is a submodule. Modules are the physical unit of source
organization: the compiler is given one or more **module roots** and scans them,
including every `.simse` file it finds. An external module — a separately
supplied library, comparable to a .NET class library — is brought in by placing
its directory on a module root.

## Packages (logical)

A **package is a namespace**, declared per file and independent of the directory
layout.

- Every file declares exactly one package, as the first declaration in the file:
  `package a.b.c`, before its imports and other declarations. A package
  declaration is mandatory.
- A package name is an opaque dotted identifier, not a path: whitespace around
  the dots is insignificant (`a.b.c` and `a . b . c` are the same name), and the
  name is matched by equality when resolving `import`.
- Because a package is a namespace and not a path, several modules may contribute
  declarations to the same package (for example a module adding definitions to
  `rtl`).
- If two files declare the same package and define the same top-level name, that
  is a duplicate-definition error.
- Packages change no visibility rules: every top-level declaration is public and
  hoisted across the whole compilation (see `declarations.md`).

### Deferred: multiple packages per file

A file may later declare `package` more than once. Each declaration sets the
current package for the declarations that follow, as if the file were split into
several files at those points; `import` declarations are positional in the same
way. This is specified here as the intended shape but is not implemented
initially: the first implementation supports exactly one package per file.

## Imports

`import a.b.c` brings the package `a.b.c` into unqualified scope, comparable to
`using` in .NET. It does not add files to the compilation.

- The compiler already includes every `.simse` file found under the scanned
  module roots; `import` only affects how names are written.
- There is no qualified-name access form. `import` only controls which packages'
  declarations are visible by their simple names: a declaration is referenced by
  its simple name, never as `a.b.c.Name`.
- Importing a package that no scanned file declares is an error.
- The runtime prelude's packages (`rtl`) are implicitly in scope in every file:
  the language runtime surface never needs an explicit import.

## Resolution

Imports are resolved by package name against the set of files the compiler has
scanned from the module roots. Resolution never consults the filesystem layout
of a package or the current working directory, so an import resolves identically
wherever the compiler is invoked.

### Implementation status

Everything in this spec is implemented: the first implementation resolves
`import`ed packages into unqualified scope, reports an import of a package that
no scanned file declares, and treats `rtl` as implicitly in scope.
Fully-qualified `a.b.c.Name` access is out of scope, not a pending item: there is
no qualified-name form, and declarations are referenced by their simple names.

## External modules (deferred)

How separately shipped modules are declared and obtained — dependency
manifests/files, versions, transitive dependency resolution, downloading — is not
specified yet. The shape fixed here is: a module is a directory; the compiler
scans module roots; and an external module is made available by adding its
directory to a module root.

## What a package is not

- It is not a compilation unit or an access-control boundary in this baseline.
- It carries no metadata beyond its name.
