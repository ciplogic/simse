#pragma once

#include "containers.hpp"
#include "types.hpp"

// Native filesystem/IO operations for the self-hosted compiler driver (T23).
// The prototypes live in the RTL so generated code that includes simse.hpp sees
// them; the definitions are in cppsrc/native/Native.cpp (linked as simse_native).
// The Simse surface is the prelude file cppsrc/rtl/fs.simse.
//
// `simse_listFiles` matches common::filesInDir exactly: recursive, `ext`-filtered
// (the extension includes the dot), sorted, and empty when `dir` is not a
// directory. `simse_listFilesDirect` is the non-recursive form used to resolve
// `import a.b.c` directories.

List<Str> simse_listFiles(const Str& dir, const Str& ext);
List<Str> simse_listFilesDirect(const Str& dir, const Str& ext);
Bool simse_writeFile(const Str& path, const Str& content);
Str simse_pathCanonical(const Str& path);
Bool simse_pathIsDirectory(const Str& path);
Bool simse_pathExists(const Str& path);
void simse_eprintln(const Str& text);
