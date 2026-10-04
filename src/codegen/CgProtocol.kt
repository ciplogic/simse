// CgProtocol.kt
//
// Protocol dispatch (specs/declarations.md, "Protocols"): a call on a constrained type
// parameter cannot name a declaration, because the type is only known when C++ instantiates
// the function's template. The emitter writes such a call against the protocol's *dispatch
// overload set*: one overload per implementation, forwarding to the concrete function (or a
// native's symbol). C++ overload resolution then picks the overload whose receiver matches
// the instantiated `T`, so the call is direct and no vtable exists.
//
// The sets are emitted only for protocols some function constrains a parameter with, and
// each overload has the shape the implementation itself would have: a generic
// implementation's overload is a template over its own type parameters.

package codegen
import compiler

import sema
import common

// An implementation of one protocol: the collected function's index in `functions` (the
// declaration itself is reached through it, so nothing is copied).
data class CgProtocolImpl(
    var protocolName: Str,

    var fnIndex: Int
)

// Whether the declaration is a protocol: the collected declaration is a Function whose
// `Protocol` attribute names it (`common/xmlIsProtocolDecl`).
fun Emitter.protocolDecl(name: *Str): AstXmlNode {
    val decl: *AstXmlNode = this.protocols.getPtr(name)
    if (decl == null) {
        return xmlEmptyNode()
    }
    return *decl
}

// The C++ name of a protocol's dispatch overload set: the protocol's package prefix and its
// name plus `_proto_<method>`, so two protocols never share a set.
fun Emitter.protocolDispatchName(protocolDecl: *AstXmlNode): Str {
    val name: Str = semProtocolName(protocolDecl)
    val method: Str = semProtocolMethodName(protocolDecl)
    var pkg: Str = ""
    val found: *Str = this.protocolPackages.getPtr(name)
    if (found != null) {
        pkg = *found
    }
    return this.qualify(pkg, name) + "_proto_" + method
}

// The receiver pattern a collected function is written on, whichever spelling: the parsed
// `fun T.name(...)` receiver, or the explicit `fun name(this: T, ...)` first parameter. Empty
// for a plain function.
fun cgReceiverPattern(fn: *CgFn): AstXmlNode {
    if (!xmlIsEmpty(fn.receiver)) {
        return * fn.receiver
    }
    return semExtensionReceiver(*fn.decl)
}

// The non-receiver parameters of a collected function, in order: the explicit `this` the
// receiver spelling adds is skipped.
fun cgValueParams(fn: *CgFn): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    val params: List<AstXmlNode> = xmlChildren(*fn.decl, AstNodeKind.Param)
    val skipsReceiver: Bool = !xmlIsEmpty(semExtensionReceiver(*fn.decl))
    for (*param in params) {
        if (skipsReceiver && xmlAttr(param, AstNodeAttributeKind.Name) == "this") {
            continue
        }
        out.append(param)
    }
    return out
}

// Every implementation of one protocol, in function-collection order: a receiver function
// whose name and parameter count match and whose receiver, parameters and return type
// satisfy the protocol's signature (`semProtocolMatches`). A blanket implementation
// (`fun <E> E.toString()`) matches every type and has no overload of its own to write, so
// it is left to ordinary overload resolution for now.
fun Emitter.protocolImpls(protocolDecl: *AstXmlNode): List<CgProtocolImpl> {
    var out: List<CgProtocolImpl> = List<CgProtocolImpl>()
    val method: Str = semProtocolMethodName(protocolDecl)
    val arity: Int = semProtocolValueParams(protocolDecl).size()
    val named: *List<Int> = this.functionsByName.getPtr(method)
    if (named == null) {
        return out
    }
    for (index in named) {
        val fn: *CgFn = *this.functions[index]
        if (fn.paramCount != arity) {
            continue
        }
        val receiverPattern: AstXmlNode = cgReceiverPattern(fn)
        if (xmlIsEmpty(receiverPattern)) {
            continue
        }
        val receiverName: Str = this.outerTypeName(receiverPattern)
        if (xmlIsTypeParam(receiverName, fn.templateParams)) {
            continue
        }
        if (!semProtocolMatches(protocolDecl, fn.decl, receiverPattern)) {
            continue
        }
        out.append(CgProtocolImpl(semProtocolName(protocolDecl), index))
    }
    return out
}

// The protocols some declaration constrains a type parameter with: only those need a
// dispatch set written.
fun Emitter.usedProtocols(): Dictionary<Str, Bool> {
    var used: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*fn in this.functions) {
        for (*constraint in semProtocolConstraints(fn.decl)) {
            used.insert(constraint.protocol, true)
        }
    }
    return used
}

// Prelude implementations the dispatch sets reach must have their bodies emitted: a
// protocol call can instantiate a prelude type no call in the program names by itself, and
// the reached-name rule (`reachesPreludeBody`) only sees names the program writes.
fun Emitter.collectProtocolReach(): Unit {
    val used: Dictionary<Str, Bool> = this.usedProtocols()
    for (*name in used.keys()) {
        val protocolDecl: AstXmlNode = this.protocolDecl(*name)
        if (xmlIsEmpty(protocolDecl)) {
            continue
        }
        for (*impl in this.protocolImpls(protocolDecl)) {
            val fn: *CgFn = *this.functions[impl.fnIndex]
            if (fn.prelude && !fn.isNative) {
                this.referencedNames.insert(fn.name, true)
            }
        }
    }
}

// The symbol a native implementation's dispatch overload forwards to: the registered native
// extension whose receiver and arity match, or the name's symbol as it stands.
fun Emitter.cgNativeSymbol(fn: *CgFn): Str {
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(fn.name)
    if (extensions != null) {
        val receiverPattern: AstXmlNode = cgReceiverPattern(fn)
        for ((*ext, i) in extensions) {
            if (!xmlIsEmpty(ext.receiver) && ext.argCount == fn.paramCount
                && this.unifyType(this.resolveAlias(ext.receiver), receiverPattern, ext.typeParams)
            ) {
                return ext.symbol
            }
        }
    }
    val direct: Opt<Str> = this.nativeSymbols.get(fn.name)
    if (direct.hasValue()) {
        return direct.value()
    }
    return ""
}

// One dispatch overload: the implementation's own C++ signature under the protocol's
// dispatch name, forwarding to the implementation (its symbol, or its emitted name with the
// implementation's type parameters spelled explicitly).
fun Emitter.emitProtocolDispatch(impl: *CgProtocolImpl, prototypeOnly: Bool): Unit {
    val protocolDecl: AstXmlNode = this.protocolDecl(impl.protocolName)
    if (xmlIsEmpty(protocolDecl)) {
        return
    }
    val fn: *CgFn = *this.functions[impl.fnIndex]
    this.setActiveTypeParams(fn.templateParams)
    this.machineIter = false
    val receiverPattern: AstXmlNode = cgReceiverPattern(fn)
    var params: List<Str> = List<Str>()
    params.append(this.receiverParam(receiverPattern))
    var args: List<Str> = List<Str>()
    // The dispatch receiver is always the implementation's pointer shape; a native symbol
    // takes its receiver by value (or by reference), so the forwarded argument reads
    // through the pointer the caller passed.
    if (fn.isNative && !this.isHandleType(receiverPattern)) {
        args.append("(*self)")
    } else {
        args.append("self")
    }
    val valueParams: List<AstXmlNode> = cgValueParams(fn)
    for (*param in valueParams) {
        val paramName: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            return
        }
        params.append(this.type(paramType) + " " + paramName)
        args.append(paramName)
    }
    if (this.failed) {
        return
    }
    var ret: Str = "void"
    val retNode: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
    if (!xmlIsEmpty(retNode)) {
        ret = this.type(retNode)
    }
    if (this.failed) {
        return
    }
    val dispatchName: Str = this.protocolDispatchName(protocolDecl)
    val paramsText: Str = cgJoin(params, ", ")
    val signature: Str = ret + " " + dispatchName + "(" + paramsText + ")"
    // Two implementations that emit one C++ shape are one function; the first wins. The
    // comparison folds the built-in aliases (`Int`/`Int32` and `Char`/`Int8` are one C++
    // type each, src/rtl/types.hpp), so `fun toString(this: Int)` and
    // `fun toString(this: Int32)` do not write two overloads.
    val signatureKey: Str = cgFoldTypeAliases(signature)
    if (this.emittedProtocols.has(signatureKey)) {
        return
    }
    this.emittedProtocols.insert(signatureKey, true)
    val tmpl: Str = this.templateClause(fn.templateParams)
    if (prototypeOnly) {
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, signature + ";")
        return
    }
    var target: Str = ""
    if (fn.isNative) {
        target = this.cgNativeSymbol(fn)
    } else {
        target = this.qualify(fn.packageName, fn.name)
    }
    if (target == "") {
        val protocolName: Str = impl.protocolName
        this.fail(fn.decl, `unsupported: no symbol for the implementation of protocol '@protocolName'`)
        return
    }
    if (fn.templateParams.size() > 0) {
        target = target + "<" + cgJoin(fn.templateParams, ", ") + ">"
    }
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, signature + " {")
    val argsText: Str = cgJoin(args, ", ")
    if (ret == "void") {
        this.line(1, target + "(" + argsText + ");")
    } else {
        this.line(1, "return " + target + "(" + argsText + ");")
    }
    this.line(0, "}")
}

// The C++ shape of a signature with the built-in aliases folded: `Int` is `Int32` and
// `Char` is `Int8` (src/rtl/types.hpp), so two signatures that differ only in the alias
// name are one C++ function. Word-level, because the names are identifiers inside a
// rendered type text (`List<Int>`, `*Int32`).
fun cgFoldTypeAliases(text: Str): Str {
    var out: Str = ""
    val size: Int = text.size()
    var i: Int = 0
    while (i < size) {
        val start: Int = i
        val ch: Char = text[i]
        if (cgIsTypeNameChar(ch)) {
            while (i < size && cgIsTypeNameChar(text[i])) {
                i = i + 1
            }
            val word: Str = text.substr(start, i - start)
            if (word == "Int") {
                out = out + "Int32"
            } else if (word == "Char") {
                out = out + "Int8"
            } else {
                out = out + word
            }
        } else {
            while (i < size && !cgIsTypeNameChar(text[i])) {
                i = i + 1
            }
            out = out + text.substr(start, i - start)
        }
    }
    return out
}

fun cgIsTypeNameChar(ch: Char): Bool {
    if (ch >= 'a' && ch <= 'z') {
        return true
    }
    if (ch >= 'A' && ch <= 'Z') {
        return true
    }
    if (ch >= '0' && ch <= '9') {
        return true
    }
    return ch == '_'
}

// Writes the dispatch overloads of every used protocol (prototypes into the prototype
// pass's section, bodies into the body pass's).
fun Emitter.emitProtocolDispatches(prototypeOnly: Bool): Unit {
    this.emittedProtocols.clear()
    val used: Dictionary<Str, Bool> = this.usedProtocols()
    for (*name in used.keys()) {
        val protocolDecl: AstXmlNode = this.protocolDecl(*name)
        if (xmlIsEmpty(protocolDecl)) {
            continue
        }
        for (*impl in this.protocolImpls(protocolDecl)) {
            this.emitProtocolDispatch(impl, prototypeOnly)
            if (this.failed) {
                return
            }
        }
    }
}

// Reads the declaration's `when` clause into `activeConstraints`, for the body about to be
// emitted: a member call on one of these parameters resolves through the protocol.
fun Emitter.setActiveConstraints(decl: *AstXmlNode): Unit {
    this.activeConstraints.clear()
    for (*constraint in semProtocolConstraints(decl)) {
        if (!this.activeTypeParams.has(constraint.param)) {
            continue
        }
        val existing: *List<Str> = this.activeConstraints.getPtr(constraint.param)
        if (existing != null) {
            existing.append(constraint.protocol)
        } else {
            var fresh: List<Str> = List<Str>()
            fresh.append(constraint.protocol)
            this.activeConstraints.insert(constraint.param, fresh)
        }
    }
}

// The name of the current function's type parameter the receiver is, or "" when the
// receiver is not a bare constrained parameter.
fun Emitter.bareTypeParamReceiver(receiverExpr: *AstXmlNode): Str {
    val receiverType: AstXmlNode = this.inferType(receiverExpr)
    if (xmlIsEmpty(receiverType)) {
        return ""
    }
    val recv: AstXmlNode = this.pointee(receiverType)
    if (xmlKind(recv) != AstNodeCategory.TypeNamed) {
        return ""
    }
    val name: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
    if (!this.activeTypeParams.has(name)) {
        return ""
    }
    return name
}

// A member call on a constrained type parameter, written against the protocol dispatch set:
// `Printable_proto_toString(value)` for `value.toString()`. Empty when the receiver is not
// such a parameter or no protocol of it declares the method.
fun Emitter.protocolCall(receiverExpr: *AstXmlNode, name: *Str, args: *List<Str>): Str {
    val param: Str = this.bareTypeParamReceiver(receiverExpr)
    if (param == "") {
        return ""
    }
    val constraints: *List<Str> = this.activeConstraints.getPtr(param)
    if (constraints == null) {
        return ""
    }
    for (*protocolName in constraints) {
        val protocolDecl: AstXmlNode = this.protocolDecl(*protocolName)
        if (xmlIsEmpty(protocolDecl)) {
            continue
        }
        if (xmlAttr(protocolDecl, AstNodeAttributeKind.Name) != name) {
            continue
        }
        if (xmlCount(protocolDecl, AstNodeKind.Param) != args.size()) {
            continue
        }
        val dispatch: Str = this.protocolDispatchName(protocolDecl)
        val receiverArg: Str = this.receiverArg(semaReceiverPattern(protocolDecl), receiverExpr)
        var out: Str = dispatch + "(" + receiverArg
        for (*arg in args) {
            out = out + ", " + * arg
        }
        out = out + ")"
        return out
    }
    return ""
}
