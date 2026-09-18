package broken

// A body-less method has no implementation: it needs `native` or an attribute
// (specs/attributes.md). The case expects the transpile to fail with the
// diagnostic below.

fun nothing(): Int
