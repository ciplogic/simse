#pragma once

#include "containers.hpp"
#include "span.hpp"
#include "strview.hpp"
#include "types.hpp"

// `Resources` (specs/resources.md): the text a program carries that is not code, as the
// compiler embedded it in the program's string table from the `_res.md` files it read.
// Every entry is a pair of `StrView`s into that table, so the feature owns no text of its
// own and copies none.
//
// This header is only the *storage*: `install` (called by the table the emitter writes)
// and the entries themselves. What the language's API *does* with them is Simse, in the
// prelude file cppsrc/rtl/resources.kt - `Resources.get/has/count` are written there
// over the `Span<ResourceEntry>` the `resources` section of cppsrc/rtl/_res.md hands out,
// so the lookup is the language's own code and this file has nothing to keep in step with
// it.
//
// The one thing that cannot be Simse is the table itself: it is built at start-up, before
// any of the program's code runs, from string-table indices the emitter writes. That is
// why `install` and the storage stay here - the generated initializer calls them - while
// the accessor the prelude's `entries` declaration names is a resource.
SIMSE_PACK_PUSH
struct ResourceEntry {
    StrView key;
    StrView value;
};
SIMSE_PACK_POP

struct Resources {
    // Not part of the language surface: the table the emitter writes just above `main`
    // calls this with the program's string table and the `{key, value, key, value, ...}`
    // index of its entries. A program with no `_res.md` file emits no call, so it carries
    // none of this.
    static void install(const StrView* table, const Int* index, Int count);
};

// The entries, built once by `install` and read by everything else. The storage is a
// function-local static, so a program that never calls `install` never constructs it.
inline List<ResourceEntry>& simse_resourcesStorage() {
    static List<ResourceEntry> entries;
    return entries;
}

inline void Resources::install(const StrView* table, const Int* index, Int count) {
    List<ResourceEntry>& entries = simse_resourcesStorage();
    entries.clear();
    for (Int i = 0; i < count; i++) {
        ResourceEntry entry;
        entry.key = table[index[i * 2]];
        entry.value = table[index[i * 2 + 1]];
        entries.push_back(entry);
    }
}
