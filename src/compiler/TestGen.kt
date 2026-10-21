// TestGen.kt
//
// The `--tests` build (specs/testing.md): every function a compiled module marks `@Test`
// (specs/attributes.md, "Marker attributes") becomes one call in a generated suite, and
// the suite's `main` replaces the program's own - a test binary runs the tests, not the
// application. The pass runs in the driver beside the `kt` generator's reparse step
// (impl_specs/generators.md): it reads the parsed modules before sema, so the suite it
// hands back is checked and emitted like any program module.
//
// The suite is a straight-line program on purpose. A failure is an abort, not a value
// (specs/functions.md, "Fatal errors"): `assert` inside a test writes the message to
// stderr, flushes stdout - the `running <pkg>.<name>` line ahead of the failing test is
// buffered until then - and the process dies with the platform's abort code, later tests
// unrun. Nothing unwinds, so there is nothing a runner could catch; the line printed
// ahead of each call is what says which test died.

package compiler

import common

// `--tests`: set by the driver's CLI, read by the pass below.
var testsFlag: Bool = false

fun tgSetTests(value: Bool): Unit {
    testsFlag = value
}

fun tgTests(): Bool {
    return testsFlag
}

// One collected test: the package it lives in (the suite imports it and calls the bare
// name), the function's own name, and where it was declared - the position a shape
// diagnostic names. Modules are walked in the driver's canonical-path order and
// declarations in source order, so the suite is byte-stable for the same file set.
data class TgTest(
    var pkg: Str,
    var name: Str,
    var fileName: Str,
    var line: Int,
    var column: Int
)

// A marked function the suite can call: no receiver, no type parameters, no parameters,
// `Unit` back. One diagnostic covers the shape - the position names the offender - and
// answers "" when the declaration fits.
fun tgShapeError(test: *TgTest, decl: *AstXmlNode): Str {
    val fileName: Str = test.fileName
    val line: Int = test.line
    val column: Int = test.column
    val name: Str = test.name
    if (xmlAttr(decl, AstNodeAttributeKind.HasReceiver) == "true") {
        return "${fileName}:${line}:${column}: an @Test function is a plain Unit function with no parameters ('${name}' has a receiver)"
    }
    if (xmlCount(decl, AstNodeKind.TypeParam) > 0) {
        return "${fileName}:${line}:${column}: an @Test function is a plain Unit function with no parameters ('${name}' is generic)"
    }
    if (xmlCount(decl, AstNodeKind.Param) > 0) {
        return "${fileName}:${line}:${column}: an @Test function is a plain Unit function with no parameters ('${name}' takes parameters)"
    }
    val returnType: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (!xmlIsEmpty(returnType)
        && !(xmlKind(returnType) == AstNodeCategory.TypeNamed
            && xmlAttr(returnType, AstNodeAttributeKind.Name) == "Unit")
    ) {
        return "${fileName}:${line}:${column}: an @Test function is a plain Unit function with no parameters ('${name}' returns a value the suite discards)"
    }
    if (xmlAttr(decl, AstNodeAttributeKind.IsSuspend) == "true") {
        return "${fileName}:${line}:${column}: an @Test function is a plain Unit function with no parameters ('${name}' is suspend)"
    }
    return ""
}

// Whether a declaration is the entry point a `--tests` build drops: the emitter picks
// `main` by that name alone (src/codegen/CgEmitFn.kt), and a second `main` beside the
// suite's is a C++ redefinition, so the program's own is removed from the tree instead.
fun tgIsMain(decl: *AstXmlNode): Bool {
    if (decl.name != AstNodeKind.Function || xmlIsProtocolDecl(decl)) {
        return false
    }
    return xmlAttr(decl, AstNodeAttributeKind.Name) == "main"
        && xmlAttr(decl, AstNodeAttributeKind.HasReceiver) != "true"
}

// The pass: walk the program modules (the prelude carries no tests), collect the marked
// functions, drop every program `main`, and answer the suite's source. `modules` is
// mutated in place - a module holding `main` has its children rebuilt without it - and
// `fileNames` runs parallel to it, naming each module in diagnostics.
fun tgSuite(modules: *List<AstXmlNode>, fileNames: *List<Str>): Res<Str> {
    var tests: List<TgTest> = List<TgTest>()
    var m: Int = 0
    while (m < modules.size()) {
        val module: *AstXmlNode = *modules[m]
        val fileName: Str = fileNames[m]
        val pkg: Str = xmlAttr(module, AstNodeAttributeKind.Package)
        var dropped: Bool = false
        var kept: List<AstXmlNode> = List<AstXmlNode>()
        for (*child in module.Children) {
            if (tgIsMain(child)) {
                dropped = true
            } else {
                kept.append(child)
                if (child.name == AstNodeKind.Function
                    && !xmlIsProtocolDecl(child)
                    && xmlAttr(child, AstNodeAttributeKind.Attribute) == "Test"
                ) {
                    val test: TgTest = TgTest(
                        pkg, xmlAttr(child, AstNodeAttributeKind.Name), fileName,
                        xmlLine(child), xmlColumn(child)
                    )
                    val shape: Str = tgShapeError(*test, child)
                    if (shape != "") {
                        return Res<Str>.err(shape)
                    }
                    tests.append(test)
                }
            }
        }
        if (dropped) {
            module.Children = kept.toArray()
        }
        m++
    }
    if (tests.size() == 0) {
        return Res<Str>.err("--tests found no @Test function among the compiled modules")
    }

    // The suite imports each test's package once, in first-seen order, and calls the bare
    // name. Two packages sharing a test's name cannot both be imported - one `import`
    // cannot disambiguate two declarations of one name (the `json` generator's rule) - so
    // the second is a diagnostic rather than a surprise at the call.
    var packages: List<Str> = List<Str>()
    var t: Int = 0
    while (t < tests.size()) {
        val test: TgTest = tests[t]
        if (test.pkg != "rtl" && !packages.contains(test.pkg)) {
            packages.append(test.pkg)
        }
        var o: Int = 0
        while (o < t) {
            val other: TgTest = tests[o]
            if (other.name == test.name && other.pkg != test.pkg) {
                val fileName: Str = test.fileName
                val line: Int = test.line
                val column: Int = test.column
                val name: Str = test.name
                val firstPkg: Str = other.pkg
                val secondPkg: Str = test.pkg
                return Res<Str>.err(
                    `@fileName:@line:@column: duplicate test name '@name' (packages '@firstPkg' and '@secondPkg'; the suite imports both, so rename one)`
                )
            }
            o++
        }
        t++
    }

    var out: Str = Str()
    out.appendStr("package rtl\n")
    out.append('\n')
    for (pkg in packages) {
        out.appendStr("import ")
        out.appendStr(pkg)
        out.append('\n')
    }
    if (packages.size() > 0) {
        out.append('\n')
    }
    out.appendStr("fun main(): Int {\n")
    t = 0
    while (t < tests.size()) {
        val test: TgTest = tests[t]
        out.appendStr("    println(\"running ")
        out.appendStr(test.pkg)
        out.append('.')
        out.appendStr(test.name)
        out.appendStr("\")\n")
        out.appendStr("    ")
        out.appendStr(test.name)
        out.appendStr("()\n")
        t++
    }
    if (tests.size() == 1) {
        out.appendStr("    println(\"1 test passed\")\n")
    } else {
        out.appendStr("    println(\"")
        out.appendStr(tests.size().toString())
        out.appendStr(" tests passed\")\n")
    }
    out.appendStr("    return 0\n")
    out.appendStr("}\n")
    return Res<Str>.ok(out)
}
