package tests

// The shape a suite can call (specs/testing.md): plain - no receiver, not generic, no
// parameters, Unit back, not suspend. One diagnostic names whichever rule broke.

@Test fun takesArguments(n: Int): Unit {
    assert(n > 0, "positive")
}
