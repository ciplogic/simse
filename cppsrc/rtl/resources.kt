// resources.kt
//
// The `Resources` API (specs/resources.md): the text a program carries that is not code,
// as the compiler read it from the `_res.md` files and embedded it in the program's
// string table. The *storage* is C++ (cppsrc/rtl/resources.hpp: the table, and the
// `install` the emitter's generated initializer calls); the API below is the language's
// own code, over the `Span<ResourceEntry>` that header hands out.
//
// Each declaration carries an explicit `this` and names its implementation with
// `@SmGen` - the shape that gives the checker a signature for `Resources.get(key)` and
// the emitter a symbol to call. Three of the four name a plain *Simse* function
// underneath (`resourcesGet`/`resourcesHas`/`resourcesCount`, below): the member-call
// spelling is what a program writes, the free function is the body, and the `cpp`
// generator produces no C++ for them at all. The fourth, `entries`, is the
// raw-pointer's worth of glue the language cannot express - a table built before any of
// the program's code runs, whose accessor is the `resources` section of
// cppsrc/rtl/_res.md over the storage in cppsrc/rtl/resources.hpp.
//
// This file is part of the RTL prelude set, so the type and its methods are in scope in
// every program with no import, whether or not the program has a resource file.

package rtl

// The statics' receiver: a type of its own, so `Resources.get(...)` has a type to be
// qualified by. Nothing constructs one, which is why it has no fields.
data class Resources()

// One entry: the key a program looks it up by, and the value it holds. Both are views
// into the program's string table, so reading a resource copies nothing. This type is
// the C++ `struct ResourceEntry` (cppsrc/rtl/resources.hpp), field for field.
data class ResourceEntry(var key: StrView, var value: StrView)

// Every entry the program carries, in the order the compiler read them. A borrowed
// view: the table is built once, before the program runs, and never grows after.
@SmGen("res", "resources", "simse_resources_entries")
fun entries(this: Resources): Span<ResourceEntry>

// The value `key` holds, empty when the key is absent. The key is the one the resource
// file writes, its section prefix included (`Profiling:Profile BootStrap`).
@SmGen("cpp", "resourcesGet")
fun get(this: Resources, key: Str): StrView

// True when the program carries `key`.
@SmGen("cpp", "resourcesHas")
fun has(this: Resources, key: Str): Bool

// How many resources the program carries.
@SmGen("cpp", "resourcesCount")
fun count(this: Resources): Int

// ---- the lookup, in the language -------------------------------------------

// A linear scan of the handful of entries a program carries. Comparing a view with a
// `Str` is the size test first - so the byte comparison only runs on a candidate - and
// then `startsWith`, which reads both in place: nothing is allocated per entry, which is
// what the C++ this replaced did too.
fun resourcesGet(key: Str): StrView {
    val all: Span<ResourceEntry> = Resources.entries()
    var i: Int = 0
    while (i < all.size()) {
        val entry: ResourceEntry = all[i]
        if (entry.key.size() == key.size() && entry.key.startsWith(key)) {
            return entry.value
        }
        i = i + 1
    }
    // The empty view: a span over nothing (`StrView` *is* a `Span<Char>`).
    return Span<Char>(null, 0)
}

fun resourcesHas(key: Str): Bool {
    val all: Span<ResourceEntry> = Resources.entries()
    var i: Int = 0
    while (i < all.size()) {
        val entry: ResourceEntry = all[i]
        if (entry.key.size() == key.size() && entry.key.startsWith(key)) {
            return true
        }
        i = i + 1
    }
    return false
}

fun resourcesCount(): Int {
    // Bound to a typed local first: a *static* call's result is not a type the emitter
    // infers (`Resources.entries()` is emitted fine, but a member chained straight onto
    // it is resolved against nothing), and the local's type is what resolves `size`.
    val all: Span<ResourceEntry> = Resources.entries()
    return all.size()
}
