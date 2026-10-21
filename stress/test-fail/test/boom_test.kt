package tests

@Test fun boom(): Unit {
    println("about to fail")
    assert(1 == 2, "one is not two")
}

@Test fun never_reached(): Unit {
    assert(true, "this test is behind the abort")
}
