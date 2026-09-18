// resources.kt
//
// The `Resources` API (specs/resources.md): the text a program carries that is not code,
// as the compiler read it from the `_res.md` files and embedded it in the program's
// string table. Declarations only - the C++ is cppsrc/rtl/resources.hpp, which
// `simse.hpp` includes - and every method is *static*: `Resources.get(key)` is spelled
// the way `Res<Str>.ok(value)` is, a type qualified by its own name, and the C++
// `struct Resources` carries the statics.
//
// This file is part of the RTL prelude set, so the type and its methods are in scope in
// every program with no import, whether or not the program has a resource file.

package rtl

// The statics' receiver: a type of its own, so `Resources.get(...)` has a type to be
// qualified by. Nothing constructs one, which is why it has no fields.
data class Resources()

// The value `key` holds, as a view over the program's string table - so reading a
// resource copies nothing. The key is the one the resource file writes, its section
// prefix included (`Profiling:Profile BootStrap`), and the value is empty when the key is
// absent.
native("simse_resources_get") fun get(this: Resources, key: Str): StrView

// True when the program carries `key`.
native("simse_resources_has") fun has(this: Resources, key: Str): Bool

// How many resources the program carries.
native("simse_resources_count") fun count(this: Resources): Int
