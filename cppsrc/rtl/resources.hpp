#pragma once

#include "containers.hpp"
#include "strview.hpp"
#include "types.hpp"

// `Resources` (specs/resources.md): the text a program carries that is not code, as the
// compiler embedded it in the program's string table from the `_res.md` files it read.
// Every entry is a pair of `StrView`s into that table, so the feature owns no text of its
// own and copies none.
//
// The static methods are the shape Simse's `Resources.get(key)` needs - the shape
// `Res<T>.ok(x)` has - because the emitter spells the call `Resources::get(...)`. The
// Simse surface that gives them their signatures is the prelude file
// cppsrc/rtl/resources.kt.
//
// `install` is not part of the language surface: the table the emitter writes just above
// `main` calls it directly, with the program's string table and the `{key, value, key,
// value, ...}` index of its entries. A program with no `_res.md` file emits no call, so
// it carries none of this.
SIMSE_PACK_PUSH
struct ResourceEntry {
    StrView key;
    StrView value;
};
SIMSE_PACK_POP

struct Resources {
    // The value `key` holds, empty when the key is absent. The key is taken by
    // reference: a caller's literal is a `StrView` at the site and converts to a
    // temporary `Str`, which binds to this without a second copy.
    static StrView get(const Str& key);
    static Bool has(const Str& key);
    static Int count();

    static void install(const StrView* table, const Int* index, Int count);
};

// The entries, built once by `install` and read by everything else. The storage is a
// function-local static, so a program that never calls `install` never constructs it.
inline List<ResourceEntry>& simse_resourcesTable() {
    static List<ResourceEntry> entries;
    return entries;
}

inline void Resources::install(const StrView* table, const Int* index, Int count) {
    List<ResourceEntry>& entries = simse_resourcesTable();
    entries.clear();
    for (Int i = 0; i < count; i++) {
        ResourceEntry entry;
        entry.key = table[index[i * 2]];
        entry.value = table[index[i * 2 + 1]];
        entries.push_back(entry);
    }
}

inline StrView Resources::get(const Str& key) {
    List<ResourceEntry>& entries = simse_resourcesTable();
    for (Int i = 0; i < (Int) entries.size(); i++) {
        if (entries[i].key == key) return entries[i].value;
    }
    return StrView();
}

inline Bool Resources::has(const Str& key) {
    List<ResourceEntry>& entries = simse_resourcesTable();
    for (Int i = 0; i < (Int) entries.size(); i++) {
        if (entries[i].key == key) return true;
    }
    return false;
}

inline Int Resources::count() {
    return (Int) simse_resourcesTable().size();
}

// The prelude declares the three operations above as natives on the type, so the checker
// has a signature to read; the emitter spells every call as the static form, so nothing
// reaches these. They exist because a native declaration names a symbol.
inline StrView simse_resources_get(Resources, const Str& key) {
    return Resources::get(key);
}

inline Bool simse_resources_has(Resources, const Str& key) {
    return Resources::has(key);
}

inline Int simse_resources_count(Resources) {
    return Resources::count();
}
