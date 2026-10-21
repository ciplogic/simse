package tests

import app

// One test per marked function (specs/testing.md): a plain Unit function the suite calls
// with no arguments; `assert` is the failure path (specs/functions.md, "Fatal errors").

@Test fun twice_doubles(): Unit {
    assert(twice(2) == 4, "twice(2) should be 4")
}

@Test fun zero_is_fixed(): Unit {
    assert(twice(0) == 0, "twice(0) should be 0")
}
