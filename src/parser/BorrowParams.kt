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
//   - a call is trusted only when the callee is *borrow-clean*: it writes nothing observable, so
//     it cannot write through a parameter it is handed. `data` is that promise made by an author
//     (the one source of truth for a body-less declaration); a declaration with a body *proves*
//     the same fact by the fixpoint below. A construction (`Point(1, 2)`) is not a call on the
//     parameter;
//   - any assignment whose target is not a plain name (`x.f = ...`, `x[i] = ...`, `*x = ...`), or
//     is a file-level `var`, borrows nothing, anywhere in the body. It covers a write through an
//     alias, and a file-level write is what could invalidate a pointer a *caller* holds into that
//     global;
//   - a parameter under `&`/`*`, assigned, or captured by a lambda is not borrowed: a capture
//     would copy the *pointer* into the closure, which is exactly an escape.
//
// Everything else a parameter can do is a *read* (`p.f`, `p[i]`, `p.size()`, `return p`, which
// copies). That is the shape the author named - "string->size() and return" - and few bodies
// qualify, which is the point: the proof is "this body only reads".
//
// *borrowness* (this pass) says a callee cannot write through a parameter, so a caller may hand it
// a pointer; it says nothing about the result, so `Str.size` and a helper returning a fresh `Str`
// or machine are both borrow-clean. *Purity* (`data`, `Codegen.kt`'s `pureCallees`,
// `linear/ReusePure.kt`) says the result is a function of the argument, so two calls fold - a
// stronger fact this pass does not need.
//
// The fixpoint is computed *below* and grows from below (see `bpInferReads`): it does not look
// through a call, does not measure type sizes, and handles only a type it can name as heavy
// (`Str`, a `data class`, a container) - never a scalar, a handle or `Span`/`Array`.

package parser
import compiler

import common

// ---- the sets the rule reads ------------------------------------------------

// A declaration that carries a borrowness mark: `data` (pure, which implies it) or `borrow`
// (read-only receiver and parameters, weaker than `data`). A mark is an author's word, needed
// for a body-less declaration whose C++ is elsewhere; a declaration with a body is *proved*
// instead (`bpInferReads`).
fun bpMarked(decl: *AstXmlNode): Bool {
    if (xmlAttr(decl, AstNodeAttributeKind.IsPure) == "true") {
        return true
    }
    return xmlAttr(decl, AstNodeAttributeKind.IsBorrow) == "true"
}

// The same declaration walk the fixpoint needs, plus the sets the rule reads: the names a
// value may be borrowed *of* (a `data class`), the callees a body may trust to begin with
// (the `data`/`borrow` marks - the borrow-clean fixpoint grows this set), and the file-level
// `var`s (a write to one is not borrow-clean). `size`/`count` are `data` declarations like
// any other (src/rtl/rtl.kt), so no name is seeded by hand.
fun bpCollectDecls(
    module: *AstXmlNode, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    statics: *Dictionary<Str, Bool>
): Unit {
    for (*decl in xmlDecls(module)) {
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        if (decl.name == AstNodeKind.Var && xmlAttr(decl, AstNodeAttributeKind.IsVar) == "true") {
            statics.insert(name, true)
        }
        if (decl.name == AstNodeKind.DataClass) {
            types.insert(name, true)
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                if (bpMarked(method)) {
                    reads.insert(xmlAttr(method, AstNodeAttributeKind.Name), true)
                }
            }
        }
        if (decl.name == AstNodeKind.Function && bpMarked(decl)) {
            reads.insert(name, true)
        }
    }
}

// The language's built-in type names (specs/built-in-types.md, specs/core-types.md): a call to
// one is a construction or a conversion, which copies what it is given.
fun bpBuiltinType(name: *Str): Bool {
    if ( * name == "Str" || * name == "List" || *name == "Dictionary" || *name == "Opt"
    || *name == "Res" || *name == "Array" || *name == "Span" || *name == "FileStream"
    ) {
        return true
    }
    return false
}

// A construction: `Name(args)` where `Name` is a type, `Opt<T>.none()` / `Res<T>.ok(x)`, or any
// `Name<T>(args)`. Its arguments are *copied* into the fields (a data class has no constructor
// body) and the built-in conversions copy too, so none of them is a call on a parameter - which
// is what makes a body that builds a value borrowable.
fun bpIsConstruct(callee: *AstXmlNode, types: *Dictionary<Str, Bool>): Bool {
    val ck: AstNodeCategory = xmlKind(callee)
    if (ck == AstNodeCategory.ExprGenericName) {
        return true
    }
    if (ck == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        return types.has(name) || bpBuiltinType(*name)
    }
    if (ck != AstNodeCategory.ExprMember) {
        return false
    }
    val member: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
    if (member != "none" && member != "some" && member != "ok" && member != "err") {
        return false
    }
    val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    val rk: AstNodeCategory = xmlKind(recv)
    if (rk != AstNodeCategory.ExprName && rk != AstNodeCategory.ExprGenericName) {
        return false
    }
    return bpBuiltinType(*xmlAttr(recv, AstNodeAttributeKind.Name))
}

// One declaration's body, reduced to what the borrow-clean fixpoint reads: whether it writes
// anything observable (a field/index/deref store, or a file-level `var`), whether a call has no
// name at all to check (a lambda value called through), and the names of the callees it calls.
data class BpPure(
    var name: Str,
    var hasWrite: Bool,
    var opaqueCall: Bool,
    var calls: List<Str>
)

// The write/callee facts of one body, written in place through the pointer (a *field* of a pointer
// passed as an argument would be copied - `agents.md`, the `*this.sections` trap).
fun bpPureWalk(
    node: *AstXmlNode, out: *BpPure, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>
): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.StmtAssign) {
        val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
        if (xmlKind(target) != AstNodeCategory.ExprName) {
            out.hasWrite = true
        } else if (statics.has(xmlAttr(target, AstNodeAttributeKind.Name))) {
            // A file-level `var` (see the header): a caller may hold a pointer into it.
            out.hasWrite = true
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val ck: AstNodeCategory = xmlKind(callee)
        if (bpIsConstruct(callee, types)) {
            // A construction copies what it is given; see `bpIsConstruct`.
        } else if (bpMachineStep(callee)) {
            // The `for` machine's own step; see `bpMachineStep`.
        } else if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember) {
            out.calls.append(xmlAttr(callee, AstNodeAttributeKind.Name))
        } else {
            out.opaqueCall = true
        }
    }
    for (*child in node.Children) {
        bpPureWalk(child, out, statics, types)
    }
}

// One declaration whose body the fixpoint may prove: it has a body, it is not native, and it is
// not already trusted by an author's mark (`data`/`borrow`). Every declaration of a name has to
// prove clean before the *name* is trusted (`bpInferReads`), which is what lets an overloaded
// name be proved - the name is not attributed to one signature, it is a conjunction over all of
// them.
fun bpPurConsider(
    fn: *AstXmlNode, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, out: *List<BpPure>
): Unit {
    if (xmlAttr(fn, AstNodeAttributeKind.HasBody) != "true"
        || xmlAttr(fn, AstNodeAttributeKind.IsNative) == "true"
        || bpMarked(fn)
    ) {
        return
    }
    val name: Str = xmlAttr(fn, AstNodeAttributeKind.Name)
    val body: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        return
    }
    var fact: BpPure = BpPure(name, false, false, List<Str>())
    for (*stmt in body.Children) {
        bpPureWalk(stmt, *fact, statics, types)
    }
    out.append(fact)
}

fun bpPurCollect(
    module: *AstXmlNode, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, out: *List<BpPure>
): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            bpPurConsider(decl, statics, types, out)
        } else if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpPurConsider(method, statics, types, out)
            }
        }
    }
}

// The *borrowness* fixpoint (impl_specs/escape-analysis.md): a name is borrow-clean when every
// declaration of it that this pass can read has a body that writes nothing observable and calls
// only borrow-clean names, and every declaration it cannot read (a native, or C++ named by an
// attribute) is marked by the author (`blocked`). It grows from *below* - a name joins only
// once every call its bodies make is to a name already in the set - so a recursive cycle is
// never assumed clean and no name is ever added on a guess. The result is the least fixpoint,
// the sound direction: a name left out is simply not trusted, and the emitted code is
// unchanged for it.
fun bpInferReads(
    facts: *List<BpPure>, blocked: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>
): Unit {
    var changed: Bool = true
    while (changed) {
        changed = false
        // A name is dirty when *any* of its declarations writes, makes an opaque call or
        // calls a name not trusted yet. Insert only after the whole list is weighed, so two
        // overloads cannot let each other in on half a proof.
        var dirty: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        for (*fact in facts) {
            if (fact.hasWrite || fact.opaqueCall) {
                dirty.insert(fact.name, true)
                continue
            }
            for (*callee in fact.calls) {
                if (!reads.has(*callee)) {
                    dirty.insert(fact.name, true)
                    break
                }
            }
        }
        for (*fact in facts) {
            if (!reads.has(fact.name) && !dirty.has(fact.name) && !blocked.has(fact.name)) {
                reads.insert(fact.name, true)
                changed = true
            }
        }
    }
}

// A declaration the fixpoint cannot read: no body, or a native. A call by its name may reach
// it, so unless the author's mark already trusts the name, it blocks the proof - the mark is
// the author's word for C++ (`BorrowParams.kt`'s header).
fun bpBlockedOne(
    fn: *AstXmlNode, reads: *Dictionary<Str, Bool>, blocked: *Dictionary<Str, Bool>
): Unit {
    val name: Str = xmlAttr(fn, AstNodeAttributeKind.Name)
    if (reads.has(name)) {
        return
    }
    if (xmlAttr(fn, AstNodeAttributeKind.HasBody) == "true"
        && xmlAttr(fn, AstNodeAttributeKind.IsNative) != "true"
    ) {
        return
    }
    blocked.insert(name, true)
}

fun bpBlockedNames(
    module: *AstXmlNode, reads: *Dictionary<Str, Bool>, blocked: *Dictionary<Str, Bool>
): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            bpBlockedOne(decl, reads, blocked)
        } else if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpBlockedOne(method, reads, blocked)
            }
        }
    }
}

// One declaration considered: a function with a body, not native, not `main` (the runtime calls
// it). Every declaration of a name is a candidate - an overloaded name is trusted only when
// all of its bodies prove clean (`bpInferReads`), so no declaration needs an author's mark for
// the *name* to be usable.
fun bpConsider(
    fn: *AstXmlNode, out: *List<AstXmlNode>, names: *Dictionary<Str, Bool>
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
    out.append(fn)
    names.insert(name, true)
}

// The program's candidates, in module then declaration order: every top-level function, and every
// method of a data class.
fun bpCandidates(
    module: *AstXmlNode, out: *List<AstXmlNode>, names: *Dictionary<Str, Bool>
): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            bpConsider(decl, out, names)
        } else if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpConsider(method, out, names)
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
// materializes a copy (`agents.md`, the `*this.sections` trap), so the set must be reached
// through `this`. `reads` is the borrowness flag (the fixpoint above); `statics` is the set of
// file-level `var` names, a write to one of which is a write the rule must see.
data class BpFacts(
    var fieldWrite: Bool,
    var staticWrite: Bool,
    var impureCall: Bool,
    var statics: *Dictionary<Str, Bool>,
    var reads: *Dictionary<Str, Bool>,
    var types: *Dictionary<Str, Bool>,
    var taken: Dictionary<Str, Bool>,
    var written: Dictionary<Str, Bool>,
    var inLambda: Dictionary<Str, Bool>,

    // The callee names that made a call untrusted, for `--showBorrow`. The decision itself
    // reads only `impureCall`.
    var blocked: List<Str>
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
                val targetName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
                if (this.statics.has(targetName)) {
                    // A file-level `var` (see the header).
                    this.staticWrite = true
                } else {
                    this.written.insert(targetName, true)
                }
            }
        }
        if (kind == AstNodeCategory.ExprCall) {
            val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
            val ck: AstNodeCategory = xmlKind(callee)
            var safe: Bool = false
            if (bpIsConstruct(callee, this.types)) {
                // A construction copies what it is given; see `bpIsConstruct`.
                safe = true
            } else if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember) {
                safe = this.reads.has(xmlAttr(callee, AstNodeAttributeKind.Name))
            }
            if (!safe && bpMachineStep(callee)) {
                // The `for` machine's own step; see `bpMachineStep`.
                safe = true
            }
            if (!safe) {
                this.impureCall = true
                if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember) {
                    this.blocked.append(xmlAttr(callee, AstNodeAttributeKind.Name))
                } else {
                    this.blocked.append("(a value call)")
                }
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

// The `for` lowering's machine step (impl_specs/for.md): the desugar declares a local named
// `_sm_for<n>` and steps it with `_sm_for<n>.advance()`. The prefix is the compiler's own (a
// program's lowering never writes `_sm_`), and a machine can only come from `iter`/`iterPtr` in
// the same body - so the call that built it has already been weighed, and `advance` reads the
// container it was built over. Trusting the step is what lets a body with a `for` borrow.
fun bpMachineStep(callee: *AstXmlNode): Bool {
    if (xmlKind(callee) != AstNodeCategory.ExprMember) {
        return false
    }
    if (xmlAttr(callee, AstNodeAttributeKind.Name) != "advance") {
        return false
    }
    val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
    if (xmlKind(recv) != AstNodeCategory.ExprName) {
        return false
    }
    return xmlAttr(recv, AstNodeAttributeKind.Name).startsWith("_sm_for")
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

// Why a candidate was refused, for `--showBorrow`: the first write the rule saw, or the callee
// names that made a call untrusted.
fun bpWhy(facts: *BpFacts): Str {
    if (facts.fieldWrite) {
        return "writes a field, an index or a pointer"
    }
    if (facts.staticWrite) {
        return "writes a file-level var"
    }
    return "calls " + joinStrs(facts.blocked, ", ")
}

// The names of a set, comma-separated in insertion order (a small set; determinism is the point).
fun bpNames(names: *Dictionary<Str, Bool>): Str {
    // Bound first: a `for` over a temporary would borrow a pointer into it (`agents.md`).
    val keys: List<Str> = names.keys()
    return joinStrs(keys, ", ")
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
    decl: *AstXmlNode, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    statics: *Dictionary<Str, Bool>
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
        false, false, false, statics, reads, types, Dictionary<Str, Bool>(), Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(), List<Str>()
    )
    for (*stmt in body.Children) {
        facts.walk(stmt)
    }
    // A write through a field/index/deref or a file-level `var`, or a call the analysis cannot
    // trust, borrows nothing: each is the "unsure" that means escape.
    if (facts.fieldWrite || facts.staticWrite || facts.impureCall) {
        if (bpShow()) {
            val xmlAttrText: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
            val bpWhyText: Str = bpWhy(*facts)
            bpNote(`borrow- @xmlAttrText @bpWhyText`)
        }
        return decl
    }
    var borrowed: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*param in params) {
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
    if (bpShow()) {
        val xmlAttrText2: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        val bpNamesText: Str = bpNames(*borrowed)
        bpNote(`borrow+ @xmlAttrText2 @bpNamesText`)
    }
    // `--no-borrow`: the analysis above still runs (so `--showBorrow` reads it), the rewrite does
    // not - the emitted C++ is what the author wrote.
    if (bpNoBorrow()) {
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
// borrows, everything else kept as it is. Only a name the fixpoint trusted is touched: a
// declaration whose name has a twin the pass could not read (a bodyless prototype beside the
// body a generator supplied) must keep the signature the twin's callers resolve against.
fun bpRewriteModule(
    module: *AstXmlNode, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    statics: *Dictionary<Str, Bool>, valueUsed: *Dictionary<Str, Bool>
): AstXmlNode {
    var changed: Bool = false
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in module.Children) {
        if (child.name == AstNodeKind.Function) {
            val name: Str = xmlAttr(child, AstNodeAttributeKind.Name)
            if (reads.has(name) && !valueUsed.has(name)) {
                kids.append(bpBorrowDecl(child, types, reads, statics))
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
                    if (reads.has(name) && !valueUsed.has(name)) {
                        methods.append(bpBorrowDecl(method, types, reads, statics))
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
    var types: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var reads: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var statics: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpCollectDecls(pre, *types, *reads, *statics)
    }
    for (*mod in modules) {
        bpCollectDecls(mod, *types, *reads, *statics)
    }

    var candidates: List<AstXmlNode> = List<AstXmlNode>()
    var candidateNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*mod in modules) {
        bpCandidates(mod, *candidates, *candidateNames)
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

    // Grows `reads` from the marks to every borrow-clean name (see `bpInferReads`): all the
    // bodies a name has must prove, and any declaration without a body must be marked.
    var factList: List<BpPure> = List<BpPure>()
    for (*pre in preludeModules) {
        bpPurCollect(pre, *statics, *types, *factList)
    }
    for (*mod in modules) {
        bpPurCollect(mod, *statics, *types, *factList)
    }
    var blocked: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpBlockedNames(pre, *reads, *blocked)
    }
    for (*mod in modules) {
        bpBlockedNames(mod, *reads, *blocked)
    }
    bpInferReads(*factList, *blocked, *reads)

    // The prelude is never rewritten: a declaration there has its C++ written by hand (and
    // hand-written C++ may call it by its exact signature).
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*mod in modules) {
        out.append(bpRewriteModule(mod, *types, *reads, *statics, *valueUsed))
    }
    return out
}

// ---- the switches -----------------------------------------------------------

// `--no-borrow`: auto-borrow off. The analysis still runs (so `--showBorrow` can read it); the
// declaration rewrite is skipped, and the emitted C++ is what the author wrote. The escape
// hatch for the day a borrow is wrong.
var bpNoBorrowFlag: Bool = false

fun bpNoBorrow(): Bool {
    return bpNoBorrowFlag
}

fun bpSetNoBorrow(value: Bool): Unit {
    bpNoBorrowFlag = value
}

// `--showBorrow`: the decision for every candidate. The lines are collected here and printed by
// the driver (the pass keeps no stderr of its own): `borrow+ <name> <params>` when a declaration
// borrows, `borrow- <name> <why>` when it refuses. An empty report unless the flag is set.
var bpShowFlag: Bool = false
var bpReport: List<Str> = List<Str>()

fun bpShow(): Bool {
    return bpShowFlag
}

fun bpSetShow(value: Bool): Unit {
    bpShowFlag = value
}

fun bpNote(line: *Str): Unit {
    bpReport.append(*line)
}

fun bpReportLines(): List<Str> {
    return bpReport
}
