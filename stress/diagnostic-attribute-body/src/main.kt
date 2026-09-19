package broken

// A method whose C++ is generated has no body: the generator owns it
// (impl_specs/generators.md). The case expects the transpile to fail with the
// diagnostic below, even though the body would otherwise parse.

@SmGen("cpp", "simse_nowMillis")
fun tick(): Int64 {
    return 0
}
