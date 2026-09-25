// Async.kt
//
// The coloring pass: which functions are async, and why. `Async<T>` in a return position is
// the marker - it names a function that can suspend and finishes with `T` - and every
// function that *calls* one is async too, transitively, up to `main`. Nothing is annotated by
// hand except the leaf: the user writes the function that actually suspends, and the least
// fixed point below carries that outward. A function no suspension reaches keeps its plain
// signature and its direct call, which is the whole point - coloring stops at the first
// function that reaches no suspension, so pure helper code never becomes a state machine.
//
// The call graph is *name-level*, the approximation the prelude reachability already uses
// (impl_specs/for.md): a callee is the name a call spells - `f`, `f<T>`, or the member of
// `x.f` - so a name two declarations share is one node and the pass colors conservatively.
// Closed world is what makes the set knowable at all: every module is scanned and nothing
// links separately, so there is no call that could be async without being visible.
//
// `--showAsync` dumps the result (the debug view this pass exists for). The machine lowering
// is the next step; until it lands, a program that *calls* an async function does not
// compile - sema has no `Async` type - so the dump is how the inference is checked.

package sema

import common

// Whether a type node is the marker: `Async<...>`, possibly through the name a program spells.
fun asyncIsMarker(typeNode: *AstXmlNode): Bool {
    if (xmlKind(typeNode) != AstNodeCategory.TypeGeneric) {
        return false
    }
    return xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Async"
}

// A declaration's own marker: `Async<T>` in the return position.
fun asyncDeclared(decl: *AstXmlNode): Bool {
    val ret: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    if (xmlIsEmpty(ret)) {
        return false
    }
    return asyncIsMarker(ret)
}

// The names a call in `node` reaches: the spelled name of a `Name`, `GenericName` or `Member`
// callee. A call through a function-typed local has no name to reach, so a body that call
// cannot be colored - the pass is conservative about what it *sees*, not about what it hides.
fun asyncCallees(node: AstXmlNode, out: *List<Str>): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val kind: AstNodeCategory = xmlKind(callee)
        if (
            kind == AstNodeCategory.ExprName || kind == AstNodeCategory.ExprGenericName
            || kind == AstNodeCategory.ExprMember
        ) {
            out.append(xmlAttr(callee, AstNodeAttributeKind.Name))
        }
    }
    for (*child in node.Children) {
        asyncCallees(child, out)
    }
}

// Every function declaration a module contributes: its own, and a data class's methods.
fun asyncCollect(module: *AstXmlNode, decls: *List<AstXmlNode>): Unit {
    val top: List<AstXmlNode> = xmlDecls(module)
    for (*decl in top) {
        if (decl.name == AstNodeKind.Function) {
            decls.append(decl)
        }
        if (decl.name == AstNodeKind.DataClass) {
            val methods: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Function)
            for (*method in methods) {
                decls.append(method)
            }
        }
    }
}

fun asyncName(fn: *AstXmlNode): Str {
    return xmlAttr(fn, AstNodeAttributeKind.Name)
}

// The fixed point: a name is async when it is declared async, or when the body of any
// function with that name calls a name that is. One round can discover more than the last, so
// the loop runs until a round adds nothing. `reasons` runs parallel to the result: the callee
// that carried the suspension in, or "" for a declaration that named `Async` itself - which
// is what makes the dump a path rather than just a set.
fun asyncColor(functions: List<AstXmlNode>, reasons: *List<Str>): List<Str> {
    var asyncNames: List<Str> = List<Str>()
    for (*fn in functions) {
        if (asyncDeclared(fn)) {
            asyncNames.append(asyncName(fn))
            reasons.append("")
        }
    }
    var changed: Bool = true
    var guard: Int = 0
    while (changed && guard < 256) {
        guard = guard + 1
        changed = false
        for (*fn in functions) {
            val name: Str = asyncName(fn)
            if (asyncNames.contains(name)) {
                continue
            }
            var callees: List<Str> = List<Str>()
            asyncCallees(xmlChild(fn, AstNodeKind.Body), callees)
            for (*callee in callees) {
            if (asyncNames.contains(callee)) {
                asyncNames.append(name)
                reasons.append(callee)
                changed = true
                break
            }
        }
        }
    }
    return asyncNames
}

// `--showAsync`: the async side, each line naming the callee that carried the suspension in,
// sorted so two runs agree byte for byte, then how much stayed synchronous.
fun asyncDump(functions: List<AstXmlNode>, asyncNames: List<Str>, reasons: List<Str>): Unit {
    var lines: List<Str> = List<Str>()
    var i: Int = 0
    while (i < asyncNames.size()) {
        var line: Str = fmtStr("async! |", asyncNames[i])
        if (reasons[i] == "") {
            line = line + "  (declared Async<T>)"
        } else {
            line = fmtStr("async  |   <- |", asyncNames[i], reasons[i])
        }
        lines.append(line)
        i = i + 1
    }
    lines.sort((left: Str, right: Str) -> left < right)
    for (*line in lines) {
        eprintln(line)
    }
    var sync: Int = 0
    for (*fn in functions) {
        if (!asyncNames.contains(asyncName(fn))) {
            sync = sync + 1
        }
    }
    eprintln(fmtStr("sync | declarations reach no suspension", sync.toString()))
    eprintln("legend: async! names Async<T>, async is inferred from the callee it shows")
}
