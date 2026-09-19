package beta

import alpha

// The other module. `fromAlpha` is reached through the package `import alpha` made
// visible: the manifest decides what is *scanned*, an import only decides how a name is
// written (specs/modules.md).

fun main(): Int {
    println(fromAlpha() + 2)
    return 0
}
