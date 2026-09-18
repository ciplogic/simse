#pragma once

#include "../common/common.h"

// The resource files (`_res.md`, specs/resources.md): text a program carries that is
// not code, read by the compiler and embedded in the program's string table. The mirror
// of cppsrc/resources/Resources.kt.
//
// A resource file is Markdown-shaped: a *section title* is a line underlined by a line
// of `=` only, and a line that reads as `key: value` is an entry. A section is not
// nesting - it is a *prefix* on the key (`Profiling` + `Name` gives `Profiling:Name`) -
// so a whole file reduces to a flat list of (key, value) pairs.
//
// The parser is deliberately small: keys are trimmed, no escape is interpreted, and the
// one place that escapes anything is the C++ literal the emitter pools a value as
// (`quoteLiteral`).
namespace resources {
    // One resource: the key the program looks it up by, and the text it holds. Both are
    // the file's own bytes - the pool and the C++ escapes are the emitter's business.
    struct ResourceEntry {
        Str key;
        Str value;
    };

    // True when `line` is a section underline: non-blank once trimmed, nothing but `=`.
    bool isUnderline(const Str& line);

    // True when `line` opens a fenced block (an opening fence may name a language) and
    // when it closes one (the bare fence).
    bool isFenceStart(const Str& line);
    bool isFenceEnd(const Str& line);

    // The key a section prefixes: `Title` + `Key` is `Title:Key`.
    Str qualifiedKey(const Str& section, const Str& key);

    // `text` without one wrapping pair of single backticks.
    Str unquote(const Str& text);

    // One file's entries, in the order they are written (mirrors `resParseText`).
    List<ResourceEntry> parseText(const Str& text);

    // The flat list a compilation keeps: a repeated key takes its last value, in the
    // position it was first written.
    List<ResourceEntry> dedup(const List<ResourceEntry>& entries);

    // Every `_res.md` under each module root, canonical path once, in canonical-path
    // order - the rule `compiler::transpile` applies to the `*.kt` files.
    List<Str> resourceFiles(const List<Str>& moduleRoots);

    // Reads and parses every resource file in order and joins them into the one list.
    List<ResourceEntry> loadFiles(const List<Str>& files);

    // Discovery, parsing and joining in one call, for a driver.
    List<ResourceEntry> load(const List<Str>& moduleRoots);

    // The same, as the emitter's own shape: every key and value as the C++ literal its
    // bytes are written into the program's pool as (`quoteLiteral`), key then value
    // (`Codegen.h`'s `emitProgram`). Entry `i` is the literals `2*i` and `2*i + 1`.
    List<Str> loadLiterals(const List<Str>& moduleRoots);

    // `text` as the C++ narrow string literal the emitter pools a resource as. Every
    // resource's bytes are written from here, so the pool, its length index and the
    // program's view of the text agree: these escapes are exactly the ones
    // `cgLiteralByteLength` counts as one byte each.
    Str quoteLiteral(const Str& text);
}
