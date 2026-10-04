// EscapeParams.kt
//
// The escape (retention) analysis the stack promotion reads (`linear/PromoteRefs.kt`): for every
// function and method, which parameters - or receiver, at index 0 - the callee may *retain*. A
// parameter the callee never retains is safe to hand a raw pointer to, so the IL pass may keep a
// `&T` local on the stack across the call; one the callee hands out (`return p`, `this.saved =
// p`, a box, a capture, or a call to a parameter that does) keeps the box.
//
// The shape is the whole-program, before-sema one `BorrowParams.kt` has: a name-keyed fixpoint
// grown from *below*, so a recursive cycle is never assumed clean and a name left out is simply
// not trusted. The property differs - this pass tracks *retention*, not writes, so a mutating
// helper is clean (`impl_specs/escape-analysis.md`, "The escape property"). A body-less
// declaration is trusted only by a mark (`borrow`/`data`, or a `union class`'s generated
// accessors); `print`/`println`, which the emitter spells with no declaration at all, are mapped
// as borrows (`epBuiltinBorrow`).
//
// Three states per (name, parameter index):
//   Clean    - every declaration proved it never retains the value;
//   Escapes  - some occurrence hands it out (a return, a field/index/static store, `&p`, a
//              capture, a construction's field) or passes it to a parameter that Escapes;
//   Unknown  - everything else: a call the table has no proof for, a body-less declaration
//              with no mark, a cycle. The consumer keeps its structural rules for Unknown.

package parser
import compiler

import common

enum class EpKind {
    Unknown,
    Clean,
    Escapes
}

// The context one mention of a parameter is reached in by `EpScan.walk`.
enum class EpCtx {
    // A read: the value is copied or viewed, the parameter is not handed out.
    Read,

    // A position that hands the parameter's *value* out: a return, a yield.
    Escape,

    // An address is derived from the expression: `&e`, `*e` as a value. A *member* under
    // this context hands out its base's storage (`&p.f`), so it propagates; under the
    // others it is the field's own copy.
    Addr,

    // A value copied into a place: a *handle* copy is an escape, a value is not.
    Store,

    // A call argument or receiver: the callee's own parameter decides.
    CallArg
}

// The result: name -> per-index kind. `epKindAt` is the consumer's view.
var epTable: Dictionary<Str, List<EpKind>> = Dictionary<Str, List<EpKind>>()

// The fixpoint's own state, before `epTable` is built from the two.
var epEscapes: Dictionary<Str, List<Bool>> = Dictionary<Str, List<Bool>>()
var epClean: Dictionary<Str, List<Bool>> = Dictionary<Str, List<Bool>>()

// What the analysis knows about `name`'s parameter `index` (index 0 is a method's receiver).
fun epKindAt(name: *Str, index: Int): EpKind {
    val row: *List<EpKind> = epTable.getPtr(*name)
    if (row == null || index < 0 || index >= row.size()) {
        return EpKind.Unknown
    }
    return (*row)[index]
}

// The builtins the emitter spells with no declaration: `print`/`println` take the value by
// `const T&` and write it where it stands (`CgCall.kt`), so the argument is a borrow. This is
// the mapping the analysis applies where a declared function would carry a `borrow` mark.
// `--no-escape` turns it off with the rest of the optimization.
fun epBuiltinBorrow(name: *Str): Bool {
    if (epNoEscape()) {
        return false
    }
    return *name == "print" || *name == "println"
}

// `--showEscape`: one line per name the analysis weighed, `<name> <kind>,<kind>...`. The view
// that turns a question about the table into a histogram (`grep 'escape .*Escapes'`), like
// `--showBorrow`.
var epShowFlag: Bool = false
var epReport: List<Str> = List<Str>()

// `--no-escape`: the analysis still runs only for the dump, and neither it nor the `print`
// mapping feeds the promotion - the emitted C++ is what the structural rules alone allow. The
// escape hatch, and how the A/B of the optimization is measured (`tools/_bench_ab.mjs`).
var epNoEscapeFlag: Bool = false

fun epShow(): Bool {
    return epShowFlag
}

fun epSetShow(value: Bool): Unit {
    epShowFlag = value
}

fun epNoEscape(): Bool {
    return epNoEscapeFlag
}

fun epSetNoEscape(value: Bool): Unit {
    epNoEscapeFlag = value
}

fun epNote(line: Str): Unit {
    epReport.append(line)
}

fun epReportLines(): List<Str> {
    return epReport
}

// The kind under construction, from the two flag sets (Escapes wins: a name that was clean in
// an early round and turned escaping later is escaping).
fun epKindNow(name: *Str, index: Int): EpKind {
    val esc: *List<Bool> = epEscapes.getPtr(*name)
    if (esc != null && index >= 0 && index < esc.size() && (*esc)[index]) {
        return EpKind.Escapes
    }
    val cln: *List<Bool> = epClean.getPtr(*name)
    if (cln != null && index >= 0 && index < cln.size() && (*cln)[index]) {
        return EpKind.Clean
    }
    return EpKind.Unknown
}

// One name's row, materialised on first touch.
fun epRow(rows: *Dictionary<Str, List<Bool>>, name: *Str): *List<Bool> {
    var row: *List<Bool> = rows.getPtr(*name)
    if (row == null) {
        rows.insert(*name, List<Bool>())
        row = rows.getPtr(*name)
    }
    return row
}

// Record one vote for (name, index); the row grows with false as indices appear.
fun epMarkOr(rows: *Dictionary<Str, List<Bool>>, name: *Str, index: Int): Unit {
    val row: *List<Bool> = epRow(rows, name)
    while (row.size() <= index) {
        row.append(false)
    }
    row[index] = true
}

fun epFlag(rows: *Dictionary<Str, List<Bool>>, name: *Str, index: Int): Bool {
    val row: *List<Bool> = rows.getPtr(*name)
    if (row == null || index < 0 || index >= row.size()) {
        return false
    }
    return row[index]
}

// Set the flag; answers whether it changed (the fixpoint's stop test).
fun epSetFlag(rows: *Dictionary<Str, List<Bool>>, name: *Str, index: Int): Bool {
    val row: *List<Bool> = epRow(rows, name)
    while (row.size() <= index) {
        row.append(false)
    }
    if (row[index]) {
        return false
    }
    row[index] = true
    return true
}

// Whether a declared type is a handle (`&T`, `*T`, the `PList` alias) - what a value copy of is
// a second owner. A bare type parameter may be instantiated with one, so it counts.
fun epHandleType(typeNode: *AstXmlNode, typeParams: List<Str>): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
        return true
    }
    if (kind == AstNodeCategory.TypeGeneric
        && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "PList"
    ) {
        return true
    }
    if (kind == AstNodeCategory.TypeNamed) {
        val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        for (*tp in typeParams) {
            if (*tp == name) {
                return true
            }
        }
    }
    return false
}

// Whether a call's callee is a construction: a declared class by name or generic name, a
// built-in conversion (`Str(x)`), or the `Opt`/`Res` arm builders. An explicitly instantiated
// function (`peek<Int>(x)`) is not one.
fun epIsConstruct(callee: *AstXmlNode, types: *Dictionary<Str, Bool>): Bool {
    val ck: AstNodeCategory = xmlKind(callee)
    if (ck == AstNodeCategory.ExprGenericName || ck == AstNodeCategory.ExprName) {
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

// One declaration's body scan: the parameter names under analysis, their declared types, and
// the per-name verdicts the walk fills in.
// One call-argument mention whose vote depends on the current table: the parameter named, and
// the callee position `epKindNow` reads. A round re-evaluates these instead of walking the body
// again (`EpDecl.scanEvents`).
data class EpEvent(
    var name: Str,
    var callee: Str,
    var at: Int
)

data class EpScan(
    var params: Dictionary<Str, Bool>,
    var handles: Dictionary<Str, Bool>,
    var types: *Dictionary<Str, Bool>,
    var typeParams: List<Str>,
    var escapes: Dictionary<Str, Bool>,
    var events: List<EpEvent>
) {
    // One mention of `name` reached in `ctx`. `callee`/`at` say which call position, for the
    // CallArg context; `at` is the declared parameter index (a method's receiver is 0).
    fun mention(name: *Str, ctx: EpCtx, callee: *Str, at: Int): Unit {
        if (!this.params.has(*name)) {
            return
        }
        if (ctx == EpCtx.Escape || ctx == EpCtx.Addr) {
            this.escapes.insert(*name, true)
            return
        }
        if (ctx == EpCtx.Store) {
            if (this.handles.has(*name)) {
                this.escapes.insert(*name, true)
            }
            return
        }
        if (ctx != EpCtx.CallArg) {
            return
        }
        if (epBuiltinBorrow(callee)) {
            return
        }
        this.events.append(EpEvent(*name, *callee, at))
    }

    // Every mention under a lambda: the closure stores the capture, so all of them escape.
    fun capture(node: *AstXmlNode): Unit {
        if (xmlKind(node) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            if (this.params.has(*name)) {
                this.escapes.insert(name, true)
            }
        }
        for (*child in node.Children) {
            this.capture(child)
        }
    }

    fun call(node: *AstXmlNode): Unit {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val ck: AstNodeCategory = xmlKind(callee)
        if (ck == AstNodeCategory.ExprMember) {
            val mname: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            val recv: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (epIsConstruct(callee, this.types)) {
                // `Opt<T>.some(x)` and friends: the builder stores the value in its arm.
                this.walk(recv, EpCtx.Read, "", -1)
                for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
                    this.walk(arg, EpCtx.Store, "", -1)
                }
                return
            }
            // A method call: the receiver is index 0, the arguments follow it.
            this.walk(recv, EpCtx.CallArg, mname, 0)
            var i: Int = 0
            for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
                this.walk(arg, EpCtx.CallArg, mname, i + 1)
                i = i + 1
            }
            return
        }
        if (ck == AstNodeCategory.ExprName || ck == AstNodeCategory.ExprGenericName) {
            val fname: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (epIsConstruct(callee, this.types)) {
                var actx: EpCtx = EpCtx.Store
                if (!this.types.has(fname)) {
                    // A built-in conversion (`Str(x)`, `List<Int>(n)`) copies what it is given.
                    actx = EpCtx.Read
                }
                for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
                    this.walk(arg, actx, "", -1)
                }
                return
            }
            // A free function: the arguments are indices 0..n-1.
            var i: Int = 0
            for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
                this.walk(arg, EpCtx.CallArg, fname, i)
                i = i + 1
            }
            return
        }
        // The callee is a value (an immediately-invoked lambda, a callable local): no name to
        // look up, so every argument is unproved.
        this.walk(callee, EpCtx.Read, "", -1)
        var i: Int = 0
        for (*arg in xmlChildren(node, AstNodeKind.Arg)) {
            this.walk(arg, EpCtx.CallArg, "(a value call)", i)
            i = i + 1
        }
    }

    fun walk(node: *AstXmlNode, ctx: EpCtx, callee: Str, at: Int): Unit {
        if (xmlIsEmpty(node)) {
            return
        }
        val kind: AstNodeCategory = xmlKind(node)
        if (kind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            this.mention(*name, ctx, *callee, at)
            return
        }
        if (kind == AstNodeCategory.ExprLambda) {
            this.capture(node)
            return
        }
        if (kind == AstNodeCategory.ExprRef) {
            this.walk(xmlChildPtr(node, AstNodeKind.Operand), EpCtx.Addr, "", -1)
            return
        }
        if (kind == AstNodeCategory.ExprDeref) {
            // `*p` as a *value* is the address (or a `&T`'s pointer); a store through it is a
            // `StmtAssign`, which handles the target itself.
            this.walk(xmlChildPtr(node, AstNodeKind.Operand), EpCtx.Addr, "", -1)
            return
        }
        if (kind == AstNodeCategory.ExprMember || kind == AstNodeCategory.ExprIndex) {
            // A *read* of a field or an element is a copy - the base's storage is not handed
            // out (`return u.Value` reads the arm). An address derived from the place
            // (`&p.f`, `*p.f`) does hand the base out, so `Addr` propagates.
            var baseCtx: EpCtx = EpCtx.Read
            if (ctx == EpCtx.Addr) {
                baseCtx = EpCtx.Addr
            }
            this.walk(xmlChildPtr(node, AstNodeKind.Receiver), baseCtx, callee, at)
            if (kind == AstNodeCategory.ExprIndex) {
                this.walk(xmlChildPtr(node, AstNodeKind.Index), EpCtx.Read, "", -1)
            }
            return
        }
        if (kind == AstNodeCategory.ExprBinary || kind == AstNodeCategory.ExprUnary
            || kind == AstNodeCategory.ExprCopy
        ) {
            for (*child in node.Children) {
                this.walk(child, EpCtx.Read, "", -1)
            }
            return
        }
        if (kind == AstNodeCategory.ExprCall) {
            this.call(node)
            return
        }
        if (kind == AstNodeCategory.StmtReturn || kind == AstNodeCategory.StmtYield) {
            this.walk(xmlChildPtr(node, AstNodeKind.Value), EpCtx.Escape, "", -1)
            return
        }
        if (kind == AstNodeCategory.StmtVarDecl) {
            val declared: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Type)
            val init: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Init)
            // An inferred `var x = p` copies the handle; a value destination reads through
            // (`val s: Str = p` copies the text).
            var ictx: EpCtx = EpCtx.Store
            if (!xmlIsEmpty(declared) && !epHandleType(declared, this.typeParams)) {
                ictx = EpCtx.Read
            }
            this.walk(init, ictx, "", -1)
            return
        }
        if (kind == AstNodeCategory.StmtAssign) {
            val target: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Target)
            // An assignment's value rides `Value`; `Rhs` belongs to `ExprBinary`.
            val rhs: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Value)
            val tk: AstNodeCategory = xmlKind(target)
            if (tk == AstNodeCategory.ExprMember || tk == AstNodeCategory.ExprIndex) {
                // A write through the parameter: the base is read and written, not handed out.
                this.walk(xmlChildPtr(target, AstNodeKind.Receiver), EpCtx.Read, "", -1)
                if (tk == AstNodeCategory.ExprIndex) {
                    this.walk(xmlChildPtr(target, AstNodeKind.Index), EpCtx.Read, "", -1)
                }
            } else if (tk == AstNodeCategory.ExprDeref) {
                this.walk(xmlChildPtr(target, AstNodeKind.Operand), EpCtx.Read, "", -1)
            } else {
                this.walk(target, EpCtx.Read, "", -1)
            }
            this.walk(rhs, EpCtx.Store, "", -1)
            return
        }
        // Everything else: a read of what the children mention.
        for (*child in node.Children) {
            this.walk(child, EpCtx.Read, "", -1)
        }
    }
}

// One declaration considered: its body (empty when body-less), the mark that trusts a
// body-less one, the parameters, their handle-ness, and the type parameters (a bare type
// parameter may be instantiated with a handle). `scanEscapes`/`scanEvents` are the body's
// table-independent scan, filled once before the fixpoint (`epPreScanDecls`).
data class EpDecl(
    var name: Str,
    var body: AstXmlNode,
    var trusted: Bool,
    var params: List<Str>,
    var handles: List<Bool>,
    var typeParams: List<Str>,
    var scanEscapes: Dictionary<Str, Bool>,
    var scanEvents: List<EpEvent>
)

fun epCollectFn(fn: *AstXmlNode, method: Bool, out: *List<EpDecl>, types: *Dictionary<Str, Bool>): Unit {
    val name: Str = xmlAttr(fn, AstNodeAttributeKind.Name)
    if (name == "main") {
        return
    }
    var typeParams: List<Str> = List<Str>()
    for (*tp in xmlChildren(fn, AstNodeKind.TypeParam)) {
        typeParams.append(xmlAttr(tp, AstNodeAttributeKind.Name))
    }
    val body: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.Body)
    var params: List<Str> = List<Str>()
    var handles: List<Bool> = List<Bool>()
    val generated: Bool = xmlAttr(fn, AstNodeAttributeKind.IsUnionGenerated) == "true"
    if (generated) {
        // A `union class`'s generated accessor: the receiver is implicit (`unionMethod` writes
        // no receiver child), and the shape is the compiler's own - it never stores it.
        params.append("this")
        handles.append(false)
    } else {
        // An extension receiver is a `Receiver` child; a class method has none and its
        // receiver *is* its class (`this` in the body). Both are index 0, as the IL spells a
        // `Method` call. An explicit-`this` declaration carries it as a `Param` instead and
        // needs no prepend.
        val recv: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.Receiver)
        if (!xmlIsEmpty(recv)) {
            params.append("this")
            handles.append(epHandleType(recv, typeParams))
        } else if (method) {
            params.append("this")
            handles.append(false)
        }
        for (*param in xmlChildren(fn, AstNodeKind.Param)) {
            params.append(xmlAttr(param, AstNodeAttributeKind.Name))
            handles.append(epHandleType(xmlChildPtr(param, AstNodeKind.Type), typeParams))
        }
    }
    out.append(
        EpDecl(
            name, *body, generated || bpMarked(fn), params, handles, typeParams,
            Dictionary<Str, Bool>(), List<EpEvent>()
        )
    )
}

fun epCollectModule(module: *AstXmlNode, out: *List<EpDecl>, types: *Dictionary<Str, Bool>): Unit {
    for (*decl in xmlDecls(module)) {
        if (decl.name == AstNodeKind.Function) {
            epCollectFn(decl, false, out, types)
        } else if (decl.name == AstNodeKind.DataClass) {
            types.insert(xmlAttr(decl, AstNodeAttributeKind.Name), true)
            for (*method in xmlChildren(decl, AstNodeKind.Function)) {
                epCollectFn(method, true, out, types)
            }
        }
    }
}

// The table-independent half of every declaration's scan, once per analysis: the mentions that
// escape by shape (an `Escape`/`Addr` context, a store into a handle, a lambda capture) and the
// call-argument mentions whose vote `epKindNow` decides. A fixpoint round then re-evaluates the
// events against the table instead of walking every body again.
fun epPreScanDecls(decls: *List<EpDecl>, types: *Dictionary<Str, Bool>): Unit {
    for (*decl in decls) {
        if (decl.params.size() == 0 || xmlIsEmpty(decl.body)) {
            continue
        }
        var params: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var handles: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
        var k: Int = 0
        while (k < decl.params.size()) {
            params.insert(decl.params[k], true)
            handles.insert(decl.params[k], decl.handles[k])
            k = k + 1
        }
        var scan: EpScan =
            EpScan(params, handles, types, decl.typeParams, Dictionary<Str, Bool>(), List<EpEvent>())
        for (*stmt in decl.body.Children) {
            scan.walk(stmt, EpCtx.Read, "", -1)
        }
        decl.scanEscapes = scan.escapes
        decl.scanEvents = scan.events
    }
}

// The whole-program analysis: collect the declarations of the prelude and the program, then
// grow the per-parameter kinds from below until nothing moves. `Unknown` everywhere is the
// start; a round's votes are applied together, so the outcome does not depend on declaration
// order.
fun epAnalyze(preludeModules: List<AstXmlNode>, modules: List<AstXmlNode>): Unit {
    epEscapes = Dictionary<Str, List<Bool>>()
    epClean = Dictionary<Str, List<Bool>>()
    epTable = Dictionary<Str, List<EpKind>>()
    if (epNoEscape()) {
        // The escape hatch: no table, so every `epKindAt` is Unknown and the promotion falls
        // back to its structural rules.
        return
    }
    var types: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var decls: List<EpDecl> = List<EpDecl>()
    for (*pre in preludeModules) {
        epCollectModule(pre, *decls, *types)
    }
    for (*mod in modules) {
        epCollectModule(mod, *decls, *types)
    }
    // One body scan per declaration, before the fixpoint: a round only re-reads the events the
    // table decides.
    epPreScanDecls(*decls, *types)
    var changed: Bool = true
    var guard: Int = 0
    while (changed && guard < 64) {
        guard = guard + 1
        changed = false

        // One round's raw votes: an escape seen, or a declaration that cannot vote clean.
        var roundEsc: Dictionary<Str, List<Bool>> = Dictionary<Str, List<Bool>>()
        var roundBad: Dictionary<Str, List<Bool>> = Dictionary<Str, List<Bool>>()
        var seen: Dictionary<Str, List<Bool>> = Dictionary<Str, List<Bool>>()
        for (*decl in decls) {
            if (decl.params.size() == 0) {
                continue
            }
            if (xmlIsEmpty(decl.body)) {
                // A body-less declaration votes only by its mark.
                var k: Int = 0
                for (pname in decl.params) {
                    epMarkOr(*seen, *decl.name, k)
                    if (!decl.trusted) {
                        epMarkOr(*roundBad, *decl.name, k)
                    }
                    k = k + 1
                }
                continue
            }
            var k: Int = 0
            for (pname in decl.params) {
                epMarkOr(*seen, *decl.name, k)
                k = k + 1
            }
            // The body's shape was scanned once (`epPreScanDecls`): the round collects the
            // events the table now classifies as escaping or unknown.
            var eventEscapes: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
            var eventUnknown: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
            for (*event in decl.scanEvents) {
                val kind: EpKind = epKindNow(event.callee, event.at)
                if (kind == EpKind.Escapes) {
                    eventEscapes.insert(event.name, true)
                } else if (kind == EpKind.Unknown) {
                    eventUnknown.insert(event.name, true)
                }
            }
            k = 0
            for (pname in decl.params) {
                if (decl.scanEscapes.has(pname) || eventEscapes.has(pname)) {
                    epMarkOr(*roundEsc, *decl.name, k)
                } else if (eventUnknown.has(pname)) {
                    epMarkOr(*roundBad, *decl.name, k)
                }
                k = k + 1
            }
        }

        var names: List<Str> = seen.keys()
        for (*name in names) {
            val row: *List<Bool> = seen.getPtr(*name)
            var k: Int = 0
            while (k < row.size()) {
                if (epFlag(*roundEsc, name, k)) {
                    if (epSetFlag(*epEscapes, name, k)) {
                        changed = true
                    }
                }
                if (!epFlag(*roundBad, name, k) && !epFlag(*epEscapes, name, k)) {
                    if (epSetFlag(*epClean, name, k)) {
                        changed = true
                    }
                }
                k = k + 1
            }
        }
    }

    // The consumer's table: the two flag sets as one kind per (name, index).
    var nameSet: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*name in epEscapes.keys()) {
        nameSet.insert(*name, true)
    }
    for (*name in epClean.keys()) {
        nameSet.insert(*name, true)
    }
    for (*name in nameSet.keys()) {
        val esc: *List<Bool> = epEscapes.getPtr(*name)
        val cln: *List<Bool> = epClean.getPtr(*name)
        var count: Int = 0
        if (esc != null) {
            count = esc.size()
        }
        if (cln != null && cln.size() > count) {
            count = cln.size()
        }
        var row: List<EpKind> = List<EpKind>()
        var k: Int = 0
        while (k < count) {
            var kind: EpKind = EpKind.Unknown
            if (esc != null && k < esc.size() && esc[k]) {
                kind = EpKind.Escapes
            } else if (cln != null && k < cln.size() && cln[k]) {
                kind = EpKind.Clean
            }
            row.append(kind)
            k = k + 1
        }
        epTable.insert(*name, row)
    }

    if (epShow()) {
        epReport = List<Str>()
        for (*name in epTable.keys()) {
            val row: *List<EpKind> = epTable.getPtr(*name)
            var text: Str = "escape " + *name + " "
            var k: Int = 0
            while (k < row.size()) {
                if (k > 0) {
                    text = text + ","
                }
                val kind: EpKind = row[k]
                if (kind == EpKind.Escapes) {
                    text = text + "Escapes"
                } else if (kind == EpKind.Clean) {
                    text = text + "Clean"
                } else {
                    text = text + "Unknown"
                }
                k = k + 1
            }
            epNote(text)
        }
    }
}
