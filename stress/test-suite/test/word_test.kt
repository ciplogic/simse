package tests

// A second file of the same package: the suite orders tests by module (canonical path)
// then declaration, so everything in math_test.kt runs first.

@Test fun shouting(): Unit {
    val word: Str = "ha"
    assert(word.size() == 2, "the word is two bytes")
}
