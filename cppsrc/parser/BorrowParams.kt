// BorrowParams.kt
//
// Auto-borrow (impl_specs/escape-analysis.md, the "auto-borrow" slice): a function parameter of a
// heavy value type becomes a `*T` when the function never writes it and never lets it escape, so
// every call site stops deep-copying the argument. The pass rewrites the *declaration* before sema
// runs, so the checker, the lowering and the emitter all see the borrowed program and nothing
// downstream knows the optimization exists - the same shape `cpFoldConstParams` has.
//
// The rule is the simplest one that is sound, and it refuses by default ("unsure means it
// escapes"):
//
//   - a call is trusted only when the callee is *pure* (`data`).
//     A body that calls anything else borrows nothing: a callee can write through an alias the
//     analysis cannot see, and a `data` promise is the one "writes nothing" fact the language has.
//     A construction (`Point(1, 2)`) is not a call on the parameter and is allowed;
//   - any assignment whose target is not a plain name (`x.f = ...`, `x[i] = ...`, `*x = ...`)
//     borrows nothing, anywhere in the body. The author's own rule, and it covers a write through
//     an alias without the analysis having to look for one;
//   - a parameter under `&`/`*`, assigned, or captured by a lambda is not borrowed itself. A
//     capture would copy the *pointer* into the closure, which is exactly an escape.
//
// Everything else a parameter can do is a *read*: `p.f`, `p[i]`, `p.size()`, `p` as a value, and
// `return p` (a value return copies, so the pointer does not escape). That is the shape the author
// named - "string->size() and return" - and few bodies qualify, which is the point: the proof is
// "this body only reads".
//
// This is a proof of concept: it deliberately does not look through calls (no interprocedural
// summary), does not measure type sizes, and handles only a type it can name as heavy (`Str`, a
// `data class`, a container) - never a scalar, a handle or `Span`/`Array`.

package parser

import common

// ---- the sets the rule reads ------------------------------------------------

fun bpBump(counts: *Dictionary<Str, Int>, name: *Str): Unit {
    var seen: Int = 0
    val found: *Int = counts.getPtr(name)
    if (found != null) {
        seen = * found
    }
    counts.insert(name, seen + 1)
}

// The same declaration counting `cpCountDecls` does, plus the two sets the rule needs: the names a
// value may be borrowed *of* (a `data class`), and the callees a body may call (`data fun`/`data`
// methods). `size`/`count` are declarations like any other (cppsrc/rtl/rtl.kt), so no name is
// seeded.
fun bpCollectDecls(
    module: *AstXmlNode, counts: *Dictionary<Str, Int>, types: *Dictionary<Str, Bool>,
    pure: *Dictionary<Str, Bool>
): Unit {
    for (*decl in xmlDecls(module)) {
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        bpBump(counts, name)
        if (decl.name == AstNodeKind.DataClass) {
            types.insert(name, true)
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpBump(counts, xmlAttr(method, AstNodeAttributeKind.Name))
                if (xmlAttr(method, AstNodeAttributeKind.IsPure) == "true") {
                    pure.insert(xmlAttr(method, AstNodeAttributeKind.Name), true)
                }
            }
        }
        if (decl.name == AstNodeKind.Function && xmlAttr(decl, AstNodeAttributeKind.IsPure) == "true") {
            pure.insert(name, true)
        }
    }
}

// One declaration considered: a function with a body, not native, not `main` (the runtime calls
// it), declared exactly once over the program and the prelude. A name declared twice cannot be
// attributed to a signature, and a prelude declaration has its C++ written by hand.
fun bpConsider(
    fn: *AstXmlNode, counts: *Dictionary<Str, Int>, out: *List<AstXmlNode>,
    names: *Dictionary<Str, Bool>
): Unit {
    if (xmlAttr(fn, AstNodeAttributeKind.HasBody) != "true"
        || xmlAttr(fn, AstNodeAttributeKind.IsNative) == "true"
    ) {
        return
    }
    val name: Str = xmlAttr(fn, AstNodeAttributeKind.Name)
    if (name == "main") {
        return
    }
    val count: *Int = counts.getPtr(name)
    if (count == null || * count != 1) {
        return
    }
    out.append(fn)
    names.insert(name, true)
}

// The program's candidates, in module then declaration order: every top-level function, and every
// method of a data class.
fun bpCandidates(
    module: *AstXmlNode, counts: *Dictionary<Str, Int>, out: *List<AstXmlNode>,
    names: *Dictionary<Str, Bool>
): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            bpConsider(decl, counts, out, names)
        } else if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpConsider(method, counts, out, names)
            }
        }
    }
}

// A candidate name used as a *value* - `apply(doubleIt)` puts its signature into a function type,
// so changing it breaks the use. The same walk `cpGather` does, without the call sites.
fun bpGatherValueUses(
    node: *AstXmlNode, names: *Dictionary<Str, Bool>, valueUsed: *Dictionary<Str, Bool>
): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprName && node.name != AstNodeKind.Callee) {
        val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (names.has(name)) {
            valueUsed.insert(name, true)
        }
    }
    for (*child in node.Children) {
        bpGatherValueUses(child, names, valueUsed)
    }
}

// ---- the body's facts -------------------------------------------------------

// Every fact the decision reads, written in place through the pointer. The marking helpers are
// methods rather than free functions taking the set: passing a *field* of a pointer as an argument
// materializes a copy (`guide4ai.md`, the `*this.sections` trap), so the set must be reached
// through `this`.
data class BpFacts(
    var fieldWrite: Bool,
    var impureCall: Bool,
    var pure: *Dictionary<Str, Bool>,
    var taken: Dictionary<Str, Bool>,
    var written: Dictionary<Str, Bool>,
    var inLambda: Dictionary<Str, Bool>
) {
    // Every name under `node`: an over-approximation is what the rule wants, since it is asked only
    // about names it already cares about.
    fun markTaken(node: *AstXmlNode): Unit {
        if (xmlKind(node) == AstNodeCategory.ExprName) {
            this.taken.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
        }
        for (*child in node.Children) {
            this.markTaken(child)
        }
    }

    fun markLambda(node: *AstXmlNode): Unit {
        if (xmlKind(node) == AstNodeCategory.ExprName) {
            this.inLambda.insert(xmlAttr(node, AstNodeAttributeKind.Name), true)
        }
        for (*child in node.Children) {
            this.markLambda(child)
        }
    }

    // One statement (or expression) walked, collecting every fact.
    fun walk(node: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(node)
        if (kind == AstNodeCategory.StmtAssign) {
            val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
            if (xmlKind(target) != AstNodeCategory.ExprName) {
                // A write through a field, an index or a pointer: the coarse bail the rule wants.
                this.fieldWrite = true
            } else {
                this.written.insert(xmlAttr(target, AstNodeAttributeKind.Name), true)
            }
        }
        if (kind == AstNodeCategory.ExprCall) {
            val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
            val ck: AstNodeCategory = xmlKind(callee)
            var safe: Bool = false
            if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember) {
                safe = this.pure.has(xmlAttr(callee, AstNodeAttributeKind.Name))
            } else if (ck == AstNodeCategory.ExprGenericName) {
                // A construction is not a call on the parameter - it copies what it is given.
                safe = true
            }
            if (!safe) {
                this.impureCall = true
            }
        }
        if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
            this.markTaken(node)
        }
        if (kind == AstNodeCategory.ExprLambda) {
            this.markLambda(node)
        }
        for (*child in node.Children) {
            this.walk(child)
        }
    }
}

// ---- the decision -----------------------------------------------------------

// A type worth borrowing: a value the C++ copies on every call. A scalar is not (it is passed in a
// register), a handle is not (it is already a pointer), and `Span`/`Array` are not (`Span` is a
// pointer and a length; `Array` is already a shared reference).
fun bpHeavy(typeNode: *AstXmlNode, types: *Dictionary<Str, Bool>): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    if (kind == AstNodeCategory.TypeGeneric) {
        return name != "Span" && name != "Array"
    }
    if (kind != AstNodeCategory.TypeNamed) {
        return false
    }
    return name == "Str" || types.has(name)
}

// ---- the rewrite ------------------------------------------------------------

// The parameter's type as a `*T`, the borrow spelling the language already has.
fun bpPointerNode(inner: *AstXmlNode): AstXmlNode {
    var node: AstXmlNode = AstXmlNode(
        AstNodeKind.Type, AstNodeCategory.TypePointer,
        List<AstNodeAttribute>(), Array<AstXmlNode>()
    )
    var renamed: AstXmlNode = inner
    renamed.name = AstNodeKind.Inner
    xmlAddChild(node, renamed)
    return node
}

// The parameter with its `Type` child replaced by the pointer.
fun bpBorrowParam(param: *AstXmlNode): AstXmlNode {
    var out: AstXmlNode = AstXmlNode(param.name, param.kind, param.attributes, Array<AstXmlNode>())
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in param.Children) {
        if (child.name == AstNodeKind.Type) {
            kids.append(bpPointerNode(*child))
        } else {
            var kept: AstXmlNode = child
            kids.append(kept)
        }
    }
    out.Children = kids.toArray()
    return out
}

// One function declaration, with the parameters the body only reads borrowed. `this` is a receiver,
// which the emitter already passes as `T* self`, so it is never borrowed here.
fun bpBorrowDecl(
    decl: *AstXmlNode, types: *Dictionary<Str, Bool>, pure: *Dictionary<Str, Bool>
): AstXmlNode {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    if (params.size() == 0) {
        return decl
    }
    val body: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        return decl
    }
    var facts: BpFacts = BpFacts(
        false, false, pure, Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), Dictionary<Str, Bool>()
    )
    for (*stmt in body.Children) {
        facts.walk(stmt)
    }
    // A write through a field/index/deref, or a call the analysis cannot trust, borrows nothing:
    // both are the "unsure" that means escape.
    if (facts.fieldWrite || facts.impureCall) {
        return decl
    }
    var borrowed: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var p: Int = 0
    while (p < params.size()) {
        val param: *AstXmlNode = *params[p]
        p = p + 1
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        if (name == "this") {
            continue
        }
        if (facts.written.has(name) || facts.taken.has(name) || facts.inLambda.has(name)) {
            continue
        }
        val declared: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (bpHeavy(declared, types)) {
            borrowed.insert(name, true)
        }
    }
    if (borrowed.size() == 0) {
        return decl
    }
    var out: AstXmlNode = AstXmlNode(decl.name, decl.kind, decl.attributes, Array<AstXmlNode>())
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in decl.Children) {
        if (child.name == AstNodeKind.Param && borrowed.has(xmlAttr(child, AstNodeAttributeKind.Name))) {
            kids.append(bpBorrowParam(*child))
        } else {
            var kept: AstXmlNode = child
            kids.append(kept)
        }
    }
    out.Children = kids.toArray()
    return out
}

// One module rewritten: a data class rebuilt when a method borrows, a function rebuilt when it
// borrows, everything else kept as it is.
fun bpRewriteModule(
    module: *AstXmlNode, types: *Dictionary<Str, Bool>, pure: *Dictionary<Str, Bool>,
    counts: *Dictionary<Str, Int>, valueUsed: *Dictionary<Str, Bool>
): AstXmlNode {
    var changed: Bool = false
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in module.Children) {
        if (child.name == AstNodeKind.Function) {
            val name: Str = xmlAttr(child, AstNodeAttributeKind.Name)
            val count: *Int = counts.getPtr(name)
            if (count != null && * count == 1 && !valueUsed.has(name)) {
                kids.append(bpBorrowDecl(child, types, pure))
                changed = true
                continue
            }
        }
        if (child.name == AstNodeKind.DataClass) {
            var methods: List<AstXmlNode> = List<AstXmlNode>()
            var rebuilt: Bool = false
            for (*method in child.Children) {
                if (method.name == AstNodeKind.Function) {
                    val name: Str = xmlAttr(method, AstNodeAttributeKind.Name)
                    val count: *Int = counts.getPtr(name)
                    if (count != null && * count == 1 && !valueUsed.has(name)) {
                        methods.append(bpBorrowDecl(method, types, pure))
                        rebuilt = true
                        continue
                    }
                }
                var kept: AstXmlNode = method
                methods.append(kept)
            }
            if (rebuilt) {
                var cls: AstXmlNode = AstXmlNode(child.name, child.kind, child.attributes, Array<AstXmlNode>())
                cls.Children = methods.toArray()
                kids.append(cls)
                changed = true
                continue
            }
        }
        var kept: AstXmlNode = child
        kids.append(kept)
    }
    if (!changed) {
        return module
    }
    return AstXmlNode(module.name, module.kind, module.attributes, kids.toArray())
}

// The pass: the program modules, with every parameter a body only reads borrowed. The walk order is
// fixed (module order, then declaration order) and the decision is a pure function of the module
// text and the declaration sets, so two runs agree byte for byte.
fun bpBorrowParams(preludeModules: List<AstXmlNode>, modules: List<AstXmlNode>): List<AstXmlNode> {
    var counts: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var types: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var pure: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpCollectDecls(pre, *counts, *types, *pure)
    }
    for (*mod in modules) {
        bpCollectDecls(mod, *counts, *types, *pure)
    }

    var candidates: List<AstXmlNode> = List<AstXmlNode>()
    var candidateNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*mod in modules) {
        bpCandidates(mod, *counts, *candidates, *candidateNames)
    }
    if (candidates.size() == 0) {
        return modules
    }
    var valueUsed: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpGatherValueUses(pre, *candidateNames, *valueUsed)
    }
    for (*mod in modules) {
        bpGatherValueUses(mod, *candidateNames, *valueUsed)
    }

    // The prelude is never rewritten: its bodies are not emitted, and a declaration there has its
    // C++ written by hand.
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*mod in modules) {
        out.append(bpRewriteModule(mod, *types, *pure, *counts, *valueUsed))
    }
    return out
}
