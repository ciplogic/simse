// FoldGlobals.kt
//
// Constant globals, substituted at their use sites, and the program-wide analysis that decides
// which one (`Emitter.run`, before any body). A global becomes its literal only when nothing in
// the program writes it, takes its address, binds the name, or passes it to a function with a
// handle parameter - the AST does not record what a name resolved to.

package optimizations

import common
import linear

// One constant global: the kind of node to build and its text (`Text`, or `Value` for a `Bool`).
data class FoldGlobalConst(
    var kind: FoldKind,
    var text: Str
)

// What the analysis found; one per program.
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

// The table the passes read; absent means not constant.
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

// The literal kind a type can take, or `None`. The `Int*` widths are one `Int` literal: the slot
// keeps the declared type, the literal its value.
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

// Whether a parameter type is a handle (`&T`, `*T`, `PList<T>`); one spelling per package, like
// `semaIsHandleType`.
fun foldIsHandleType(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference) {
        return true
    }
    return kind == AstNodeCategory.TypeGeneric
            && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList"
}

// A file-level `val`/`var` whose initializer is a literal of its simple type; anything else is not
// a candidate.
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

// One module's static candidates and the functions with a handle parameter. Every module's decls
// run before any body does: the handle table has to be whole, or a call to a module not yet
// reached would look by-value.
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

// The names a body binds, at any depth (lambda bodies included).
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

// What one body says about the globals: writes, source-level borrows, and handle-passing calls.
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
    // A method call's receiver is handed over as `T* self`, so a name it is made on can be written
    // there (the escape `foldConstMarkReceiver` covers): a `val` global is not automatically constant.
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

// Every `ExprName` argument of a call to a function known to take a handle.
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

// Every function body of one module; runs after `foldGlobalScanDecls` has seen them all.
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

// The candidates left standing, as the table the passes read.
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

// The literal a constant stands for, in `e`'s position; shared with `PassFoldConst`'s propagation.
fun foldGlobalLiteral(e: *AstXmlNode, entry: FoldGlobalConst): AstXmlNode {
    if (entry.kind == FoldKind.Bool) {
        return foldBoolLit(e, entry.text == "true")
    }
    // A string literal already carries its quotes (`foldStrLit` builds text; this writes it through).
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
    val entry: *FoldGlobalConst = linConstGlobals.getPtr(name)
    if (entry == null) {
        return e
    }
    return foldGlobalLiteral(e, *entry)
}

fun linFoldGlobalsBody(stmts: *List<AstXmlNode>): Bool {
    if (linConstGlobals.size() == 0) {
        return false
    }
    return foldExprsInList(stmts, foldGlobalRule)
}

// Self-registration (`Optimize.kt`).
val linFoldGlobalsPass: Bool = registerLinOptPass("foldGlobals", linFoldGlobalsBody)
