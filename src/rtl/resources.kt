// resources.kt
//
// The `Resources` API (specs/resources.md): the text a program carries that is not code,
// read from `_res.md` and embedded in the program's string table.
//
// The type and its methods are in every program's scope with no import. The storage and the
// install are the language's too: the emitter writes only the table (string-table indices)
// and the generated initialization pass calls `resourcesInstall` with it before `main`'s
// body, so `Resources` is Simse over Simse in a program that uses it.

package rtl

// The statics' receiver: a type of its own, so `Resources.get(...)` has a type to qualify.
// Nothing constructs one, which is why it has no fields.
data class Resources()

// One entry: the key and its value, both views into the program's string table, so reading
// a resource copies nothing.
data class ResourceEntry(var key: StrView, var value: StrView)

// The entries the program carries, in the order the compiler read them. A file-level static:
// `resourcesInstall` fills it once, before `main`'s body, and `resourcesEntries` borrows it.
// The storage is emitted only when the API (or the install) is reached (`staticReached`).
var resourceStore: List<ResourceEntry>

// The install: the emitter writes the program's string table and one string-table index per
// key and value (specs/resources.md, "What the program carries"), and the generated
// initialization pass calls this with them before `main`'s body. The loop is the language's
// where it used to be C++ in resources.hpp; the table is bound as a *span* first, because a
// `*StrView` index resolves through the pointer to the span's own element, and the entry is
// a typed local so the type is reached with the body that builds one.
fun resourcesInstall(table: *StrView, tableCount: Int, index: *Int, count: Int): Unit {
    val views: Span<StrView> = Span<StrView>(table, tableCount)
    var i: Int = 0
    while (i < count) {
        var entry: ResourceEntry = ResourceEntry(views[index[i * 2]], views[index[i * 2 + 1]])
        resourceStore.append(entry)
        i = i + 1
    }
}

// Every entry the program carries, as one borrowed view: the table is built before the
// program runs and never grows.
@SmGen("cpp", "resourcesEntries")
fun entries(this: Resources): Span<ResourceEntry>

fun resourcesEntries(): Span<ResourceEntry> {
    return spanOf(resourceStore)
}

// The value `key` holds, empty when absent. The key includes its section prefix
// (`Profiling:Profile BootStrap`).
@SmGen("cpp", "resourcesGet")
fun get(this: Resources, key: Str): StrView

@SmGen("cpp", "resourcesHas")
fun has(this: Resources, key: Str): Bool

@SmGen("cpp", "resourcesCount")
fun count(this: Resources): Int

// A linear scan of the handful of entries a program carries: a size test before the byte
// comparison, then `startsWith`, so nothing is allocated per entry.
fun resourcesGet(key: Str): StrView {
    val all: Span<ResourceEntry> = Resources.entries()
    for (entry in all) {
        if (entry.key.size() == key.size() && entry.key.startsWith(key)) {
            return entry.value
        }
    }
    // The empty view: a span over nothing (`StrView` is a `Span<Char>`).
    return Span<Char>(null, 0)
}

fun resourcesHas(key: Str): Bool {
    val all: Span<ResourceEntry> = Resources.entries()
    for (entry in all) {
        if (entry.key.size() == key.size() && entry.key.startsWith(key)) {
            return true
        }
    }
    return false
}

fun resourcesCount(): Int {
    // Bound to a typed local first: a *static* call's result is not a type the emitter
    // infers, so a member chained straight onto `Resources.entries()` resolves against
    // nothing.
    val all: Span<ResourceEntry> = Resources.entries()
    return all.size()
}
