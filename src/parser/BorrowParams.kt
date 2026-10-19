// BorrowParams.kt
//
// Auto-borrow (impl_specs/escape-analysis.md, the "auto-borrow" slice): a function parameter of a
// heavy value type becomes a `*T` when the function never writes it and never lets it escape, so
// every call site stops deep-copying the argument. The pass rewrites the *declaration* before sema
// runs, so the checker, the lowering and the emitter all see the borrowed program and nothing
// downstream knows the optimization exists - the same shape `cpFoldConstParams` has. The prelude's
// Simse bodies are rewritten too; a prelude name hand-written C++ may call keeps its authored
// signature (`bpCppCalled`), and a body-less (`@SmGen`) declaration is never a candidate.
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

// The third author's mark, beside `data` (pure) and `borrow` (read-only): a trailing
// `"mutates"` argument on the declaration's `@SmGen` - `@SmGen("res", "listops",
// "simse_str_append", "mutates")` - is the author's word that this callee's writes reach
// **only its receiver**. Such a callee cannot break a caller's borrow contract when the
// receiver at the call site is a local the body itself built (`parts.append(x)` writes
// `parts`, which no caller lent), so it does not block a body's proof the way an ordinary
// opaque call does. `data`/`borrow` cannot say this: both promise the receiver is not
// written at all, which is false for every mutator the RTL owns.
fun bpMutatesMark(decl: *AstXmlNode): Bool {
    val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    if (generator != "res" && generator != "cpp") {
        return false
    }
    val args: Str = xmlAttr(decl, AstNodeAttributeKind.GeneratorArgs)
    if (args.size() == 0) {
        return false
    }
    val parts: List<Str> = args.split(",")
    for (part in parts) {
        if (part.trim() == "mutates") {
            return true
        }
    }
    return false
}

// The same declaration walk the fixpoint needs, plus the sets the rule reads: the names a
// value may be borrowed *of* (a `data class`), the callees a body may trust to begin with
// (the `data`/`borrow` marks - the borrow-clean fixpoint grows this set), the callees whose
// writes reach only their receiver (the `mutates` generator flag), and the file-level
// `var`s (a write to one is not borrow-clean). `size`/`count` are `data` declarations like
// any other (src/rtl/rtl.kt), so no name is seeded by hand.
fun bpCollectDecls(
    module: *AstXmlNode, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    statics: *Dictionary<Str, Bool>, mutates: *Dictionary<Str, Bool>
): Unit {
    for (*decl in xmlDecls(module)) {
        val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
        if (decl.name == AstNodeKind.Var && xmlAttr(decl, AstNodeAttributeKind.IsVar) == "true") {
            statics.insert(name, true)
        }
        if (decl.name == AstNodeKind.DataClass) {
            types.insert(name, true)
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                val methodName: Str = xmlAttr(method, AstNodeAttributeKind.Name)
                if (bpMarked(method)) {
                    reads.insert(methodName, true)
                }
                if (bpIsSurfaceName(methodName)) {
                    bpSurfaceShadowed.insert(methodName, true)
                }
            }
        }
        if (decl.name == AstNodeKind.Function && bpMarked(decl)) {
            reads.insert(name, true)
        }
        if (decl.name == AstNodeKind.Function && bpIsSurfaceName(name)) {
            bpSurfaceShadowed.insert(name, true)
        }
        if (decl.name == AstNodeKind.Function && bpMutatesMark(decl)) {
            mutates.insert(name, true)
        }
    }
}

// The language's built-in type names (specs/built-in-types.md, specs/core-types.md): a call to
// one is a construction or a conversion, which copies what it is given.
fun bpBuiltinType(name: *Str): Bool {
    if ( * name == "Str" || * name == "List" || *name == "Dictionary" || *name == "Opt"
    || *name == "Res" || *name == "Array" || *name == "Span"
    ) {
        return true
    }
    return false
}

// ---- the Opt/Res member surface ----------------------------------------------

// The `Opt`/`Res` member calls (`o.hasValue()`, `r.value()`, ...) are declared under their
// collision-safe names (`simse_optHasValue`, ... - src/rtl/optres.kt), and the checker
// renames a call onto the twin only at checking time - after this pass. The fixpoint is
// name-keyed, so without help every function that reads an `Opt`/`Res` through the surface
// is borrow-dirty by association, which is most of them: `Res` is the language's error
// path. The answer is an alias, sound by construction: the surface name is trusted when
// *every* twin it could resolve to is borrow-clean (both `Opt`'s and `Res`'s - the pass
// does not know the receiver's type) and no declaration in the program shadows the name
// (a shadowing declaration's own proof governs, whichever way it went).
fun bpIsSurfaceName(name: *Str): Bool {
    when (name) {
        "hasValue", "isOk", "value", "error" -> {
            return true
        }
    }
    return false
}

// The twins a surface name can resolve to, every one of which must be borrow-clean for
// the surface call to be trusted.
fun bpSurfaceTwins(name: *Str, out: *List<Str>): Unit {
    when (name) {
        "hasValue", "isOk" -> {
            out.append("simse_optHasValue")
            out.append("simse_resHasValue")
        }

        "value" -> {
            out.append("simse_optValue")
            out.append("simse_resValue")
        }

        "error" -> {
            out.append("simse_resError")
        }
    }
}

// Filled by `bpCollectDecls`: the surface names this program declares for itself, which the
// alias above must not override.
var bpSurfaceShadowed: Dictionary<Str, Bool> = Dictionary<Str, Bool>()

// Whether a call named `callee` is borrow-trusted: proved under its own name, or a surface
// call whose twins all proved and that no declaration shadows.
fun bpCallTrusted(callee: *Str, reads: *Dictionary<Str, Bool>): Bool {
    if (reads.has(callee)) {
        return true
    }
    if (!bpIsSurfaceName(callee) || bpSurfaceShadowed.has(callee)) {
        return false
    }
    var twins: List<Str> = List<Str>()
    bpSurfaceTwins(callee, *twins)
    var i: Int = 0
    while (i < twins.size()) {
        if (!reads.has(twins[i])) {
            return false
        }
        i = i + 1
    }
    return twins.size() > 0
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
    var calls: List<Str>,

    // The locals the body declared with an initializer that borrows nothing: a `mutates`-
    // marked callee on one of these writes storage no caller lent, so it imposes no
    // read-only obligation and never joins `calls` (src/parser/BorrowParams.kt, the
    // `mutates` mark).
    var fresh: Dictionary<Str, Bool>
)

// The write/callee facts of one body, written in place through the pointer (a *field* of a pointer
// passed as an argument would be copied - `agents.md`, the `*this.sections` trap).
fun bpPureWalk(
    node: *AstXmlNode, out: *BpPure, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, mutates: *Dictionary<Str, Bool>
): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.StmtVarDecl) {
        // A local the body declares is fresh unless its initializer borrows a *place*
        // (`var p = &param`): then a mutating call on it may reach the parameter the
        // address came from, so it never counts as fresh.
        val declared: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (!statics.has(declared) && !bpContainsRef(xmlChildPtr(node, AstNodeKind.Init))) {
            out.fresh.insert(declared, true)
        }
    }
    if (kind == AstNodeCategory.StmtAssign) {
        val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
        if (xmlKind(target) != AstNodeCategory.ExprName) {
            out.hasWrite = true
        } else if (statics.has(xmlAttr(target, AstNodeAttributeKind.Name))) {
            // A file-level `var` (see the header): a caller may hold a pointer into it.
            out.hasWrite = true
        } else if (bpContainsRef(xmlChildPtr(node, AstNodeKind.Value))) {
            // The rebinding taints the local the same way a borrowing initializer would.
            out.fresh.remove(xmlAttr(target, AstNodeAttributeKind.Name))
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val ck: AstNodeCategory = xmlKind(callee)
        if (bpIsConstruct(callee, types)) {
            // A construction copies what it is given; see `bpIsConstruct`.
        } else if (bpMachineStep(callee)) {
            // The `for` machine's own step; see `bpMachineStep`.
        } else if (ck == AstNodeCategory.ExprMember && mutates.has(xmlAttr(callee, AstNodeAttributeKind.Name))) {
            // A `mutates`-marked callee writes only its receiver; on a fresh local that
            // storage is the body's own, so the call imposes no read-only obligation on
            // anybody (`bpMutatesMark`).
        } else if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember) {
            out.calls.append(xmlAttr(callee, AstNodeAttributeKind.Name))
        } else {
            out.opaqueCall = true
        }
    }
    for (*child in node.Children) {
        bpPureWalk(child, out, statics, types, mutates)
    }
}

// One declaration whose body the fixpoint may prove: it has a body, it is not native, and it is
// not already trusted by an author's mark (`data`/`borrow`). Every declaration of a name has to
// prove clean before the *name* is trusted (`bpInferReads`), which is what lets an overloaded
// name be proved - the name is not attributed to one signature, it is a conjunction over all of
// them.
fun bpPurConsider(
    fn: *AstXmlNode, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, mutates: *Dictionary<Str, Bool>, out: *List<BpPure>
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
    var fact: BpPure = BpPure(name, false, false, List<Str>(), Dictionary<Str, Bool>())
    for (*stmt in body.Children) {
        bpPureWalk(stmt, *fact, statics, types, mutates)
    }
    out.append(fact)
}

fun bpPurCollect(
    module: *AstXmlNode, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, mutates: *Dictionary<Str, Bool>, out: *List<BpPure>
): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            bpPurConsider(decl, statics, types, mutates, out)
        } else if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpPurConsider(method, statics, types, mutates, out)
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
                if (!bpCallTrusted(callee, reads)) {
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
    if (bpShow()) {
        for (*fact in facts) {
            if (!reads.has(fact.name)) {
                var why: Str = ""
                for (*callee in fact.calls) {
                    if (!bpCallTrusted(callee, reads)) {
                        val calleeName: Str = callee
                        why = why + " " + calleeName
                    }
                }
                if (fact.hasWrite) {
                    why = why + " <write>"
                }
                if (fact.opaqueCall) {
                    why = why + " <opaque>"
                }
                val dirtyName: Str = fact.name
                bpNote(`borrow-dirty @dirtyName:@why`)
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

// Whether the expression borrows or reads through a *place* anywhere under it: the shape that
// makes an assignment's target a suspect local (a mutating call on it may reach the parameter
// the address came from). An over-approximation is the sound direction.
fun bpContainsRef(node: *AstXmlNode): Bool {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
        return true
    }
    for (childNode in node.Children) {
        if (bpContainsRef(childNode)) {
            return true
        }
    }
    return false
}

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
    var mutates: *Dictionary<Str, Bool>,
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
        if (kind == AstNodeCategory.StmtVarDecl) {
            // A declared local is one the body built - even with no initializer, which
            // cannot alias anything at all. The mutator relaxation below reads this set,
            // and so does the parameter rule (`written` parameters are never borrowed,
            // since the body rebinds them).
            val declared: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            if (!this.statics.has(declared)) {
                this.written.insert(declared, true)
                if (bpContainsRef(xmlChildPtr(node, AstNodeKind.Init))) {
                    // Initialized from a borrowed *place* (`var p = &param`): a mutating
                    // call on it may reach the parameter the address came from.
                    this.taken.insert(declared, true)
                }
            }
        }
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
                    if (bpContainsRef(xmlChildPtr(node, AstNodeKind.Value))) {
                        // The local is initialized or reassigned from a borrowed *place*
                        // (`var p = &param`), so a mutating call on it may reach the
                        // parameter the address came from: the local never counts as fresh.
                        this.taken.insert(targetName, true)
                    }
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
                val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                safe = this.reads.has(calleeName) || bpCallTrusted(calleeName, this.reads)
            }
            if (!safe && bpMachineStep(callee)) {
                // The `for` machine's own step; see `bpMachineStep`.
                safe = true
            }
            if (!safe && ck == AstNodeCategory.ExprMember) {
                // A `mutates`-marked callee writes only its receiver, so on a local this
                // body built - and that no borrowed address flows into - the call cannot
                // reach a parameter (`bpMutatesMark`).
                val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                if (this.mutates.has(calleeName)) {
                    val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
                    if (xmlKind(receiver) == AstNodeCategory.ExprName) {
                        val receiverName: Str = xmlAttr(receiver, AstNodeAttributeKind.Name)
                        if (this.written.has(receiverName) && !this.taken.has(receiverName)) {
                            safe = true
                        }
                    }
                }
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
// program's lowering never writes `_sm_`), and a machine can only come from `iterValues`/`iter`
// in the same body - so the call that built it has already been weighed, and `advance` reads the
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

// The data classes whose *copy* is deep: one with a `Str` field, or a field of another such
// class. A `List`/`Array`/`&T` field is a refcount, not a copy, so it does not count. This gates
// the `for` promotion (`bpDeepElement`): a deep element pays for a copy - and, for a `Str`, an
// allocation - on every iteration of the value form.
//
// The set below is the analysis's result, kept for the parts of the compiler that run after the
// pass: sema's `for` promotion reads it (`bpDeepElement`, `bpLoopReadOnly`).
var bpDeepTypes: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
var bpTrustedNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
var bpStaticNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
var bpKnownTypes: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
var bpMutatesNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()

fun bpDeepClasses(modules: *List<AstXmlNode>): Dictionary<Str, Bool> {
    // The declarations by name, so a class's chain of bases can be walked: inheritance is
    // field appending, and a field a base carries makes the derived class deep too
    // (specs/declarations.md, "Inheritance").
    var declsByName: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    for (*module in modules) {
        for (*decl in xmlDecls(module)) {
            if (decl.name == AstNodeKind.DataClass) {
                declsByName.insert(xmlAttr(decl, AstNodeAttributeKind.Name), *decl)
            }
        }
    }
    var deep: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var changed: Bool = true
    while (changed) {
        changed = false
        for (*module in modules) {
            for (*decl in xmlDecls(module)) {
                if (decl.name != AstNodeKind.DataClass) {
                    continue
                }
                val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
                if (deep.has(name)) {
                    continue
                }
                if (bpClassDeep(*decl, *declsByName, *deep)) {
                    deep.insert(name, true)
                    changed = true
                }
            }
        }
    }
    return deep
}

// Whether any field on the class's chain - its own or a base's - is `Str` or a deep class.
// A base is looked up by name; a link the tables do not hold ends the walk.
fun bpClassDeep(
    decl: *AstXmlNode, declsByName: *Dictionary<Str, AstXmlNode>, deep: *Dictionary<Str, Bool>
): Bool {
    var current: *AstXmlNode = decl
    var guard: Int = 0
    while (current != null && guard < 64) {
        guard++
        for (*field in xmlChildren(*current, AstNodeKind.Field)) {
            val fieldType: *AstXmlNode = xmlChildPtr(field, AstNodeKind.Type)
            if (xmlKind(fieldType) != AstNodeCategory.TypeNamed) {
                continue
            }
            val fieldName: Str = xmlAttr(fieldType, AstNodeAttributeKind.Name)
            if (fieldName == "Str" || deep.has(fieldName)) {
                return true
            }
        }
        val baseName: Str = xmlAttr(*current, AstNodeAttributeKind.Base)
        if (baseName == "") {
            break
        }
        val base: *AstXmlNode = declsByName.getPtr(baseName)
        if (base == null) {
            break
        }
        current = base
    }
    return false
}

// Whether a `for` variable of this element type is worth the pointer form: the copy is deep
// (`Str`, or a data class holding one), so the value form copies and allocates per element. A
// scalar, a handle, a container and a class of those are left as written - `impl_specs/for.md`
// measured the value form as fast or faster for them.
fun bpDeepElement(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode) || xmlKind(typeNode) != AstNodeCategory.TypeNamed) {
        return false
    }
    val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    if (name == "Str") {
        return true
    }
    return bpDeepTypes.has(name)
}

// Whether `body` only reads `name`: no write through it, no `&`/`*`, no capture, and no call the
// fixpoint cannot trust - so the element's place is stable for the whole loop. The same rule the
// parameter borrow uses, so a `for` promotion and a parameter borrow agree.
fun bpLoopReadOnly(body: *List<AstXmlNode>, name: *Str): Bool {
    var facts: BpFacts = BpFacts(
        false, false, false, *bpStaticNames, *bpTrustedNames, *bpKnownTypes, *bpMutatesNames,
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), List<Str>()
    )
    for (*stmt in body) {
        // The template's own binding (`val <name> = <machine>.current`, the `for`'s first
        // statement) is the declaration the promotion exists to retype - a `*T` place
        // instead of a copy - so it is not a write that disqualifies the walk. Every other
        // write to the name (a reassignment in the body) still is.
        val declName: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
        if (declName == name && xmlKind(stmt) == AstNodeCategory.StmtVarDecl) {
            continue
        }
        facts.walk(stmt)
    }
    if (facts.fieldWrite || facts.staticWrite || facts.impureCall) {
        return false
    }
    if (facts.written.has(*name) || facts.taken.has(*name) || facts.inLambda.has(*name)) {
        return false
    }
    return true
}

// The names hand-written C++ may call: `<name>` followed by `(` anywhere in a resource body or
// a prelude header. The scan is coarse on purpose - a member call on another type or a
// declaration counts too - because a name it misses would be rewritten under C++ that is
// compiled against the exact signature. A false positive only costs a parameter that stays by
// value.
fun bpCppCalled(texts: *List<Str>): Dictionary<Str, Bool> {
    var out: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*text in texts) {
        val len: Int = text.size()
        var i: Int = 0
        while (i < len) {
            val ch: Char = text[i]
            if (ch.isAlpha() || ch == '_') {
                val start: Int = i
                while (i < len) {
                    val part: Char = text[i]
                    if (!part.isAlphaOrDigit() && part != '_') {
                        break
                    }
                    i++
                }
                var after: Int = i
                while (after < len) {
                    val gap: Char = text[after]
                    if (gap != ' ' && gap != '\t') {
                        break
                    }
                    after++
                }
                if (after < len && text[after] == '(') {
                    out.insert(text.substr(start, i - start), true)
                }
            } else {
                i++
            }
        }
    }
    return out
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
// which the emitter already passes as `T* self`, so it is never borrowed here. A prelude name
// hand-written C++ may call keeps its exact signature (`bpCppCalled`): the resource bodies and
// the headers are compiled against it, so the proof is a refusal there, not a rewrite.
fun bpBorrowDecl(
    decl: *AstXmlNode, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    statics: *Dictionary<Str, Bool>, cppCalled: *Dictionary<Str, Bool>, prelude: Bool
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
        false, false, false, statics, reads, types, *bpMutatesNames, Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), List<Str>()
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
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (prelude && cppCalled.has(name)) {
        if (bpShow()) {
            bpNote(`borrow- @name C++ calls it (prelude)`)
        }
        return decl
    }
    if (bpShow()) {
        val bpNamesText: Str = bpNames(*borrowed)
        bpNote(`borrow+ @name @bpNamesText`)
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
    statics: *Dictionary<Str, Bool>, valueUsed: *Dictionary<Str, Bool>,
    cppCalled: *Dictionary<Str, Bool>, prelude: Bool
): AstXmlNode {
    var changed: Bool = false
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    for (*child in module.Children) {
        if (child.name == AstNodeKind.Function) {
            val name: Str = xmlAttr(child, AstNodeAttributeKind.Name)
            if (reads.has(name) && !valueUsed.has(name)) {
                kids.append(bpBorrowDecl(child, types, reads, statics, cppCalled, prelude))
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
                        methods.append(bpBorrowDecl(method, types, reads, statics, cppCalled, prelude))
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

// What the pass produced: the prelude and the modules, both rewritten. The prelude comes back
// because its Simse bodies borrow like a module's; only a name hand-written C++ may call keeps
// its authored signature (`bpCppCalled`).
data class BpRewrite(
    var prelude: List<AstXmlNode>,
    var modules: List<AstXmlNode>
)

// The pass: the prelude and the program modules, with every parameter a body only reads borrowed.
// The walk order is fixed (module order, then declaration order) and the decision is a pure
// function of the module text and the declaration sets, so two runs agree byte for byte.
fun bpBorrowParams(
    preludeModules: List<AstXmlNode>, modules: List<AstXmlNode>, cppTexts: *List<Str>
): BpRewrite {
    var types: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var reads: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var statics: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var mutates: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpCollectDecls(pre, *types, *reads, *statics, *mutates)
    }
    for (*mod in modules) {
        bpCollectDecls(mod, *types, *reads, *statics, *mutates)
    }

    var candidates: List<AstXmlNode> = List<AstXmlNode>()
    var candidateNames: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpCandidates(pre, *candidates, *candidateNames)
    }
    for (*mod in modules) {
        bpCandidates(mod, *candidates, *candidateNames)
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
        bpPurCollect(pre, *statics, *types, *mutates, *factList)
    }
    for (*mod in modules) {
        bpPurCollect(mod, *statics, *types, *mutates, *factList)
    }
    var blocked: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpBlockedNames(pre, *reads, *blocked)
    }
    for (*mod in modules) {
        bpBlockedNames(mod, *reads, *blocked)
    }
    bpInferReads(*factList, *blocked, *reads)

    // The analysis's results, kept for sema's `for` promotion (the caches above): the trusted
    // names and file-level vars weigh a loop body, and the deep classes gate the element type.
    var allModules: List<AstXmlNode> = List<AstXmlNode>()
    for (*pre in preludeModules) {
        allModules.append(pre)
    }
    for (*mod in modules) {
        allModules.append(mod)
    }
    bpDeepTypes = bpDeepClasses(*allModules)
    bpTrustedNames = reads
    bpStaticNames = statics
    bpKnownTypes = types
    bpMutatesNames = mutates

    // Both sides are rewritten: a Simse-bodied prelude declaration borrows exactly like a module
    // one, and only a name C++ may call keeps its authored signature (inside `bpBorrowDecl`).
    var cppCalled: Dictionary<Str, Bool> = bpCppCalled(cppTexts)
    var outPrelude: List<AstXmlNode> = List<AstXmlNode>()
    for (*pre in preludeModules) {
        outPrelude.append(bpRewriteModule(pre, *types, *reads, *statics, *valueUsed, *cppCalled, true))
    }
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*mod in modules) {
        out.append(bpRewriteModule(mod, *types, *reads, *statics, *valueUsed, *cppCalled, false))
    }
    return BpRewrite(outPrelude, out)
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
