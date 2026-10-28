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
//   - a call is trusted only when the callee is *borrow-clean* - it writes nothing
//     observable, so it cannot write through a parameter it is handed - or when it carries
//     a *write signature*: the parameter positions (a method's receiver at 0) its writes
//     reach, and every one of those positions takes an argument the caller built. `data`
//     is the clean promise made by an author (the one source of truth for a body-less
//     declaration); `mutates` (and its positional `mutates:0,2` spelling) is the
//     signature made by an author; a declaration with a body *proves* either fact by the
//     fixpoint below. A construction (`Point(1, 2)`) is not a call on the parameter;
//   - an assignment whose target is not a plain name (`x.f = ...`, `x[i] = ...`,
//     `*x = ...`) attributes the write to the place the target chain roots at - a
//     parameter (or the receiver) gives the declaration a signature position, a local the
//     body built costs nothing, and anything else (a file-level `var`, an alias the
//     classifier cannot attribute) refuses the proof. A file-level write is what could
//     invalidate a pointer a *caller* holds into that global;
//   - a parameter under `&`/`*`, assigned, or captured by a lambda is not borrowed: a
//     capture would copy the *pointer* into the closure, which is exactly an escape.
//
// Everything else a parameter can do is a *read* (`p.f`, `p[i]`, `p.size()`, `return p`, which
// copies). That is the shape the author named - "string->size() and return" - and few bodies
// qualify, which is the point: the proof is "this body only reads". The signature is the same
// proof one step wider - "this body only reads its *other* parameters" - so a builder that
// writes its receiver or one handed-in destination still borrows the rest (`xmlAddChild`
// writes parameter 0, and every caller that builds its node fresh borrows the rest of what it
// holds).
//
// *borrowness* (this pass) says a callee cannot write through a parameter, so a caller may hand it
// a pointer; it says nothing about the result, so `Str.size` and a helper returning a fresh `Str`
// or machine are both borrow-clean. *Purity* (`data`, `Codegen.kt`'s `pureCallees`,
// `linear/ReusePure.kt`) says the result is a function of the argument, so two calls fold - a
// stronger fact this pass does not need.
//
// The fixpoint is computed *below* and grows from below (see `bpInferWrites`): it does not look
// through a call, does not measure type sizes, and handles only a type it can name as heavy
// (`Str`, a `data class`, a container) - never a scalar, a handle or `Span`/`Array`.

package parser
import compiler

import common

// ---- the sets the rule reads ------------------------------------------------

// A declaration that carries a borrowness mark: `data` (pure, which implies it) or `borrow`
// (read-only receiver and parameters, weaker than `data`). A mark is an author's word, needed
// for a body-less declaration whose C++ is elsewhere; a declaration with a body is *proved*
// instead (`bpInferWrites`).
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

// The written *parameter positions* of a positional mark: `mutates:0` (or `mutates:0,2`)
// in the `@SmGen` arguments names the 0-based parameters the callee writes - the receiver
// is not among them (the receiver-only mark is plain `mutates`, above). "" when the
// declaration carries no positional mark. The reason it exists: `memCopy` writes through
// its first parameter, a shape the receiver-only relaxation cannot see, and bodies that
// build into a fresh local through such primitives (`StrView.plus`'s `resize` + `memCopy`
// tail) were borrow-dirty through them.
fun bpMutatesPositions(decl: *AstXmlNode): Str {
    val generator: Str = xmlAttr(decl, AstNodeAttributeKind.Generator)
    if (generator != "res" && generator != "cpp") {
        return ""
    }
    val args: Str = xmlAttr(decl, AstNodeAttributeKind.GeneratorArgs)
    if (args.size() == 0) {
        return ""
    }
    val parts: List<Str> = args.split(",")
    for (part in parts) {
        val trimmed: Str = part.trim()
        if (trimmed.startsWith("mutates:")) {
            return trimmed.substr(8, trimmed.size() - 8)
        }
    }
    return ""
}

// The `at`-th `Arg` child of a call, as a pointer (no node copies on the walk's hot
// path); null when the call has no argument there.
fun bpArgAt(call: *AstXmlNode, at: Int): *AstXmlNode {
    if (at < 0) {
        return null
    }
    var i: Int = 0
    for (*child in call.Children) {
        if (child.name == AstNodeKind.Arg) {
            if (i == at) {
                return child
            }
            i++
        }
    }
    return null
}

// Merge `positions` (comma text) into the author-signature entry for `name`: the marks are
// signatures too, seeded into the table beside the fixpoint's own proofs, so a call site
// consults one table whatever its callee's declaration looks like.
fun bpSigAdd(sigs: *Dictionary<Str, Str>, name: Str, positions: Str): Unit {
    var merged: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
    val old: *Str = sigs.getPtr(name)
    if (old != null) {
        for (part in old.split(",")) {
            val parsed: Opt<Int> = part.trim().toInt()
            if (parsed.hasValue()) {
                merged.insert(parsed.value(), true)
            }
        }
    }
    for (part in positions.split(",")) {
        val parsed: Opt<Int> = part.trim().toInt()
        if (parsed.hasValue() && parsed.value() >= 0) {
            merged.insert(parsed.value(), true)
        }
    }
    var text: Str = ""
    for (at in merged.keys()) {
        if (text != "") {
            text = text + ","
        }
        text = text + at.toString()
    }
    sigs.insert(name, text)
}

// The same declaration walk the fixpoint needs, plus the sets the rule reads: the names a
// value may be borrowed *of* (a `data class`), the callees a body may trust to begin with
// (the `data`/`borrow` marks - the borrow-clean fixpoint grows this set), the callees whose
// writes reach only their receiver (the `mutates` generator flag), and the file-level
// `var`s (a write to one is not borrow-clean). `size`/`count` are `data` declarations like
// any other (src/rtl/rtl.kt), so no name is seeded by hand.
fun bpCollectDecls(
    module: *AstXmlNode, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    statics: *Dictionary<Str, Bool>, mutates: *Dictionary<Str, Bool>,
    authorSigs: *Dictionary<Str, Str>
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
                    bpResultTrusted.insert(methodName, true)
                }
                if (bpIsSurfaceName(methodName)) {
                    bpSurfaceShadowed.insert(methodName, true)
                }
            }
        }
        if (decl.name == AstNodeKind.Function && bpMarked(decl)) {
            reads.insert(name, true)
            bpResultTrusted.insert(name, true)
        }
        if (decl.name == AstNodeKind.Function && bpIsSurfaceName(name)) {
            bpSurfaceShadowed.insert(name, true)
        }
        if (decl.name == AstNodeKind.Function && bpMutatesMark(decl)) {
            mutates.insert(name, true)
        }
        if (decl.name == AstNodeKind.Function) {
            // The author's marks, normalized into the table's indexing: a receiver-only
            // `mutates` writes position 0, a positional `mutates:0,2` the positions it
            // names - shifted past the receiver on a member declaration, whose arguments
            // start at slot 1 (`bpParamIndexes`).
            if (bpMutatesMark(decl)) {
                bpSigAdd(authorSigs, name, "0")
            }
            val positions: Str = bpMutatesPositions(decl)
            if (positions.size() > 0) {
                if (xmlIsEmpty(xmlChildPtr(decl, AstNodeKind.Receiver))) {
                    bpSigAdd(authorSigs, name, positions)
                } else {
                    var shifted: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
                    for (part in positions.split(",")) {
                        val parsed: Opt<Int> = part.trim().toInt()
                        if (parsed.hasValue() && parsed.value() >= 0) {
                            shifted.insert(parsed.value() + 1, true)
                        }
                    }
                    var text: Str = ""
                    for (at in shifted.keys()) {
                        if (text != "") {
                            text = text + ","
                        }
                        text = text + at.toString()
                    }
                    bpSigAdd(authorSigs, name, text)
                }
            }
        }
    }
}

// The language's built-in type names (specs/built-in-types.md, specs/core-types.md): a call to
// one is a construction or a conversion, which copies what it is given. The scalars are the
// conversions (`Int64(x)`, `Char(c)`) - a value computed from the argument, no body for the
// fixpoint to prove and no write through anything it is handed - and a body full of them is one
// every numeric loop writes (the strlist scan's `Int64(w.size())` is what used to keep the `for`
// promotion from firing).
fun bpBuiltinType(name: *Str): Bool {
    if ( * name == "Str" || * name == "List" || *name == "Dictionary" || *name == "Opt"
    || *name == "Res" || *name == "Array" || *name == "Span"
    || *name == "Int" || *name == "Int8" || *name == "Int16" || *name == "Int32"
    || *name == "Int64" || *name == "Float32" || *name == "Float64"
    || *name == "Char" || *name == "Bool"
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
// The emitter-spelled builtins the borrow fixpoint trusts by seed (`print`/`println`;
// `CgCall.kt` spells them as borrows, `EscapeParams.epBuiltinBorrow` is the retention
// twin of this list).
fun bpBorrowBuiltins(): List<Str> {
    var out: List<Str> = List<Str>()
    out.append("print")
    out.append("println")
    return out
}

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

// One declaration's body, reduced to what the write-signature fixpoint reads: whether it
// writes through anything it cannot attribute (a file-level `var`, an alias of many
// places, an opaque callee), whether a call has no name at all to check (a lambda value
// called through), the parameter positions its writes *do* reach, and one precomputed
// event per call - the callee's name with every slot the callee could write, classified
// to the storage that slot derives from (`bpClassifyPlace`). A round re-evaluates the
// events against the current signature table instead of walking the body again.
data class BpPure(
    var name: Str,
    var hasWrite: Bool,
    var opaqueCall: Bool,
    var events: List<BpCallEvent>,
    var writes: Dictionary<Int, Bool>,
    var fresh: Dictionary<Str, Bool>,
    var alias: Dictionary<Str, Int>,
    // The declaration the facts came from (by value: the walk that collected it
    // iterated `xmlDecls`' fresh list, so a pointer into it would dangle), and its
    // parameter indexes: the result-provenance fixpoint below re-walks the body
    // each round (its classification depends on the current provenance table), so
    // it keeps what `bpPurConsider` already computed rather than resolving the
    // decl again.
    var decl: AstXmlNode,
    var params: Dictionary<Str, Int>
)

// One call the fixpoint weighs: the callee's name, and every slot the callee could write
// - the receiver first for a member call, the arguments after it - each already
// classified (`bpClassifyPlace`): `bpSlotFresh` (storage the body built), `bpSlotOther`
// (unattributable), or the 0-based position of the parameter the place reaches.
data class BpCallEvent(
    var callee: Str,
    var slots: List<Int>
)

val bpSlotFresh: Int = -2
val bpSlotOther: Int = -1

// The declaration's parameters by position: the receiver (an extension's `Receiver` child,
// or a class method's implicit `this`) is index 0 and the declared parameters follow it;
// a free function's parameters start at 0. The signature table and every event share this
// indexing, so a method's receiver writes and a free function's first-parameter writes are
// the same slot a call site checks.
fun bpParamIndexes(fn: *AstXmlNode, method: Bool): Dictionary<Str, Int> {
    var out: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var next: Int = 0
    val recv: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.Receiver)
    if (!xmlIsEmpty(recv) || method) {
        out.insert("this", 0)
        next = 1
    }
    for (*param in xmlChildren(fn, AstNodeKind.Param)) {
        out.insert(xmlAttr(param, AstNodeAttributeKind.Name), next)
        next++
    }
    return out
}

// Whether the declaration has a receiver at position 0 (`bpParamIndexes` gave `this` the
// slot): a place aliased to *it* is storage the caller passes as `T* self` whatever this
// pass decides, so a write reaching it never threatens a borrowed parameter.
fun bpHasReceiver(fn: *AstXmlNode, method: Bool): Bool {
    if (!xmlIsEmpty(xmlChildPtr(fn, AstNodeKind.Receiver))) {
        return true
    }
    return method
}

// The storage a place expression derives from: `bpSlotFresh` when the body built it (a
// copy, a temporary, a place chain rooted at a clean local), `bpSlotOther` when the
// derivation cannot be attributed (a static, an untrusted call's result, an alias of more
// than one parameter), or the parameter position (the receiver at 0) the place reaches.
// `fresh` holds the body's clean locals, `alias` the locals whose binding reaches exactly
// one interesting place, `params` the parameter positions, and a trusted call's result is
// classified from its arguments - `leak(p)` hands back `p`'s storage, and `strBytes(x)` a
// view into `x`, so the arguments' positions are the result's position. A construction
// copies what it is given, so it is fresh regardless.
fun bpClassifyPlace(
    node: *AstXmlNode, fresh: *Dictionary<Str, Bool>, alias: *Dictionary<Str, Int>,
    params: *Dictionary<Str, Int>, types: *Dictionary<Str, Bool>
): Int {
    if (xmlIsEmpty(node)) {
        // No expression at all - a local declared without an initializer, which cannot
        // alias anything: storage the body owns.
        return bpSlotFresh
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprName) {
        val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (params.has(name)) {
            val at: *Int = params.getPtr(name)
            return *at
        }
        if (alias.has(name)) {
            val at: *Int = alias.getPtr(name)
            return *at
        }
        if (fresh.has(name)) {
            return bpSlotFresh
        }
        return bpSlotOther
    }
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprDeref) {
        return bpClassifyPlace(
            xmlChildPtr(node, AstNodeKind.Operand), fresh, alias, params, types
        )
    }
    if (kind == AstNodeCategory.ExprMember || kind == AstNodeCategory.ExprIndex) {
        return bpClassifyPlace(
            xmlChildPtr(node, AstNodeKind.Receiver), fresh, alias, params, types
        )
    }
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        if (bpIsConstruct(callee, types)) {
            return bpSlotFresh
        }
        val calleeKind: AstNodeCategory = xmlKind(callee)
        if (calleeKind != AstNodeCategory.ExprName && calleeKind != AstNodeCategory.ExprMember) {
            return bpSlotOther
        }
        val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        // Only a declaration the *author* marked (`data`/`borrow`) - or the
        // result-provenance fixpoint proved (a borrow-clean body whose every return
        // classifies, `bpInferResults`) - promises its result derives from what it was
        // handed: the write proof alone says nothing about the result, and a
        // proved-clean callee can still hand back a view of a static (`getSourceGens`),
        // which a write through the local would reach. The sound answer for a callee
        // neither covers is refusal.
        val provenance: *Str = bpResultNames.getPtr(calleeName)
        if (!bpResultTrusted.has(calleeName) && provenance == null) {
            return bpSlotOther
        }
        if (provenance != null && !bpResultTrusted.has(calleeName)) {
            if (*provenance == "fresh") {
                return bpSlotFresh
            }
            // The entry names the positions the result can reach; classify the call's
            // slot at each (the receiver at 0 on a member call, the arguments after
            // it - the same indexing `slotsSafe` checks) and join. A slot the call
            // does not have, or one the classifier cannot attribute, refuses.
            var reach: Int = bpSlotFresh
            var positions: List<Str> = (*provenance).split(",")
            for (posText in positions) {
                val parsed: Opt<Int> = posText.trim().toInt()
                if (!parsed.hasValue() || parsed.value() < 0) {
                    return bpSlotOther
                }
                val at: Int = parsed.value()
                var slot: *AstXmlNode = null
                if (calleeKind == AstNodeCategory.ExprMember) {
                    if (at == 0) {
                        slot = xmlChildPtr(callee, AstNodeKind.Receiver)
                    } else {
                        slot = bpArgAt(node, at - 1)
                    }
                } else {
                    slot = bpArgAt(node, at)
                }
                if (slot == null) {
                    return bpSlotOther
                }
                val cls: Int = bpClassifyPlace(slot, fresh, alias, params, types)
                if (cls == bpSlotOther) {
                    return bpSlotOther
                }
                if (cls >= 0) {
                    if (reach >= 0 && reach != cls) {
                        return bpSlotOther
                    }
                    reach = cls
                }
            }
            return reach
        }
        // A trusted callee's result derives from what it was handed - `leak(p)` hands
        // back `p`'s storage, `strBytes(x)` a view into `x` - so the places it was
        // handed are the places the result can reach. The receiver is one of them on a
        // member call (`this.posAttrs(...)`), and a write through the result reaches
        // the receiver (position 0), which a method's caller passes as `T* self`.
        var reach: Int = bpSlotFresh
        var combine: Int = bpSlotFresh
        if (calleeKind == AstNodeCategory.ExprMember) {
            combine = bpClassifyPlace(
                xmlChildPtr(callee, AstNodeKind.Receiver), fresh, alias, params, types
            )
            if (combine == bpSlotOther) {
                return bpSlotOther
            }
            if (combine >= 0) {
                reach = combine
            }
        }
        for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
            combine = bpClassifyPlace(arg, fresh, alias, params, types)
            if (combine == bpSlotOther) {
                return bpSlotOther
            }
            if (combine >= 0) {
                if (reach >= 0 && reach != combine) {
                    // Two different parameters: the place could reach either.
                    return bpSlotOther
                }
                reach = combine
            }
        }
        return reach
    }
    // Everything else - a literal, an operator, a lambda - is a value the body built; a
    // write through it lands in the body's own temporary.
    return bpSlotFresh
}

// One call's slots, classified: the receiver first for a member call, then the arguments.
fun bpCallSlots(
    node: *AstXmlNode, callee: *AstXmlNode, fresh: *Dictionary<Str, Bool>,
    alias: *Dictionary<Str, Int>, params: *Dictionary<Str, Int>,
    types: *Dictionary<Str, Bool>
): List<Int> {
    var out: List<Int> = List<Int>()
    if (xmlKind(callee) == AstNodeCategory.ExprMember) {
        out.append(
            bpClassifyPlace(
                xmlChildPtr(callee, AstNodeKind.Receiver), fresh, alias, params, types
            )
        )
    }
    for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
        out.append(bpClassifyPlace(arg, fresh, alias, params, types))
    }
    return out
}

// The write/callee facts of one body, written in place through the pointer (a *field* of a pointer
// passed as an argument would be copied - `agents.md`, the `*this.sections` trap).
fun bpPureWalk(
    node: *AstXmlNode, out: *BpPure, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    callables: *Dictionary<Str, Bool>, params: *Dictionary<Str, Int>
): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.StmtVarDecl) {
        // A declared local is classified by its initializer: fresh when the derivation
        // reaches no lent place, or an alias of the one parameter (the receiver at 0) it
        // does reach. A static shadowed by the name stays out of both.
        val declared: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (!statics.has(declared)) {
            val cls: Int = bpClassifyPlace(
                xmlChildPtr(node, AstNodeKind.Init), out.fresh, out.alias, params, types
            )
            if (cls == bpSlotFresh) {
                out.fresh.insert(declared, true)
            } else if (cls >= 0) {
                out.alias.insert(declared, cls)
            }
        }
    }
    if (kind == AstNodeCategory.StmtAssign) {
        val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
        if (xmlKind(target) != AstNodeCategory.ExprName) {
            // A write through a field, an index or a pointer: attributed to the place the
            // chain roots at, when that place is one the fixpoint can name.
            val cls: Int = bpClassifyPlace(target, out.fresh, out.alias, params, types)
            if (cls == bpSlotOther) {
                out.hasWrite = true
            } else if (cls >= 0) {
                out.writes.insert(cls, true)
            }
        } else if (statics.has(xmlAttr(target, AstNodeAttributeKind.Name))) {
            // A file-level `var` (see the header): a caller may hold a pointer into it.
            out.hasWrite = true
        } else {
            val targetName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            val cls: Int = bpClassifyPlace(
                xmlChildPtr(node, AstNodeKind.Value), out.fresh, out.alias, params, types
            )
            if (cls >= 0) {
                // The rebinding keeps the alias it derives from (`bpClassifyPlace`), and
                // an unattributable derivation is a local writes-through cannot trust.
                out.alias.insert(targetName, cls)
            } else if (cls == bpSlotOther) {
                out.alias.insert(targetName, bpSlotOther)
            }
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val ck: AstNodeCategory = xmlKind(callee)
        if (bpIsConstruct(callee, types)) {
            // A construction copies what it is given; see `bpIsConstruct`.
        } else if (bpMachineStep(callee)) {
            // The `for` machine's own step; see `bpMachineStep`.
        } else if (ck == AstNodeCategory.ExprName && callables.has(xmlAttr(callee, AstNodeAttributeKind.Name))) {
            // A call to a callable-by-value parameter of the enclosing declaration: the
            // callee cannot name a place through which to write (`bpCallableParams`), so
            // it imposes no obligation either - `repeat`'s `body(i)` and `forEach`'s
            // `f(x)` are the shapes their own proofs were refused on.
        } else if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember) {
            // Every other named call is one event: the callee, and the classification of
            // every slot it could write. The author's marks and the fixpoint's own table
            // decide, per round, which slots those are (`bpInferWrites`).
            out.events.append(
                BpCallEvent(
                    xmlAttr(callee, AstNodeAttributeKind.Name),
                    bpCallSlots(node, callee, out.fresh, out.alias, params, types)
                )
            )
        } else {
            out.opaqueCall = true
        }
    }
    for (*child in node.Children) {
        bpPureWalk(child, out, statics, types, reads, callables, params)
    }
}

// One declaration whose body the fixpoint may prove: it has a body, it is not native, and it is
// not already trusted by an author's mark (`data`/`borrow`). Every declaration of a name has to
// attribute before the *name* carries a signature (`bpInferWrites`), which is what lets an
// overloaded name be proved - the name is not attributed to one signature, it is a conjunction
// over all of them.
fun bpPurConsider(
    fn: *AstXmlNode, method: Bool, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>, out: *List<BpPure>
): Unit {
    if (xmlAttr(fn, AstNodeAttributeKind.HasBody) != "true"
        || xmlAttr(fn, AstNodeAttributeKind.IsNative) == "true"
        || bpMarked(fn)
    ) {
        return
    }
    val name: Str = xmlAttr(fn, AstNodeAttributeKind.Name)
    // `main`'s ABI is built-in (the argv form the emitter spells): its parameter is never
    // a rewrite candidate, so its body proves nothing.
    if (name == "main") {
        return
    }
    val body: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.Body)
    if (xmlIsEmpty(body)) {
        return
    }
    var params: Dictionary<Str, Int> = bpParamIndexes(fn, method)
    var fact: BpPure = BpPure(
        name, false, false, List<BpCallEvent>(), Dictionary<Int, Bool>(),
        Dictionary<Str, Bool>(), Dictionary<Str, Int>(), fn, params
    )
    var callables: Dictionary<Str, Bool> = bpCallableParams(fn)
    for (*stmt in body.Children) {
        bpPureWalk(stmt, fact, statics, types, reads, *callables, *params)
    }
    out.append(fact)
}

fun bpPurCollect(
    module: *AstXmlNode, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>, out: *List<BpPure>
): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            bpPurConsider(decl, false, statics, types, reads, out)
        } else if (decl.name == AstNodeKind.DataClass) {
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                bpPurConsider(method, true, statics, types, reads, out)
            }
        }
    }
}

// The *write-signature* fixpoint (impl_specs/escape-analysis.md): a name is borrow-clean
// when every declaration of it that this pass can read attributes every write to no
// parameter at all and calls only names it can weigh, and every declaration it cannot
// read (a native, or C++ named by an attribute) is marked by the author (`blocked`). A
// name whose writes attribute to parameter *positions* carries a signature instead - the
// union of its declarations' positions and its author's marks - and a call site may still
// trust it when every written position takes an argument the caller built. Both tables
// grow from *below* - a name joins only once every call its bodies make is to a name
// already weighed - so a recursive cycle is never assumed clean and no name is ever added
// on a guess. The result is the least fixpoint, the sound direction: a name left out is
// simply not trusted, and the emitted code is unchanged for it.
fun bpInferWrites(
    facts: *List<BpPure>, blocked: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
    writesOf: *Dictionary<Str, Str>, authorSigs: *Dictionary<Str, Str>
): Unit {
    var changed: Bool = true
    while (changed) {
        changed = false
        // One round's raw state, per name: attributable so far, and the positions its
        // attributable declarations reached. A declaration that writes through something
        // it cannot attribute, or calls a name neither table weighs, is not attributable
        // this round - the retry the loop exists for. The state is recomputed whole, so
        // two overloads cannot let each other in on half a proof.
        var ok: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var seen: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var roundWrites: Dictionary<Str, Dictionary<Int, Bool>> = Dictionary<Str, Dictionary<Int, Bool>>()
        for (*fact in facts) {
            seen.insert(fact.name, true)
            if (!ok.has(fact.name)) {
                ok.insert(fact.name, true)
            }
            if (fact.hasWrite || fact.opaqueCall) {
                ok.insert(fact.name, false)
                continue
            }
            var positions: *Dictionary<Int, Bool> = roundWrites.getPtr(fact.name)
            if (positions == null) {
                roundWrites.insert(fact.name, Dictionary<Int, Bool>())
                positions = roundWrites.getPtr(fact.name)
            }
            for (at in fact.writes.keys()) {
                positions.insert(at, true)
            }
            for (*event in fact.events) {
                if (bpCallTrusted(event.callee, reads)) {
                    continue
                }
                val sig: *Str = writesOf.getPtr(event.callee)
                if (sig == null) {
                    ok.insert(fact.name, false)
                    continue
                }
                var indexes: List<Str> = sig.split(",")
                for (posText in indexes) {
                    val parsed: Opt<Int> = posText.trim().toInt()
                    if (!parsed.hasValue()) {
                        ok.insert(fact.name, false)
                        continue
                    }
                    val at: Int = parsed.value()
                    if (at < 0 || at >= event.slots.size()) {
                        ok.insert(fact.name, false)
                        continue
                    }
                    val slot: Int = event.slots[at]
                    if (slot == bpSlotOther) {
                        ok.insert(fact.name, false)
                    } else if (slot >= 0) {
                        positions.insert(slot, true)
                    }
                }
            }
        }
        // The author's marks weigh beside the facts: a marked name with no declaration
        // this pass can read (the body-less `mutates` natives) carries the mark alone.
        for (*name in authorSigs.keys()) {
            if (!seen.has(name)) {
                seen.insert(name, true)
                ok.insert(name, true)
            }
        }
        // The commit, revisionary on purpose: a name's positions can grow as the callees
        // it weighs grow, so a name already committed as clean moves to the signature
        // table when a later round attributes positions to it (the ladder - the tables
        // only ever gain write-knowledge, so the loop ends).
        for (*name in seen.keys()) {
            val okAt: *Bool = ok.getPtr(name)
            if (okAt == null || !*okAt || blocked.has(name)) {
                continue
            }
            var positions: Dictionary<Int, Bool> = Dictionary<Int, Bool>()
            val own: *Dictionary<Int, Bool> = roundWrites.getPtr(name)
            if (own != null) {
                for (at in own.keys()) {
                    positions.insert(at, true)
                }
            }
            val marks: *Str = authorSigs.getPtr(name)
            if (marks != null) {
                var indexes: List<Str> = marks.split(",")
                for (posText in indexes) {
                    val parsed: Opt<Int> = posText.trim().toInt()
                    if (parsed.hasValue() && parsed.value() >= 0) {
                        positions.insert(parsed.value(), true)
                    }
                }
            }
            if (positions.size() == 0) {
                if (!reads.has(name) && !writesOf.has(name)) {
                    reads.insert(name, true)
                    changed = true
                }
                continue
            }
            var text: Str = ""
            for (at in positions.keys()) {
                if (text != "") {
                    text = text + ","
                }
                text = text + at.toString()
            }
            if (reads.has(name)) {
                reads.remove(name)
                changed = true
            }
            val current: *Str = writesOf.getPtr(name)
            if (current == null || *current != text) {
                writesOf.insert(name, text)
                changed = true
            }
        }
    }
    if (bpShow() || diagOn("borrow")) {
        for (*fact in facts) {
            if (reads.has(fact.name) || writesOf.has(fact.name)) {
                continue
            }
            var why: Str = ""
            for (*event in fact.events) {
                if (bpCallTrusted(event.callee, reads)) {
                    continue
                }
                val sig: *Str = writesOf.getPtr(event.callee)
                if (sig == null) {
                    why = why + " " + event.callee
                    continue
                }
                // A signature the event cannot satisfy (a slot out of range, or a place
                // the classifier cannot attribute): name the callee with its signature.
                var sigOk: Bool = true
                for (posText in sig.split(",")) {
                    val parsed: Opt<Int> = posText.trim().toInt()
                    if (!parsed.hasValue() || parsed.value() < 0 || parsed.value() >= event.slots.size()) {
                        sigOk = false
                        break
                    }
                    if (event.slots[parsed.value()] == bpSlotOther) {
                        sigOk = false
                        break
                    }
                }
                if (!sigOk) {
                    val sigText: Str = *sig
                    why = why + " " + event.callee + "[" + sigText + "]"
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
        for (*name in writesOf.keys()) {
            val sigText: Str = *writesOf.getPtr(name)
            bpNote(`borrow~ @name @sigText`)
        }
    }
}

// ---- the result-provenance fixpoint -------------------------------------------
//
// `bpResultTrusted` above is the author's word - the only names whose *result* the
// classifier may derive from their arguments. The fixpoint here proves the same fact for
// the names the write-signature fixpoint already proved borrow-clean: a clean body whose
// every return classifies (by `bpClassifyPlace`, against the current tables) to storage
// the body built (`fresh`) or to exactly one parameter position carries a provenance
// entry, and the classifier consults the table wherever the mark set misses. That is the
// named follow-up from the write-signature change: `var attrs = this.posAttrs(...)`
// initializes from a proved, unmarked callee, and without an entry the local is
// unattributable - a write through it refused the whole body.
//
// The rounds re-walk the bodies: a return's classification depends on the callees in it,
// whose entries grow as the table grows, so the facts cannot be precomputed the way
// `bpInferWrites` precomputes its events. Entries only widen (`fresh` -> positions ->
// more positions) or drop (a widened callee turns a join into two different positions,
// which is `bpSlotOther`), and a drop cannot un-drop (the widening that caused it is
// permanent), so the iteration from the empty table reaches a stable, self-justified
// table - every entry in it is justified by the final table it is read against.
//
// Soundness stays the author's one rule: *only a proved-clean name earns an entry* (a
// body the write fixpoint refused could write through anything, result included), and a
// body whose result escapes the classifier - a file-level `var` (`getSourceGens` hands
// back a static's storage), an unmarked callee's result, a lambda value - contributes
// `bpSlotOther` and leaves the name out. The laundering shape this guards is pinned by
// `stress/borrow-provenance`.

// The proved table itself: name -> `fresh` or the comma positions (the receiver at 0)
// the result's storage can reach. Absent means refused - the classifier treats the
// result as unattributable, exactly the behavior before the fixpoint existed.
var bpResultNames: Dictionary<Str, Str> = Dictionary<Str, Str>()

// The join of two return classifications: anything with `bpSlotOther` refuses; a
// position joins `fresh` to itself; two different positions refuse (the place could
// reach either).
fun bpProvJoin(left: Int, right: Int): Int {
    if (left == bpSlotOther || right == bpSlotOther) {
        return bpSlotOther
    }
    if (left == bpSlotFresh) {
        return right
    }
    if (right == bpSlotFresh) {
        return left
    }
    if (left == right) {
        return left
    }
    return bpSlotOther
}

// One declaration's returns classified: `bpPureWalk`'s local bookkeeping (a declared
// local classified by its initializer, a rebinding keeping the alias it derives from)
// plus one classification per `StmtReturn`. A lambda's own returns are the lambda's -
// the walk does not enter it - and its effects on the enclosing body's locals are by
// value (captures copy), so the enclosing classification cannot see them.
fun bpProvWalk(
    node: *AstXmlNode, fresh: *Dictionary<Str, Bool>, alias: *Dictionary<Str, Int>,
    params: *Dictionary<Str, Int>, statics: *Dictionary<Str, Bool>,
    types: *Dictionary<Str, Bool>, out: *List<Int>
): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprLambda) {
        return
    }
    if (kind == AstNodeCategory.StmtVarDecl) {
        val declared: Str = xmlAttr(node, AstNodeAttributeKind.Name)
        if (!statics.has(declared)) {
            val cls: Int = bpClassifyPlace(
                xmlChildPtr(node, AstNodeKind.Init), fresh, alias, params, types
            )
            if (cls == bpSlotFresh) {
                fresh.insert(declared, true)
            } else if (cls >= 0) {
                alias.insert(declared, cls)
            }
        }
    }
    if (kind == AstNodeCategory.StmtAssign) {
        val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
        if (xmlKind(target) == AstNodeCategory.ExprName && !statics.has(xmlAttr(target, AstNodeAttributeKind.Name))) {
            val targetName: Str = xmlAttr(target, AstNodeAttributeKind.Name)
            val cls: Int = bpClassifyPlace(
                xmlChildPtr(node, AstNodeKind.Value), fresh, alias, params, types
            )
            if (cls >= 0 || cls == bpSlotOther) {
                alias.insert(targetName, cls)
            }
        }
    }
    if (kind == AstNodeCategory.StmtReturn) {
        out.append(
            bpClassifyPlace(
                xmlChildPtr(node, AstNodeKind.Value), fresh, alias, params, types
            )
        )
        return
    }
    for (*child in node.Children) {
        bpProvWalk(child, fresh, alias, params, statics, types, out)
    }
}

// One round's entry text for a classification: "fresh", the sole position, or "" for a
// refusal (no entry).
fun bpProvText(cls: Int): Str {
    if (cls == bpSlotFresh) {
        return "fresh"
    }
    if (cls >= 0) {
        return cls.toString()
    }
    return ""
}

// The provenance table over the proved-clean names: rounds of `bpProvWalk` per
// declaration, the join per name over its declarations, until a round changes nothing.
// Only names in `reads` (the write fixpoint's proof) are walked; a name with no
// value-returning declaration among them stays absent (its result is never a place).
fun bpInferResults(facts: *List<BpPure>, reads: *Dictionary<Str, Bool>): Unit {
    var rounds: Int = 0
    var changed: Bool = true
    while (changed && rounds < 32) {
        changed = false
        rounds++
        var next: Dictionary<Str, Str> = Dictionary<Str, Str>()
        for (*fact in facts) {
            if (!reads.has(fact.name)) {
                continue
            }
            // The declaration is a value copy the fact owns, so the borrows below
            // (the body pointer, its children) stay alive for the whole walk - a
            // pointer into the collecting walk's list would dangle (`BpPure.decl`).
            var declNode: AstXmlNode = fact.decl
            var params: Dictionary<Str, Int> = fact.params
            var fresh: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
            var alias: Dictionary<Str, Int> = Dictionary<Str, Int>()
            var rets: List<Int> = List<Int>()
            val body: *AstXmlNode = xmlChildPtr(*declNode, AstNodeKind.Body)
            if (!xmlIsEmpty(body)) {
                for (*stmt in body.Children) {
                    bpProvWalk(
                        stmt, *fresh, *alias, *params, *bpStaticNames, *bpKnownTypes,
                        *rets
                    )
                }
            }
            if (rets.size() == 0) {
                continue
            }
            var acc: Int = bpSlotFresh
            for (at in rets) {
                acc = bpProvJoin(acc, at)
            }
            val text: Str = bpProvText(acc)
            if (text == "") {
                // A refusing declaration drags the name: the join is over every
                // declaration the name has.
                next.insert(fact.name, "")
                continue
            }
            val old: *Str = next.getPtr(fact.name)
            if (old != null && *old == "") {
                continue
            }
            if (old == null) {
                next.insert(fact.name, text)
            } else {
                // Two declaring positions join as classifications: fold through the
                // text round-trip, keeping `fresh` under a position.
                var left: Int = bpSlotFresh
                if (*old != "fresh") {
                    val parsed: Opt<Int> = (*old).trim().toInt()
                    left = parsed.value()
                }
                var right: Int = bpSlotFresh
                if (text != "fresh") {
                    val parsedRight: Opt<Int> = text.trim().toInt()
                    right = parsedRight.value()
                }
                val joined: Int = bpProvJoin(left, right)
                val joinedText: Str = bpProvText(joined)
                if (joinedText == "") {
                    next.insert(fact.name, "")
                } else {
                    next.insert(fact.name, joinedText)
                }
            }
        }
        // A refused name leaves no entry ("" was the join's marker inside the round).
        var refused: List<Str> = List<Str>()
        for (*name in next.keys()) {
            if ((*next.getPtr(name)).size() == 0) {
                refused.append(*name)
            }
        }
        for (name in refused) {
            next.remove(name)
        }
        // The commit: entries that appeared, widened or dropped all count as a change.
        var differs: Bool = next.size() != bpResultNames.size()
        if (!differs) {
            for (*name in next.keys()) {
                val was: *Str = bpResultNames.getPtr(name)
                if (was == null || *was != *next.getPtr(name)) {
                    differs = true
                    break
                }
            }
        }
        if (differs) {
            bpResultNames = next
            changed = true
        }
    }
    if (bpShow() || diagOn("borrow")) {
        for (*name in bpResultNames.keys()) {
            val provText: Str = *bpResultNames.getPtr(name)
            bpNote(`borrowprov @name @provText`)
        }
    }
}

// A declaration the fixpoint cannot read: no body, or a native. A call by its name may reach
// it, so unless the author's mark already answers for the name, it blocks the proof - `data`
// and `borrow` answer "writes nothing" (the name is in `reads` before this runs), `mutates`
// and its positional spelling answer "writes these positions" (`bpInferWrites` seeds the
// signature table from them), and no mark at all leaves the name's writes unknown.
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
    if (bpMutatesMark(fn) || bpMutatesPositions(fn).size() > 0) {
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
// all of its bodies attribute (`bpInferWrites`), so no declaration needs an author's mark for
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

// Every fact the rewrite decision reads, written in place through the pointer. The
// marking helpers are methods rather than free functions taking the set: passing a
// *field* of a pointer as an argument materializes a copy (`agents.md`, the
// `*this.sections` trap), so the set must be reached through `this`. `reads` is the
// borrow-clean set (the fixpoint above); `writesOf` the signature table; `statics` the
// file-level `var` names, a write to one of which is a write the rule must see.
data class BpFacts(
    var fieldWrite: Bool,
    var staticWrite: Bool,
    var impureCall: Bool,
    var statics: *Dictionary<Str, Bool>,
    var reads: *Dictionary<Str, Bool>,
    var types: *Dictionary<Str, Bool>,
    var writesOf: *Dictionary<Str, Str>,

    // The names a `&`/`*` anywhere in the body marks: a parameter under one is never
    // borrowed (its address flowed somewhere), and a loop element or place root under one
    // is not place-stable.
    var taken: Dictionary<Str, Bool>,
    var written: Dictionary<Str, Bool>,
    var inLambda: Dictionary<Str, Bool>,

    // The callee names that made a call untrusted, for `--showBorrow`. The decision itself
    // reads only `impureCall`.
    var blocked: List<Str>,

    // The name of the place a *binding promotion* borrows into ("" for the parameter rule):
    // a signature-carrying callee on a place that is a plain name other than this root
    // writes storage the borrowed element cannot live in, so the call is safe for the
    // promotion however the parameter rule treats it.
    var placeRoot: Str,

    // The declaration's parameters by position (`bpParamIndexes`, the receiver at 0), its
    // callable-by-value parameters (`bpCallableParams`), the classifier-maintained local
    // sets (`bpClassifyPlace`: clean locals, and locals aliased to one parameter or the
    // receiver), and whether position 0 is a receiver - a place aliased to *it* is storage
    // the caller passes as `T* self` whatever this pass decides, so writing it never
    // threatens a borrowed parameter.
    var params: Dictionary<Str, Int>,
    var callables: Dictionary<Str, Bool>,
    var fresh: Dictionary<Str, Bool>,
    var alias: Dictionary<Str, Int>,
    var hasReceiver: Bool
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

    // Whether the place is storage this body owns, or storage aliased to the receiver
    // (position 0 of a declaration that has one): a callee handed such a place cannot
    // write through anything this declaration's parameters lent (`bpClassifyPlace`).
    fun placeSafe(node: *AstXmlNode): Bool {
        val cls: Int = bpClassifyPlace(node, this.fresh, this.alias, this.params, this.types)
        if (cls == bpSlotFresh) {
            return true
        }
        return cls == 0 && this.hasReceiver
    }

    // Whether every written position of a signature-carrying callee takes a safe place:
    // the receiver first for a member call, the arguments after it - the same slot order
    // the fixpoint's events classify (`bpCallSlots`). A position the call does not have is
    // a refusal; so is any written place this body does not own.
    fun slotsSafe(call: *AstXmlNode, callee: *AstXmlNode, sig: *Str): Bool {
        val memberCall: Bool = xmlKind(callee) == AstNodeCategory.ExprMember
        var first: Int = 0
        if (memberCall) {
            first = 1
        }
        var indexes: List<Str> = sig.split(",")
        for (posText in indexes) {
            val parsed: Opt<Int> = posText.trim().toInt()
            if (!parsed.hasValue()) {
                return false
            }
            val at: Int = parsed.value()
            if (at < 0) {
                return false
            }
            var slot: *AstXmlNode = null
            if (memberCall && at == 0) {
                slot = xmlChildPtr(callee, AstNodeKind.Receiver)
            } else {
                slot = bpArgAt(call, at - first)
            }
            if (slot == null) {
                return false
            }
            if (this.placeSafe(slot)) {
                continue
            }
            // Under a place promotion, a plain named place other than the root is out of
            // reach: the callee writes that storage alone, and the borrowed element lives
            // in the root's (`placeRoot` above).
            if (this.placeRoot != "" && xmlKind(slot) == AstNodeCategory.ExprName
                && xmlAttr(slot, AstNodeAttributeKind.Name) != this.placeRoot
            ) {
                continue
            }
            return false
        }
        return indexes.size() > 0
    }

    // One statement (or expression) walked, collecting every fact.
    fun walk(node: *AstXmlNode): Unit {
        val kind: AstNodeCategory = xmlKind(node)
        if (kind == AstNodeCategory.StmtVarDecl) {
            // A declared local is one the body built - even with no initializer, which
            // cannot alias anything at all. The mutator relaxation below reads the
            // classifier's sets, and so does the parameter rule (`written` parameters are
            // never borrowed, since the body rebinds them).
            val declared: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            if (!this.statics.has(declared)) {
                this.written.insert(declared, true)
                val cls: Int = bpClassifyPlace(
                    xmlChildPtr(node, AstNodeKind.Init), this.fresh, this.alias,
                    this.params, this.types
                )
                if (cls == bpSlotFresh) {
                    this.fresh.insert(declared, true)
                } else if (cls >= 0) {
                    this.alias.insert(declared, cls)
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
                    val cls: Int = bpClassifyPlace(
                        xmlChildPtr(node, AstNodeKind.Value), this.fresh, this.alias,
                        this.params, this.types
                    )
                    if (cls >= 0 || cls == bpSlotOther) {
                        // The rebinding keeps whatever the value derives from - an alias
                        // of a parameter, or a mark that writes through it can never trust
                        // (`bpSlotOther`). A fresh value leaves a fresh local fresh.
                        this.alias.insert(targetName, cls)
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
                if (!safe && ck == AstNodeCategory.ExprName && this.callables.has(calleeName)) {
                    // A call to a callable-by-value *parameter* of the enclosing
                    // declaration: the callee is the caller's own function value, and its
                    // signature cannot name a place (`bpCallableParams`), so the most it
                    // can touch is the copies its arguments are.
                    safe = true
                }
                if (!safe) {
                    // A signature-carrying callee - the author's `mutates` marks and the
                    // fixpoint's own proofs are one table (`bpInferWrites`): the call is
                    // safe when every position the callee writes takes a place this body
                    // owns (`slotsSafe`).
                    val sig: *Str = this.writesOf.getPtr(calleeName)
                    if (sig != null && this.slotsSafe(node, callee, sig)) {
                        safe = true
                    }
                }
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

// The declaration's parameters that may be *called* without dirtying the body: a
// declared callable type (`(Int) -> Unit`) whose every parameter is a value - no `*T`, no
// `&T`, either of which would hand the callee a place it could write through. A bare type
// parameter is not a callable the rules can read, so it stays out (sound refusal).
fun bpCallableParams(fn: *AstXmlNode): Dictionary<Str, Bool> {
    var out: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*param in xmlChildren(fn, AstNodeKind.Param)) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType) || xmlKind(paramType) != AstNodeCategory.TypeFunction) {
            continue
        }
        var byValue: Bool = true
        for (*one in xmlChildren(paramType, AstNodeKind.ParamType)) {
            val oneKind: AstNodeCategory = xmlKind(one)
            if (oneKind == AstNodeCategory.TypePointer || oneKind == AstNodeCategory.TypeReference) {
                byValue = false
            }
        }
        if (byValue) {
            out.insert(xmlAttr(param, AstNodeAttributeKind.Name), true)
        }
    }
    return out
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
// The names whose declarations carry an author's `data`/`borrow` mark: the only callees
// whose *result* the classifier may derive from their arguments. The fixpoint proves a
// name writes nothing, which says nothing about what its result aliases - a proved-clean
// `getSourceGens` hands back a static's storage - so an unmarked callee's result is
// unattributable however clean it proved (`bpClassifyPlace`).
var bpResultTrusted: Dictionary<Str, Bool> = Dictionary<Str, Bool>()

// The write-signature table the fixpoint proved (name -> the comma positions it writes,
// the receiver at 0), with the author's `mutates` marks seeded in. Kept for the rewrite
// walk, sema's promotions and the span walk, beside the trusted names.
var bpWritesNames: Dictionary<Str, Str> = Dictionary<Str, Str>()
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
// (`Str`, or a data class holding one), or a container of a deep element (`List<List<Str>>`,
// `Dictionary<Str, T>` - the ownership is recursive through the containers that own heap
// storage; types are acyclic, so the recursion ends). A scalar, a handle, a view (`Span`,
// `Array`) and a class of those are left as written - `impl_specs/for.md` measured the value
// form as fast or faster for them.
fun bpDeepElement(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
        return false
    }
    val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
    if (kind == AstNodeCategory.TypeNamed) {
        if (name == "Str") {
            return true
        }
        return bpDeepTypes.has(name)
    }
    if (name != "List" && name != "Dictionary" && name != "Opt" && name != "Res") {
        return false
    }
    val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
    for (*arg in args) {
        if (bpDeepElement(arg)) {
            return true
        }
    }
    return false
}

// Whether `body` only reads `name`: no write through it, no `&`/`*`, no capture, and no call the
// fixpoint cannot trust - so the element's place is stable for the whole loop. The same rule the
// parameter borrow uses, so a `for` promotion and a parameter borrow agree.
fun bpLoopReadOnly(body: *List<AstXmlNode>, name: *Str): Bool {
    var facts: BpFacts = BpFacts(
        false, false, false, *bpStaticNames, *bpTrustedNames, *bpKnownTypes, *bpWritesNames,
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), List<Str>(),
        "", Dictionary<Str, Int>(), Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(), Dictionary<Str, Int>(), false
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

// Whether `body` only reads the place named `name`, borrowed out of the storage rooted at
// `root` (a `val x = c[i]` promotion's two names): the rule `bpLoopReadOnly` applies to the
// element, plus the root itself - a rebind of the root (`c = ...`) frees the storage the
// borrowed place lives in, and a capture of either name could carry the pointer out.
fun bpPlaceReadOnly(body: *List<AstXmlNode>, name: *Str, root: *Str): Bool {
    var facts: BpFacts = BpFacts(
        false, false, false, *bpStaticNames, *bpTrustedNames, *bpKnownTypes, *bpWritesNames,
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), List<Str>(),
        *root, Dictionary<Str, Int>(), Dictionary<Str, Bool>(),
        Dictionary<Str, Bool>(), Dictionary<Str, Int>(), false
    )
    for (*stmt in body) {
        // The promoted declaration itself is the binding being retyped, not a write.
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
    if (facts.written.has(*root) || facts.taken.has(*root) || facts.inLambda.has(*root)) {
        return false
    }
    return true
}

// ---- the span hoist stability rule -------------------------------------------

// Whether `body` keeps the container named `root` storage-stable: the buffer its
// elements live in does not move, so a span taken before the block sees every element
// the block reads or writes. An element write (c[i] = v) is stable - it writes through
// the buffer, it does not move it; what moves a buffer is a mutating call on it, a
// rebind of the name, or an escape of the name. The trust rule is the parameter rule,
// with the place-promotion relaxation generalized: a mutates-marked callee on a named
// receiver other than the root moves that receiver buffer alone.
data class BpSpan(
    var rebind: Bool,
    var escape: Bool,
    var unsafeCall: Bool
)

fun bpSpanWalk(node: *AstXmlNode, root: *Str, facts: *BpSpan): Unit {
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.StmtAssign) {
        val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
        if (xmlKind(target) == AstNodeCategory.ExprName
            && xmlAttr(target, AstNodeAttributeKind.Name) == *root
        ) {
            facts.rebind = true
        } else if (xmlKind(target) == AstNodeCategory.ExprMember) {
            // A field write through the root (`this.items = ...`, `box.parts = ...`): the
            // field holding the buffer a walk or hoist borrowed is itself rebound. An
            // *index* link is an element write - it goes through the buffer, it does not
            // move it - so the chain stops there (`c[i] = v` stays the scan kernel's
            // stable shape, the hoist's own proof), and `c[i].f = v` with it.
            var chain: *AstXmlNode = target
            var crossed: Bool = false
            var guard: Int = 0
            while (xmlKind(chain) == AstNodeCategory.ExprMember && guard < 32) {
                guard++
                crossed = true
                chain = xmlChildPtr(chain, AstNodeKind.Receiver)
            }
            if (crossed && xmlKind(chain) == AstNodeCategory.ExprName
                && xmlAttr(chain, AstNodeAttributeKind.Name) == *root
            ) {
                facts.rebind = true
            }
        }
    }
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        var safe: Bool = bpIsConstruct(callee, *bpKnownTypes) || bpMachineStep(callee)
        val ck: AstNodeCategory = xmlKind(callee)
        if (!safe && (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprMember)) {
            val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (bpCallTrusted(calleeName, *bpTrustedNames)) {
                safe = true
            }
            if (!safe && bpMutatesNames.has(calleeName) && ck == AstNodeCategory.ExprMember) {
                val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
                if (xmlKind(receiver) == AstNodeCategory.ExprName
                    && xmlAttr(receiver, AstNodeAttributeKind.Name) != *root
                ) {
                    safe = true
                }
            }
            if (!safe && bpWritesNames.has(calleeName)) {
                // A signature-carrying callee: unsafe only when a written slot names the
                // root - any other place the callee writes moves that storage alone, and
                // the span stays valid. The over-approximation is `bpNameUnder`.
                val sig: *Str = bpWritesNames.getPtr(calleeName)
                var memberCall: Bool = ck == AstNodeCategory.ExprMember
                var first: Int = 0
                if (memberCall) {
                    first = 1
                }
                var rootSafe: Bool = true
                for (posText in sig.split(",")) {
                    val parsed: Opt<Int> = posText.trim().toInt()
                    if (!parsed.hasValue()) {
                        rootSafe = false
                        break
                    }
                    val at: Int = parsed.value()
                    if (at < 0) {
                        rootSafe = false
                        break
                    }
                    var slot: *AstXmlNode = null
                    if (memberCall && at == 0) {
                        slot = xmlChildPtr(callee, AstNodeKind.Receiver)
                    } else {
                        slot = bpArgAt(node, at - first)
                    }
                    if (slot == null || bpNameUnder(slot, *root)) {
                        rootSafe = false
                        break
                    }
                }
                if (rootSafe) {
                    safe = true
                }
            }
        }
        if (!safe) {
            facts.unsafeCall = true
        }
    }
    if (kind == AstNodeCategory.ExprRef || kind == AstNodeCategory.ExprLambda) {
        if (bpNameUnder(node, *root)) {
            facts.escape = true
        }
        if (kind == AstNodeCategory.ExprLambda) {
            return
        }
    }
    for (*child in node.Children) {
        bpSpanWalk(child, root, facts)
    }
}

// Whether the plain name appears anywhere under `node` - the over-approximation the
// escape check wants.
fun bpNameUnder(node: *AstXmlNode, name: Str): Bool {
    if (xmlKind(node) == AstNodeCategory.ExprName
        && xmlAttr(node, AstNodeAttributeKind.Name) == name
    ) {
        return true
    }
    for (*child in node.Children) {
        if (bpNameUnder(child, name)) {
            return true
        }
    }
    return false
}

// One node (a statement, an expression, a condition) walked for the stability facts.
fun bpSpanStableNode(node: *AstXmlNode, root: Str): Bool {
    var facts: BpSpan = BpSpan(false, false, false)
    bpSpanWalk(node, root, facts)
    return !facts.rebind && !facts.escape && !facts.unsafeCall
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
    decl: *AstXmlNode, method: Bool, types: *Dictionary<Str, Bool>, reads: *Dictionary<Str, Bool>,
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
        false, false, false, statics, reads, types, *bpWritesNames,
        Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), Dictionary<Str, Bool>(), List<Str>(),
        "", bpParamIndexes(decl, method), bpCallableParams(decl),
        Dictionary<Str, Bool>(), Dictionary<Str, Int>(), bpHasReceiver(decl, method)
    )
    for (*stmt in body.Children) {
        facts.walk(stmt)
    }
    // A write through a field/index/deref or a file-level `var`, or a call the analysis cannot
    // trust, borrows nothing: each is the "unsure" that means escape.
    if (facts.fieldWrite || facts.staticWrite || facts.impureCall) {
        if (bpShow() || diagOn("borrow")) {
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
        // The body was clean but nothing survived the per-param checks: say which heavy
        // parameter was refused and why (written / taken / captured), the one answer the
        // report otherwise leaves silent. No heavy parameter, no line - scalars were never
        // candidates.
        if (bpShow() || diagOn("borrow")) {
            var refused: Str = ""
            for (*param in params) {
                val paramName: Str = xmlAttr(param, AstNodeAttributeKind.Name)
                if (paramName == "this") {
                    continue
                }
                if (!bpHeavy(xmlChildPtr(param, AstNodeKind.Type), types)) {
                    continue
                }
                var why: Str = ""
                if (facts.written.has(paramName)) {
                    why = "written"
                }
                if (facts.taken.has(paramName)) {
                    if (why != "") {
                        why = why + "+"
                    }
                    why = why + "taken"
                }
                if (facts.inLambda.has(paramName)) {
                    if (why != "") {
                        why = why + "+"
                    }
                    why = why + "captured"
                }
                if (why == "") {
                    why = "refused"
                }
                if (refused != "") {
                    refused = refused + " "
                }
                refused = refused + paramName + ":" + why
            }
            if (refused != "") {
                val declName: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
                bpNote(`borrow- @declName @refused`)
            }
        }
        return decl
    }
    val name: Str = xmlAttr(decl, AstNodeAttributeKind.Name)
    if (prelude && cppCalled.has(name)) {
        if (bpShow() || diagOn("borrow")) {
            bpNote(`borrow- @name C++ calls it (prelude)`)
        }
        return decl
    }
    if (bpShow() || diagOn("borrow")) {
        val bpNamesText: Str = bpNames(*borrowed)
        bpNote(`borrow+ @name @bpNamesText`)
    }
    // `--no-borrow`: the analysis above still runs (so `--showBorrow` reads it), the rewrite does
    // not - the emitted C++ is what the author wrote.
    if (bpNoBorrow()) {
        return decl
    }
    bpRewrittenDecls = bpRewrittenDecls + 1
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

// The declarations the pass rebuilt, for the `borrow` topic's summary (reset per program).
var bpRewrittenDecls: Int = 0

// One module rewritten: a data class rebuilt when a method borrows, a function rebuilt when it
// borrows, everything else kept as it is. Only a name the fixpoint weighed is touched - clean or
// signature-carrying: a signature name's unwritten parameters borrow while its written ones keep
// their authored spelling, and a declaration whose name has a twin the pass could not read (a
// bodyless prototype beside the body a generator supplied) must keep the signature the twin's
// callers resolve against.
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
            if ((reads.has(name) || bpWritesNames.has(name)) && !valueUsed.has(name)) {
                kids.append(bpBorrowDecl(child, false, types, reads, statics, cppCalled, prelude))
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
                    if ((reads.has(name) || bpWritesNames.has(name)) && !valueUsed.has(name)) {
                        methods.append(bpBorrowDecl(method, true, types, reads, statics, cppCalled, prelude))
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

// The fixpoint's facts, rebuilt from the modules: the classification inside `bpPureWalk`
// consults the provenance table (`bpClassifyPlace`), so each outer round of the two
// fixpoints below re-collects them against the table it just seeded.
fun bpCollectFacts(
    preludeModules: List<AstXmlNode>, modules: List<AstXmlNode>,
    statics: *Dictionary<Str, Bool>, types: *Dictionary<Str, Bool>,
    reads: *Dictionary<Str, Bool>
): List<BpPure> {
    var factList: List<BpPure> = List<BpPure>()
    for (*pre in preludeModules) {
        bpPurCollect(pre, *statics, *types, *reads, *factList)
    }
    for (*mod in modules) {
        bpPurCollect(mod, *statics, *types, *reads, *factList)
    }
    return factList
}

// A shallow copy of one of the pass's sets, per the outer round's fresh seed.
fun bpCopyReads(src: *Dictionary<Str, Bool>): Dictionary<Str, Bool> {
    var out: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (key in src.keys()) {
        out.insert(key, true)
    }
    return out
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
    var authorSigs: Dictionary<Str, Str> = Dictionary<Str, Str>()
    bpResultTrusted = Dictionary<Str, Bool>()
    // The emitter-spelled builtins (`print`/`println`) take their argument by `const T&`
    // and write it where it stands (`CgCall.kt`) - a borrow, the same shape
    // `EscapeParams.epBuiltinBorrow` maps for the retention pass. Trusted by seed, so a
    // body that only prints is not borrow-dirty through them.
    for (builtin in bpBorrowBuiltins()) {
        reads.insert(builtin, true)
    }
    for (*pre in preludeModules) {
        bpCollectDecls(pre, *types, *reads, *statics, *mutates, *authorSigs)
    }
    for (*mod in modules) {
        bpCollectDecls(mod, *types, *reads, *statics, *mutates, *authorSigs)
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

    // Grows `reads` and the signature table from the marks and the bodies (see
    // `bpInferWrites`): all the bodies a name has must attribute, and any declaration
    // without a body must be marked. The blocked set is shape-only (no body, no mark,
    // not already read) and never changes across the rounds below.
    var blocked: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*pre in preludeModules) {
        bpBlockedNames(pre, *reads, *blocked)
    }
    for (*mod in modules) {
        bpBlockedNames(mod, *reads, *blocked)
    }
    // The two fixpoints alternate: the write fixpoint proves which names borrow-clean
    // (`reads`), the provenance fixpoint proves which of *those* names' results derive
    // from their arguments - and each entry makes more bodies attribute, which grows
    // `reads` again (the `var attrs = this.posAttrs(...)` family: the write through the
    // local attributes once the callee's result classifies). Each outer round re-collects
    // the facts against the table the previous round seeded, then re-runs the write
    // fixpoint from the marks; both tables only grow, so the alternation settles, and a
    // round that moves nothing is the exit. The cap never binds on real programs; if it
    // ever does, the reset below falls back to the table-less behavior (every unmarked
    // result unattributable), which is the sound floor the pass had before the
    // provenance fixpoint existed.
    bpResultNames = Dictionary<Str, Str>()
    // `bpProvWalk` classifies against these two (they are shape-only: the file-level
    // vars and the data classes, neither of which the rounds below change); the rest of
    // the pass's globals keep their assignment below, from the tables the rounds settle
    // on.
    bpStaticNames = statics
    bpKnownTypes = types
    var seedReads: Dictionary<Str, Bool> = bpCopyReads(*reads)
    var writesOf: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var factList: List<BpPure> = bpCollectFacts(preludeModules, modules, *statics, *types, *reads)
    bpInferWrites(*factList, *blocked, *reads, *writesOf, *authorSigs)
    var outer: Int = 0
    var moved: Bool = true
    while (moved && outer < 4) {
        outer++
        bpInferResults(*factList, *reads)
        var readsBefore: Dictionary<Str, Bool> = bpCopyReads(*reads)
        var writesBefore: Dictionary<Str, Str> = Dictionary<Str, Str>()
        for (key in writesOf.keys()) {
            writesBefore.insert(key, *writesOf.getPtr(key))
        }
        reads = bpCopyReads(*seedReads)
        writesOf = Dictionary<Str, Str>()
        factList = bpCollectFacts(preludeModules, modules, *statics, *types, *reads)
        bpInferWrites(*factList, *blocked, *reads, *writesOf, *authorSigs)
        moved = reads.size() != readsBefore.size() || writesOf.size() != writesBefore.size()
        if (!moved) {
            for (key in reads.keys()) {
                if (!readsBefore.has(key)) {
                    moved = true
                    break
                }
            }
        }
        if (!moved) {
            for (key in writesOf.keys()) {
                if (!writesBefore.has(key)) {
                    moved = true
                    break
                }
                if (*writesBefore.getPtr(key) != *writesOf.getPtr(key)) {
                    moved = true
                    break
                }
            }
        }
        if (moved && outer == 4) {
            bpResultNames = Dictionary<Str, Str>()
            reads = bpCopyReads(*seedReads)
            writesOf = Dictionary<Str, Str>()
            factList = bpCollectFacts(preludeModules, modules, *statics, *types, *reads)
            bpInferWrites(*factList, *blocked, *reads, *writesOf, *authorSigs)
            moved = false
        }
    }
    if (diagOn("borrow")) {
        val readsDiag: Str = reads.size().toString()
        val writesDiag: Str = writesOf.size().toString()
        val resultsDiag: Str = bpResultNames.size().toString()
        val staticsDiag: Str = statics.size().toString()
        val blockedDiag: Str = blocked.size().toString()
        diagTrace(
            "borrow",
            `fixpoint: @readsDiag trusted, @writesDiag signatures, @resultsDiag provenance, @staticsDiag statics, @blockedDiag blocked`
        )
    }

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
    bpMutatesNames = mutates
    bpWritesNames = writesOf

    // Both sides are rewritten: a Simse-bodied prelude declaration borrows exactly like a module
    // one, and only a name C++ may call keeps its authored signature (inside `bpBorrowDecl`).
    bpRewrittenDecls = 0
    var cppCalled: Dictionary<Str, Bool> = bpCppCalled(cppTexts)
    var outPrelude: List<AstXmlNode> = List<AstXmlNode>()
    for (*pre in preludeModules) {
        outPrelude.append(bpRewriteModule(pre, *types, *reads, *statics, *valueUsed, *cppCalled, true))
    }
    var out: List<AstXmlNode> = List<AstXmlNode>()
    for (*mod in modules) {
        out.append(bpRewriteModule(mod, *types, *reads, *statics, *valueUsed, *cppCalled, false))
    }
    if (diagOn("borrow")) {
        val rewrittenDiag: Str = bpRewrittenDecls.toString()
        diagTrace("borrow", `rewrote @rewrittenDiag declaration signature(s)`)
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
