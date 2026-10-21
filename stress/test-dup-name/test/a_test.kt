package alpha

// Two packages may not share a test's name (specs/testing.md): the suite imports both
// packages and calls the bare name, and one `import` cannot disambiguate two declarations.

@Test fun same(): Unit {
    assert(true, "alpha's own")
}
