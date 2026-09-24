// resources.kt
//
// The `Resources` API (specs/resources.md): the text a program carries that is not code,
// read from `_res.md` and embedded in the program's string table.
//
// The type and its methods are in every program's scope with no import.

package rtl

// The statics' receiver: a type of its own, so `Resources.get(...)` has a type to qualify.
// Nothing constructs one, which is why it has no fields.
data class Resources()

// One entry: the key and its value, both views into the program's string table, so reading
// a resource copies nothing.
data class ResourceEntry(var key: StrView, var value: StrView)

// Every entry the program carries, in the order the compiler read them. A borrowed view:
// the table is built before the program runs and never grows.
@SmGen("res", "resources", "simse_resources_entries")
fun entries(this: Resources): Span<ResourceEntry>

// The value `key` holds, empty when absent. The key includes its section prefix
// (`Profiling:Profile BootStrap`).
@SmGen("cpp", "resourcesGet")
fun get(this: Resources, key: Str): StrView

// True when the program carries `key`.
@SmGen("cpp", "resourcesHas")
fun has(this: Resources, key: Str): Bool

// How many resources the program carries.
@SmGen("cpp", "resourcesCount")
fun count(this: Resources): Int

// A linear scan of the handful of entries a program carries: a size test before the byte
// comparison, then `startsWith`, so nothing is allocated per entry.
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
    // The empty view: a span over nothing (`StrView` is a `Span<Char>`).
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
    // infers, so a member chained straight onto `Resources.entries()` resolves against
    // nothing.
    val all: Span<ResourceEntry> = Resources.entries()
    return all.size()
}
