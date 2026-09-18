package broken

// A `@SmGen("kt", section)` declaration needs `<section>:source` in a resource
// (impl_specs/generators.md): without it there is nothing to compile, and the failure
// has to name the section rather than surface later as a C++ link error.

@SmGen("kt", "absent")
fun greeting(name: Str): Str
