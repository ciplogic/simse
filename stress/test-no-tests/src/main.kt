package fixtures

// `--tests` with nothing marked is a misconfiguration, not an empty pass (specs/testing.md):
// a suite with no tests would answer "passed" to a project whose test roots were never
// compiled in.

fun main(): Int {
    return 0
}
