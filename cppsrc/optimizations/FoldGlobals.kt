// FoldGlobals.kt
//
// The program's constant globals, substituted at their use sites, and the analysis that
// decides which globals deserve the name.
//
//   val world: Str = "world"     var world: Str = "world"
//   ...                          ...
//   print(world)                 print(world)
//      -> print("world")            -> print("world")   (nothing ever writes it)
//
// The analysis is *program-wide* and runs once, before any body is emitted
// (`Emitter.run`): a body's own passes are handed one flat body and nothing else
// (`Optimize.kt`), so what a global is can only be decided where every module is in hand.
// It answers one table, `linConstGlobals`, which `foldGlobalRule` reads.
//
// **Two requests, one analysis.** A `val` global is a candidate because the language
// cannot write it. A `var` global is a candidate exactly when nothing writes it either -
// which is the second optimization (a never-written `var` is a `val`), and it is why the
// pass has no separate switch for the two.
//
// ## What makes it safe
//
// A global is substituted only when the whole program agrees on four things:
//
//   - **Nothing writes the name.** An assignment to the name anywhere - including a
//     compound one, and including an assignment to a *shadowing* local of the same name,
//     which this pass cannot tell apart from a write to the global - disqualifies it. The
//     conservative direction is the only one available: the AST does not record which
//     declaration a name resolved to.
//   - **Nothing takes its address.** `*g` and `&g` are the storage, not the value, so a
//     literal cannot stand for them.
//   - **Nothing binds the name.** A parameter or a local with the name shadows the global
//     in that body, and this pass cannot see scopes - so a name that is bound anywhere is
//     left alone everywhere.
//   - **Nothing borrows it at a call.** `f(g)` where `f`'s parameter is a `*T` or a `&T` is
//     an *implicit* address-of, which no `*g` in the source would tell this pass about, so
//     an argument of a call to a function this pass knows to have a handle parameter is
//     reason enough to leave the name alone. A callee this pass does *not* know (a method,
//     a prelude function, `print`) is read as a by-value call.
//
// The fourth rule is the one a reader should push further: it is deliberately coarse (one
// handle parameter disqualifies every argument of every call to that function), and the
// thorough version of it is a *handle-parameter* check in `sema` - the same shape as the
// view check `Sema.checkViewArgument` already makes - which would turn the one C++ error
// this pass could still produce into a positioned Simse diagnostic.
//
// What the pass does **not** do is drop the global from the program: the storage and its
// initializer are still emitted (`Emitter.emitStatics`), unused. Removing a static nothing
// reads any more is a pass of its own.

package optimizations

import common
import linear

// One constant global: the literal to stand for it, as the kind of node to build and that
// node's own text (`Text` for a number or a string - a string literal keeps its quotes -
// and `Value` for a `Bool`).
data class FoldGlobalConst(
    var kind: FoldKind,
    var text: Str
)

// What the analysis found. One per program, filled before the bodies are emitted.
data class FoldGlobalScan(
    var written: Dictionary<Str, Bool>,
    var addressed: Dictionary<Str, Bool>,
    var bound: Dictionary<Str, Bool>,
    var borrowed: Dictionary<Str, Bool>,
    var handleFns: Dictionary<Str, Bool>,
    var candidates: List<FoldGlobalConst>,
    var names: List<Str>
)

var linGlobalScan: FoldGlobalScan

// The table the passes read: "" is not in it.
var linConstGlobals: Dictionary<Str, FoldGlobalConst>

fun getLinConstGlobals(): *Dictionary<Str, FoldGlobalConst> {
    return * linConstGlobals
}

fun linConstGlobalsReset(): Unit {
    linGlobalScan = FoldGlobalScan(
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), List<FoldGlobalConst>(), List<Str>()
    )
    linConstGlobals = Dictionary<Str, FoldGlobalConst>()
}

// Whether a node is a type a literal can stand in for, paired with the literal kind the
// initializer has to be. `Int8`/`Int16`/`Int32`/`Int64` are the same `Int` literal - the
// declared type is what the slot keeps, and the value is what the literal says.
fun foldGlobalTypeKind(typeName: *Str): FoldKind {
    if (typeName == "Str") {
        return FoldKind.Str
    }
    if (typeName == "Bool") {
        return FoldKind.Bool
    }
    if (typeName == "Char") {
        return FoldKind.Char
    }
    if (typeName == "Int" || typeName == "Int8" || typeName == "Int16"
        || typeName == "Int32" || typeName == "Int64"
    ) {
        return FoldKind.Int
    }
    if (typeName == "Float32" || typeName == "Float64") {
        return FoldKind.Float
    }
    return FoldKind.None
}

// Whether a parameter type is a handle (`&T`, `*T`, or the `PList<T>` alias). The same
// rule `semaIsHandleType`, `ilIsHandleType` and `Emitter.isHandleType` spell - the
// codebase keeps one spelling per package for it rather than a dependency between them.
fun foldIsHandleType(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference) {
        return true
    }
    return kind == AstNodeCategory.TypeGeneric
            && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList"
}

// A file-level `val`/`var` that is a constant: a simple type whose initializer is a literal
// of that type. Empty for anything else - no initializer, a type a literal cannot spell, an
// initializer that is a call.
fun foldGlobalCandidate(decl: *AstXmlNode, out: *List<FoldGlobalConst>, names: *List<Str>): Unit {
    val typeNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Type)
    val init: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Init)
    if (xmlIsEmpty(typeNode) || xmlIsEmpty(init) || xmlKind(typeNode) != AstNodeCategory.TypeNamed) {
        return
    }
    val wanted: FoldKind = foldGlobalTypeKind(xmlAttr(typeNode, AstNodeAttributeKind.Name))
    if (wanted == FoldKind.None || foldKindOf(init) != wanted) {
        return
    }
    var text: Str = xmlAttr(init, AstNodeAttributeKind.Text)
    if (wanted == FoldKind.Bool) {
        text = xmlAttr(init, AstNodeAttributeKind.Value)
    }
    out.append(FoldGlobalConst(wanted, text))
    names.append(xmlAttr(decl, AstNodeAttributeKind.Name))
}

// The declarations of one module: its static candidates, and the functions with a handle
// parameter (what the borrow rule below asks about). Run for every module before
// `foldGlobalScanBodies` runs for any - the handle table has to be whole before a body is
// read, or a call to a module the walk has not reached yet would look by-value.
fun foldGlobalScanDecls(module: *AstXmlNode): Unit {
    for (*decl in module.Children) {
        if (decl.name == AstNodeKind.Var) {
            foldGlobalCandidate(decl, *linGlobalScan.candidates, *linGlobalScan.names)
            continue
        }
        if (decl.name != AstNodeKind.Function) {
            continue
        }
        for (*param in decl.Children) {
        if (param.name != AstNodeKind.Param) {
            continue
        }
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (!xmlIsEmpty(paramType) && foldIsHandleType(paramType)) {
            linGlobalScan.handleFns.insert(xmlAttr(decl, AstNodeAttributeKind.Name), true)
            continue
        }
    }
    }
}

// The names a body binds: a declaration anywhere under it, at any depth, lambda bodies
// included.
fun foldGlobalScanBinds(node: *AstXmlNode): Unit {
    if (xmlKind(node) == AstNodeCategory.StmtVarDecl) {
        linGlobalScan.bound.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    if (node.name == AstNodeKind.Param) {
        linGlobalScan.bound.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
    }
    for (*child in node.Children) {
        foldGlobalScanBinds(child)
    }
}

// What one body says about the globals: what it writes, what it borrows in the source, and
// what it hands to a call whose callee has a handle parameter.
fun foldGlobalScanBody(node: *AstXmlNode): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.StmtAssign) {
        var targetName: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
        if (!xmlIsEmpty(target)) {
            targetName = xmlAttr(target, AstNodeAttributeKind.Name)
        }
        if (targetName != "") {
            linGlobalScan.written.insert(targetName, true)
        }
    }
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
        val operand: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Operand)
        if (!xmlIsEmpty(operand) && xmlKind(operand) == AstNodeCategory.ExprName) {
            linGlobalScan.addressed.insert(xmlAttr(operand, AstNodeAttributeKind.Name), true)
        }
    }
    // A method call's receiver is handed over as a pointer (`T* self`), so a *name* it is
    // made on can be written there: the same escape `foldConstMarkReceiver` covers for a
    // local, and the reason a `val` global is not automatically a constant.
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        if (!xmlIsEmpty(callee) && xmlKind(callee) == AstNodeCategory.ExprMember) {
            val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.ExprName) {
                linGlobalScan.addressed.insert(xmlAttr(recv, AstNodeAttributeKind.Name), true)
            }
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        foldGlobalScanCall(node)
    }
    for (*child in node.Children) {
        foldGlobalScanBody(child)
    }
}

// A call: every `ExprName` argument of a call to a function known to take a handle.
fun foldGlobalScanCall(call: *AstXmlNode): Unit {
    val callee: *AstXmlNode = xmlChildPtr(call, AstNodeKind.Callee)
    if (xmlIsEmpty(callee)) {
        return
    }
    val calleeKind: AstNodeCategory = xmlKind(callee)
    if (calleeKind != AstNodeCategory.ExprName && calleeKind != AstNodeCategory.ExprGenericName) {
        return
    }
    if (!linGlobalScan.handleFns.has(xmlAttr(callee, AstNodeAttributeKind.Name))) {
        return
    }
    for (*arg in call.Children) {
        if (arg.name != AstNodeKind.Arg || xmlKind(arg) != AstNodeCategory.ExprName) {
            continue
        }
        linGlobalScan.borrowed.insert(xmlAttr(arg, AstNodeAttributeKind.Name), true)
    }
}

// Every function body of one module. Run for every module *after*
// `foldGlobalScanDecls` has seen them all.
fun foldGlobalScanBodies(module: *AstXmlNode): Unit {
    for (*decl in module.Children) {
        if (decl.name != AstNodeKind.Function) {
            continue
        }
        foldGlobalScanBinds(decl)
        for (*child in decl.Children) {
        if (child.name == AstNodeKind.Body) {
            foldGlobalScanBody(child)
        }
    }
    }
}

// The candidates the analysis left standing, as the table the passes read.
fun linConstGlobalsBuild(): Unit {
    var i: Int = 0
    while (i < linGlobalScan.candidates.size()) {
        val name: Str = linGlobalScan.names[i]
        var ok: Bool = !linGlobalScan.written.has(name)
        ok = ok && !linGlobalScan.addressed.has(name)
        ok = ok && !linGlobalScan.bound.has(name)
        ok = ok && !linGlobalScan.borrowed.has(name)
        if (ok) {
            linConstGlobals.insert(name, linGlobalScan.candidates[i])
        }
        i = i + 1
    }
}

// ---- the pass --------------------------------------------------------------

// The literal a constant stands for, in the position `e` holds. Shared by the global table
// (`foldGlobalRule`) and the walk's own propagation (`PassFoldConst`), which see the same
// shape: a name and the literal it is worth.
fun foldGlobalLiteral(e: *AstXmlNode, entry: FoldGlobalConst): AstXmlNode {
    if (entry.kind == FoldKind.Bool) {
        return foldBoolLit(e, entry.text == "true")
    }
    // A string literal already carries its quotes (`foldStrLit` is for a fold that *builds*
    // the text of a value), so the entry's own text is written through.
    var kind: AstNodeCategory = AstNodeCategory.ExprStrLit
    if (entry.kind == FoldKind.Int) {
        kind = AstNodeCategory.ExprIntLit
    } else if (entry.kind == FoldKind.Char) {
        kind = AstNodeCategory.ExprCharLit
    } else if (entry.kind == FoldKind.Float) {
        kind = AstNodeCategory.ExprFloatLit
    }
    return foldAsLiteral(e, kind, entry.text)
}

// A use of a constant global is the literal it holds.
fun foldGlobalRule(e: *AstXmlNode): AstXmlNode {
    if (xmlKind(e) != AstNodeCategory.ExprName) {
        return e
    }
    val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
    if (!linConstGlobals.has(name)) {
        return e
    }
    return foldGlobalLiteral(e, linConstGlobals.get(name).value())
}

fun linFoldGlobalsBody(stmts: *List<AstXmlNode>): Bool {
    if (linConstGlobals.size() == 0) {
        return false
    }
    return foldExprsInList(stmts, foldGlobalRule)
}

// Self-registration (`Optimize.kt`).
val linFoldGlobalsPass: Bool = registerLinOptPass("foldGlobals", linFoldGlobalsBody)
