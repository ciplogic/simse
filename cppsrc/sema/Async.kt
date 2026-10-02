// Async.kt
//
// The coloring pass: which functions can suspend, and why. `suspend` on a declaration is the
// marker, and every function that *calls* one can suspend too, transitively, up to `main`. Nothing
// is annotated by hand except the leaf: the least fixed point below carries that outward. A function
// no suspension reaches keeps its plain signature and its direct call, which is the whole point -
// coloring stops at the first function that reaches no suspension, so pure helper code never becomes
// a state machine.
//
// The call graph is *name-level*, the approximation the prelude reachability already uses
// (impl_specs/for.md): a callee is the name a call spells - `f`, `f<T>`, or the member of `x.f` - so
// a name two declarations share is one node and the pass colors conservatively. Closed world is what
// makes the set knowable at all: every module is scanned and nothing links separately, so there is
// no call that could be async without being visible.
//
// `--showAsync` dumps the result (the debug view this pass exists for). Until the machine lowering
// lands, a program that *calls* a suspending function does not compile - there is no task to run -
// so the dump is how the inference is checked.

package sema
import compiler

import common
import io

// A declaration's marker: the `suspend` modifier, which the parser carries as `IsSuspend`
// (impl_specs/async.md). It is a modifier on the declaration, not a type - the signature keeps
// its plain return type, which is why nothing else in the checker has to know about it.
fun asyncDeclared(decl: *AstXmlNode): Bool {
    return xmlAttr(decl, AstNodeAttributeKind.IsSuspend) == "true"
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

// The fixed point: a name is async when it is declared async, or when the body of any function with
// that name calls a name that is. `reasons` runs parallel to the result: the callee that carried the
// suspension in, or "" for a declaration that named `Async` itself - which is what makes the dump a
// path rather than just a set.
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
        val nameText: Str = asyncNames[i]
        var line: Str = `async! @nameText`
        if (reasons[i] == "") {
            line = line + "  (declared suspend)"
        } else {
            val reasonText: Str = reasons[i]
            line = `async  @nameText   <- @reasonText`
        }
        lines.append(line)
        i = i + 1
    }
    lines.sort(compareLessThan)
    for (*line in lines) {
        eprintln(line)
    }
    var sync: Int = 0
    for (*fn in functions) {
        if (!asyncNames.contains(asyncName(fn))) {
            sync = sync + 1
        }
    }
    eprintln(`sync @sync declarations reach no suspension`)
    eprintln("legend: async! is declared suspend, async is inferred from the callee it shows")
}
