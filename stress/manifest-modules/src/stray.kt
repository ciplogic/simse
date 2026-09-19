package stray

// Deliberately *not* a module of this project: the root's `simse.md` names `alpha` and
// `beta` only, and a root with a manifest is scanned as the modules it names. If this file
// were scanned the case would fail, because it declares a second `main`.

fun main(): Int {
    println(99)
    return 0
}
