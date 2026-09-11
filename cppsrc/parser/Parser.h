#pragma once

#include "../ast/Ast.h"
#include "../lex/Scanner.h"

// The recursive-descent + Pratt parser for the minimal Simse subset. It works on
// the token stream produced by the scanner (Space and Comment tokens are ignored;
// the stream must not contain the Eof token, though a synthetic one is appended
// internally). EndOfLine and ';' both terminate statements.

namespace parser {
    // Parses a whole module. On failure the returned error is formatted as
    // "<fileName>:<line>:<col>: <message>".
    Res<ast::Module> parseModule(List<lex::Token>& tokens, const Str& fileName);

    // Reads, scans, and parses `fileName`. Convenience for callers that do not
    // already hold a token stream (for example loading a prelude).
    Res<ast::Module> parseFile(const Str& fileName);

    // Reads, scans, and parses `fileName`, then recursively merges the modules it
    // imports into the result. `import a.b.c` imports every file that declares
    // `package a.b.c` (resolved through a package index over `rootDir`, with a
    // silent directory fallback under `<rootDir>/a/b/c` for unmatched names); see
    // specs/functions.md. Import cycles are reported rather than followed.
    // Declarations are hoisted, so merge order is irrelevant; files are visited in
    // sorted order for determinism.
    Res<ast::Module> parseFileWithImports(const Str& fileName, const Str& rootDir);

    // Parses the given files and returns the ordered, de-duplicated list of every
    // file that participates: each input and its transitive imports, in the order
    // `parseFileWithImports` would merge them (imports before their importer). Each
    // `import a.b.c` selects the files declaring `package a.b.c` under `rootDir`.
    // Import cycles and unresolvable imports are reported the same way. Used by
    // the directory compiler so every file is compiled exactly once.
    Res<List<Str>> collectImportSet(const List<Str>& files, const Str& rootDir);
}
