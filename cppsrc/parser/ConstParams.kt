// ConstParams.kt
//
// Constant parameters (impl_specs/const-params.md): a parameter every call site passes the *same
// literal* is that constant, so it becomes a local in the body and the parameter goes away - the
// whole program, because "every call site" is the premise and Simse is a closed world (every
// module root is scanned and nothing links separately).
//
//     fun logMe(isDebug: Bool) {          fun logMe() {
//         if (isDebug) {                      val isDebug: Bool = false;
//             print("is debug")               if (isDebug) {
//         }                                       print("is debug")
//     }                                   }
//     logMe(false)                        logMe()
//     logMe(false)                        logMe()
//
// It runs with the `!!` expansion (cppsrc/compiler/Driver.kt), once every module is parsed and
// *before* sema, so the checker, the lowering and the emitter all see the rewritten program and
// nothing downstream knows the optimization exists. The signature is why it cannot live later:
// the emitter reads a declaration's parameters from the AST at emit time and lowers that AST body
// into the IL (`Codegen.kt`'s `emitFunction`), and the passes under `cppsrc/optimizations/` see
// one already-lowered body at a time - a signature is not theirs to move. So this is an
// AST-to-AST rewrite beside Propagate.kt rather than a linear pass.
//
// The resolution is name-level, like the coloring pass (cppsrc/sema/Async.kt) and `!!`
// (cppsrc/parser/Propagate.kt): a call spells a name and a declaration answers it. Where that
// approximation is not good enough the pass *declines* rather than guess - every condition in
// `cpDecide` is a bug if missed.
//
// Only plain-name calls (`f(x)`), only value parameters (a `*T`/`&T` takes a place, so no
// literal is uniform for it), only syntactic literals with the same kind and the same text, and
// only one parameter per declaration - the smallest thing worth a stress case
// (`stress/fold-const-params`).

package parser

import common

// The identity of a literal argument: its kind and its text, never its line/column - those
// differ per call site, and a comparison that included them would silently never fold anything.
// Anything else (a `null` included) has no identity, and "" is not one.
fun cpLiteralKey(arg: *AstXmlNode): Str {
    val kind: AstNodeCategory = xmlKind(arg)
    if (kind == AstNodeCategory.ExprIntLit) {
        return fmtStr("int:|", xmlAttr(arg, AstNodeAttributeKind.Text))
    }
    if (kind == AstNodeCategory.ExprFloatLit) {
        return fmtStr("float:|", xmlAttr(arg, AstNodeAttributeKind.Text))
    }
    if (kind == AstNodeCategory.ExprStrLit) {
        return fmtStr("str:|", xmlAttr(arg, AstNodeAttributeKind.Text))
    }
    if (kind == AstNodeCategory.ExprCharLit) {
        return fmtStr("char:|", xmlAttr(arg, AstNodeAttributeKind.Text))
    }
    if (kind == AstNodeCategory.ExprBoolLit) {
        // A bool literal keeps its value in `Value`; every other literal keeps it in `Text`.
        return fmtStr("bool:|", xmlAttr(arg, AstNodeAttributeKind.Value))
    }
    return ""
}

// A `*T`/`&T` parameter takes a *place*, so no literal can be uniform for it: test the declared
// type and skip the parameter rather than trusting the argument shape.
fun cpIsPlaceType(typeNode: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    return kind == AstNodeCategory.TypePointer || kind == AstNodeCategory.TypeReference
}

// One plain-name call site: its arguments, in order. A node copy - the pass only reads it, so
// the shared children block is fine.
data class ConstCallSite(
    var args: List<AstXmlNode>
)

// A parameter that folds: the name it belongs to, the parameter's index, and the local that
// replaces it (built from the parameter's declared `Type` and one call site's literal).
data class ConstFold(
    var name: Str,
    var index: Int,
    var local: AstXmlNode
)

fun cpBump(counts: *Dictionary<Str, Int>, name: *Str): Unit {
    var seen: Int = 0
    val found: *Int = counts.getPtr(name)
    if (found != null) {
        seen = * found
    }
    counts.insert(name, seen + 1)
}

// Every declaration a module contributes, by name: its top-level declarations and a data class's
// methods. "Declared exactly once" is what lets a call site's name be attributed to one
// declaration, and counting a data class (or an enum, or a type alias) as well is what keeps a
// construction from being read as a call to a function of the same name.
fun cpCountDecls(module: *AstXmlNode, counts: *Dictionary<Str, Int>): Unit {
    val top: List<AstXmlNode> = xmlDecls(module)
    for (*decl in top) {
        cpBump(counts, xmlAttr(decl, AstNodeAttributeKind.Name))
        if (decl.name == AstNodeKind.DataClass) {
            val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
            for (*method in methods) {
                cpBump(counts, xmlAttr(method, AstNodeAttributeKind.Name))
            }
        }
    }
}

// The program's fold candidates, in module then declaration order: a function with a body,
// declared exactly once over the program and the prelude, and not `main` (the runtime calls it,
// so its signature is not the program's to change). A *prelude* declaration is not a candidate
// either - it has nowhere to put the local.
fun cpCollectCandidates(
    module: *AstXmlNode, counts: *Dictionary<Str, Int>,
    out: *List<AstXmlNode>, names: *Dictionary<Str, Bool>
): Unit {
    val top: List<AstXmlNode> = xmlDecls(module)
    for (*decl in top) {
        if (decl.name != AstNodeKind.Function) {
            continue
        }
        // The declaration has a body: a native declaration has nowhere to put the local.
        if (xmlAttr(decl, AstNodeAttributeKind.HasBody) != "true"
            || xmlAttr(decl, AstNodeAttributeKind.IsNative) == "true"
        ) {
            continue
        }
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        if (name == "main") {
            continue
        }
        // The name is declared exactly once: two declarations sharing a name mean the call sites
        // cannot be attributed, and an overload set would be rewritten wrongly.
        val count: *Int = counts.getPtr(name)
        if (count == null || * count != 1) {
            continue
        }
        out.append(decl)
        names.insert(name, true)
    }
}

// The dictionary's own list is appended to through `getPtr` (no write-back); a fresh one is
// inserted only the first time a name is seen.
fun cpAddCall(sites: *Dictionary<Str, List<ConstCallSite>>, name: Str, args: List<AstXmlNode>): Unit {
    val existing: *List<ConstCallSite> = sites.getPtr(name)
    if (existing != null) {
        existing.append(ConstCallSite(args))
        return
    }
    var fresh: List<ConstCallSite> = List<ConstCallSite>()
    fresh.append(ConstCallSite(args))
    sites.insert(name, fresh)
}

// Gather, depth first: a plain-name call site (`ExprCall` whose callee is an `ExprName`) and a
// name used as a *value* (an `ExprName` that is not a call's callee). Only the candidate names
// are remembered - anything else can never fold, so storing it would only cost memory.
fun cpGather(
    node: *AstXmlNode, names: *Dictionary<Str, Bool>,
    sites: *Dictionary<Str, List<ConstCallSite>>, valueUsed: *Dictionary<Str, Bool>
): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        if (xmlKind(callee) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (names.has(name)) {
                cpAddCall(sites, name, xmlChildren(node, AstNodeKind.Arg))
            }
        }
    } else if (xmlKind(node) == AstNodeCategory.ExprName) {
        // `applyOne(21, doubleIt)` makes `doubleIt`'s signature part of a function type, and
        // changing it breaks the use - the condition that is easy to forget, because the call
        // sites look perfect.
        if (node.name != AstNodeKind.Callee) {
            val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            if (names.has(name)) {
                valueUsed.insert(name, true)
            }
        }
    }
    for (*child in node.Children) {
        cpGather(child, names, sites, valueUsed)
    }
}

// The local a folded parameter leaves: `val <name>: <Type> = <literal>`, typed because a body
// that yields turns its locals into fields and a field needs a type (impl_specs/yield.md), so an
// untyped local would be rejected by the yieldable lowering.
fun cpBuildLocal(param: *AstXmlNode, arg: *AstXmlNode): AstXmlNode {
    val paramName: Str = xmlAttr(param, AstNodeAttributeKind.Name)
    var attrs: List<AstNodeAttribute> = listOf<AstNodeAttribute>(
        AstNodeAttribute(AstNodeAttributeKind.Line, xmlLine(param).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Column, xmlColumn(param).toString()),
        AstNodeAttribute(AstNodeAttributeKind.Name, paramName),
        AstNodeAttribute(AstNodeAttributeKind.IsVar, "false")
    )
    var node: AstXmlNode = AstXmlNode(AstNodeKind.Stmt, AstNodeCategory.StmtVarDecl, attrs, Array<AstXmlNode>())
    var typeNode: AstXmlNode = xmlChild(param, AstNodeKind.Type)
    xmlAddChild(node, typeNode)
    var init: AstXmlNode = * arg
    init.name = AstNodeKind.Init
    xmlAddChild(node, init)
    return node
}

// The uniform literal a position holds across every call site, or none: the first site's key
// (empty when it is not a literal) must be non-empty and every other site's must equal it.
fun cpUniformLiteral(group: *List<ConstCallSite>, index: Int): Opt<Str> {
    val first: Str = cpLiteralKey(*group[0].args[index])
    if (first == "") {
        return Opt<Str>.none()
    }
    var k: Int = 1
    while (k < group.size()) {
        if (cpLiteralKey(*group[k].args[index]) != first) {
            return Opt<Str>.none()
        }
        k = k + 1
    }
    return Opt<Str>.some(first)
}

// One candidate's decision, on the original program: the first parameter that is a value
// parameter carrying the same literal at every call site. Nothing about the program is consulted
// after the call set and the value uses, so the decision is a pure function of them.
fun cpDecide(
    decl: *AstXmlNode, sites: *Dictionary<Str, List<ConstCallSite>>,
    valueUsed: *Dictionary<Str, Bool>
): Opt<ConstFold> {
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    // The name is never used as a value.
    if (valueUsed.has(name)) {
        return Opt<ConstFold>.none()
    }
    // At least one call site: zero makes "all of them agree" vacuously true, with no value to
    // fold in.
    val group: *List<ConstCallSite> = sites.getPtr(name)
    if (group == null || group.size() == 0) {
        return Opt<ConstFold>.none()
    }
    // Every call site passes exactly `Params.size()` arguments, or an index no longer identifies
    // a parameter (a trailing-argument pack, or an overload).
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    var k: Int = 0
    while (k < group.size()) {
        if (group[k].args.size() != params.size()) {
            return Opt<ConstFold>.none()
        }
        k = k + 1
    }
    var i: Int = 0
    while (i < params.size()) {
        val declared: *AstXmlNode = xmlChildPtr(*params[i], AstNodeKind.Type)
        if (!xmlIsEmpty(declared) && !cpIsPlaceType(declared)) {
            val key: Opt<Str> = cpUniformLiteral(group, i)
            if (key.hasValue()) {
                val local: AstXmlNode = cpBuildLocal(*params[i], *group[0].args[i])
                return Opt<ConstFold>.some(ConstFold(name, i, local))
            }
        }
        i = i + 1
    }
    return Opt<ConstFold>.none()
}

// The children with the `argIndex`-th `Arg` dropped: the parameter's own index identifies the
// argument, because every call site was shown to pass exactly one argument per parameter.
fun cpDropArg(children: Array<AstXmlNode>, argIndex: Int): Array<AstXmlNode> {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    var seen: Int = 0
    for (*child in children) {
        if (child.name == AstNodeKind.Arg) {
            val ordinal: Int = seen
            seen = seen + 1
            if (ordinal == argIndex) {
                continue
            }
        }
        var kept: AstXmlNode = child
        kids.append(kept)
    }
    return kids.toArray()
}

// The argument this node drops, or -1 when it is not a plain-name call to a folded name. A
// member call's callee is an `ExprMember`, so an extension is folded only when it happens to be
// called as a plain function - never through its receiver form.
fun cpCallIndex(node: *AstXmlNode, folds: *Dictionary<Str, Int>): Int {
    if (xmlKind(node) != AstNodeCategory.ExprCall) {
        return -1
    }
    val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
    if (xmlKind(callee) != AstNodeCategory.ExprName) {
        return -1
    }
    val at: *Int = folds.getPtr(xmlAttr(callee, AstNodeAttributeKind.Name))
    if (at == null) {
        return -1
    }
    return * at
}

// Whether a fold reaches anywhere under this node: the read-only walk the rewrite's pre-check
// runs, so a body that mentions no folded name is kept as it is (the same node) and the cost is
// proportional to the folds, not to the program.
fun cpHasFoldCall(node: *AstXmlNode, folds: *Dictionary<Str, Int>): Bool {
    if (cpCallIndex(node, folds) >= 0) {
        return true
    }
    for (*child in node.Children) {
        if (cpHasFoldCall(child, folds)) {
            return true
        }
    }
    return false
}

// This subtree with every folded call losing one argument; a child no fold reaches is kept as it
// is, so only the folds are rebuilt.
fun cpRewriteCalls(node: *AstXmlNode, folds: *Dictionary<Str, Int>): AstXmlNode {
    var out: AstXmlNode = AstXmlNode(node.name, node.kind, node.attributes, Array<AstXmlNode>())
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in node.Children) {
        if (cpHasFoldCall(child, folds)) {
            kids.append(cpRewriteCalls(child, folds))
        } else {
            var kept: AstXmlNode = child
            kids.append(kept)
        }
    }
    out.Children = kids.toArray()
    val at: Int = cpCallIndex(node, folds)
    if (at >= 0) {
        out.Children = cpDropArg(out.Children, at)
    }
    return out
}

// A declaration that folds: the parameter is dropped (by ordinal among the `Param` children) and
// the local is prepended to the body. A recursive call in that body is a folded call like any
// other, so the body's statements go through `cpRewriteCalls`.
fun cpRewriteFoldDecl(
    decl: *AstXmlNode, paramIndex: Int, local: *AstXmlNode, folds: *Dictionary<Str, Int>
): AstXmlNode {
    var out: AstXmlNode = AstXmlNode(decl.name, decl.kind, decl.attributes, Array<AstXmlNode>())
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    var p: Int = 0
    for (*child in decl.Children) {
        if (child.name == AstNodeKind.Param) {
            val ordinal: Int = p
            p = p + 1
            if (ordinal == paramIndex) {
                continue
            }
            var kept: AstXmlNode = child
            kids.append(kept)
            continue
        }
        if (child.name == AstNodeKind.Body) {
            var stmts: List<AstXmlNode> = List<AstXmlNode>()
            var seed: AstXmlNode = * local
            stmts.append(seed)
            for (*stmt in child.Children) {
                if (cpHasFoldCall(stmt, folds)) {
                    stmts.append(cpRewriteCalls(stmt, folds))
                } else {
                    var kept: AstXmlNode = stmt
                    stmts.append(kept)
                }
            }
            kids.append(AstXmlNode(child.name, child.kind, child.attributes, stmts.toArray()))
            continue
        }
        var kept: AstXmlNode = child
        kids.append(kept)
    }
    out.Children = kids.toArray()
    return out
}

// One module rewritten: a folded declaration rebuilt, a declaration a fold reaches rebuilt
// (its calls only), and everything else kept. A module no fold reaches is the same node, so the
// cost is proportional to the folds (the driver reassigns its `modules` list from the result).
fun cpRewriteModule(
    module: *AstXmlNode, folds: *Dictionary<Str, Int>, locals: *Dictionary<Str, AstXmlNode>
): AstXmlNode {
    var changed: Bool = false
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in module.Children) {
        if (child.name == AstNodeKind.Function && folds.has(xmlAttr(child, AstNodeAttributeKind.Name))) {
            val name: Str = xmlAttr(child, AstNodeAttributeKind.Name)
            val at: *Int = folds.getPtr(name)
            val local: *AstXmlNode = locals.getPtr(name)
            if (at != null && local != null) {
                kids.append(cpRewriteFoldDecl(child, *at, local, folds))
                changed = true
                continue
            }
        }
        if (xmlIsDecl(child) && cpHasFoldCall(child, folds)) {
            kids.append(cpRewriteCalls(child, folds))
            changed = true
            continue
        }
        var kept: AstXmlNode = child
        kids.append(kept)
    }
    if (!changed) {
        return module
    }
    return AstXmlNode(module.name, module.kind, module.attributes, kids.toArray())
}

// The pass (impl_specs/const-params.md): the program modules, with every foldable constant
// parameter specialized away. The walk order is fixed (module order, then declaration order) and
// the decision is a pure function of the call set, so two runs agree byte for byte - the property
// the bootstrap fixed point rests on.
fun cpFoldConstParams(preludeModules: List<AstXmlNode>, modules: List<AstXmlNode>): List<AstXmlNode> {
    // 1. Declarations, by name, over the prelude and the program.
    var counts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    for (*pre in preludeModules) {
        cpCountDecls(pre, *counts)
    }
    for (*mod in modules) {
        cpCountDecls(mod, *counts)
    }

    // The candidates (a function with a body, declared once, not `main`), in a fixed order.
    var candidates: List<AstXmlNode> = List<AstXmlNode>()
    var candidateNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*mod in modules) {
        cpCollectCandidates(mod, *counts, *candidates, *candidateNames)
    }
    if (candidates.size() == 0) {
        return modules
    }

    // 2. Gather the call sites and the value uses, for the candidates only.
    var sites: Dictionary<Str, List<ConstCallSite>> = Dictionary<Str, List<ConstCallSite>>()
    var valueUsed: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        cpGather(pre, *candidateNames, *sites, *valueUsed)
    }
    for (*mod in modules) {
        cpGather(mod, *candidateNames, *sites, *valueUsed)
    }

    // 3. Decide first, rewrite second, so every decision rests on the original program.
    var folds: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var locals: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    for (*decl in candidates) {
        val fold: Opt<ConstFold> = cpDecide(decl, *sites, *valueUsed)
        if (fold.hasValue()) {
            folds.insert(fold.value().name, fold.value().index)
            locals.insert(fold.value().name, fold.value().local)
        }
    }
    if (folds.size() == 0) {
        return modules
    }

    // 4. The program modules rewritten. The prelude is not a fold candidate and its bodies name
    //    no program function, so only the program's modules are rewritten.
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*mod in modules) {
        out.append(cpRewriteModule(mod, *folds, *locals))
    }
    return out
}
