// Protocols.kt
//
// The `protocol` declarations and the `when T: P` constraints (specs/declarations.md,
// "Protocols"): the checker's view. A protocol is a named method *signature*; a type
// satisfies it when a receiver function matching that signature is in scope. The emitter
// resolves the call through the protocol's dispatch overloads (src/codegen/CgProtocol.kt),
// and this file's matcher is the one both sides read a signature with.

package sema
import compiler

import common
import linq

// One `when T: P` requirement: a type parameter and the protocol it must satisfy.
data class ProtocolConstraint(
    var param: Str,

    var protocol: Str
)

// One `param:protocol` item of a declaration's `Protocols` attribute. `slice` is the view's
// own shape: one argument takes the tail, two take a window (no `-1` sentinel).
fun semProtocolConstraintOf(item: *StrView): ProtocolConstraint {
    val at: Int = item.indexOfView(":")
    return ProtocolConstraint(item.slice(0, at).toString(), item.slice(at + 1).toString())
}

// The constraints a declaration carries, in written order (empty when it has no `when`).
// The parser writes the attribute as `param:protocol` items joined by a comma. The split is a
// lazy chain: an item that carries no `:` is filtered out before the `Str` copy the
// `ProtocolConstraint` needs, and no `List<Str>` of items is built at all.
fun semProtocolConstraints(decl: *AstXmlNode): List<ProtocolConstraint> {
    if (xmlIsEmpty(decl)) {
        return List<ProtocolConstraint>()
    }
    val raw: Str = xmlAttr(decl, AstNodeAttributeKind.Protocols)
    if (raw == "") {
        return List<ProtocolConstraint>()
    }
    return raw.splitIter(",").where((item: *StrView) -> item.indexOfView(":") >= 0).select((item: *StrView) -> semProtocolConstraintOf(item)).toList()
}

// The protocol's name (`Printable`) and the method name its signature declares
// (`protocol fun Printable<T> T.toString(): Str` is `Printable`/`toString`).
fun semProtocolName(decl: *AstXmlNode): Str {
    return xmlAttr(decl, AstNodeAttributeKind.Protocol)
}

fun semProtocolMethodName(decl: *AstXmlNode): Str {
    return xmlAttr(decl, AstNodeAttributeKind.Name)
}

// The protocol's subject: the receiver's outer type parameter (`T`), the type a satisfying
// declaration is written on. Empty when the receiver is not a type parameter (the parser
// records it as written; `checkProtocols` reports a receiver that cannot be one).
fun semProtocolSubject(decl: *AstXmlNode): Str {
    val receiver: AstXmlNode = semaReceiverPattern(decl)
    if (xmlIsEmpty(receiver)) {
        return ""
    }
    if (xmlKind(receiver) != AstNodeCategory.TypeNamed) {
        return ""
    }
    return xmlAttr(receiver, AstNodeAttributeKind.Name)
}

// One `Param` child that is a *value* parameter: the explicit `this` the receiver spelling
// adds is skipped.
fun semIsValueParam(child: *AstXmlNode, skipsReceiver: Bool): Bool {
    if (child.name != AstNodeKind.Param) {
        return false
    }
    return !(skipsReceiver && xmlAttr(child, AstNodeAttributeKind.Name) == "this")
}

// The non-receiver parameters of a declaration, in order. The walk is lazy (`where`) up to
// the `toList` drain, so the parameters the predicate rejects never reach a list.
fun semProtocolValueParams(decl: *AstXmlNode): List<AstXmlNode> {
    val skipsReceiver: Bool = !xmlIsEmpty(semExtensionReceiver(decl))
    return spanOfArray(decl.Children).iter().where((child: *AstXmlNode) -> semIsValueParam(child, skipsReceiver)).toList()
}

// Whether a type node is `Unit` or absent - "nothing returned".
fun semReturnsNothing(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return true
    }
    if (xmlKind(typeNode) != AstNodeCategory.TypeNamed) {
        return false
    }
    return xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Unit"
}

// Whether a declaration with `receiverPattern` satisfies the protocol: the receiver,
// every parameter and the return type unify with the protocol's signature, the protocol's
// own type parameters being the wildcards (so `fun Point.compareTo(other: *Point)` matches
// `fun <T> T.compareTo(other: *T)`).
fun semProtocolMatches(protocolDecl: *AstXmlNode, fn: *AstXmlNode, receiverPattern: AstXmlNode): Bool {
    val protocolParams: List<Str> = xmlTypeParamNames(protocolDecl)
    val subjectPattern: AstXmlNode = semaReceiverPattern(protocolDecl)
    val protocolArgs: List<AstXmlNode> = semProtocolValueParams(protocolDecl)
    val fnArgs: List<AstXmlNode> = semProtocolValueParams(fn)
    if (protocolArgs.size() != fnArgs.size()) {
        return false
    }
    var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
    if (!semBindTypes(subjectPattern, receiverPattern, protocolParams, bindings)) {
        return false
    }
    var i: Int = 0
    while (i < protocolArgs.size()) {
        val pattern: *AstXmlNode = xmlChildPtr(protocolArgs[i], AstNodeKind.Type)
        val actual: *AstXmlNode = xmlChildPtr(fnArgs[i], AstNodeKind.Type)
        if (xmlIsEmpty(pattern) || xmlIsEmpty(actual)) {
            return false
        }
        if (!semBindTypes(pattern, actual, protocolParams, bindings)) {
            return false
        }
        i = i + 1
    }
    val protocolRet: *AstXmlNode = xmlChildPtr(protocolDecl, AstNodeKind.ReturnType)
    val fnRet: *AstXmlNode = xmlChildPtr(fn, AstNodeKind.ReturnType)
    if (semReturnsNothing(protocolRet)) {
        return semReturnsNothing(fnRet)
    }
    if (xmlIsEmpty(fnRet)) {
        return false
    }
    return semBindTypes(protocolRet, fnRet, protocolParams, bindings)
}

// The protocol's signature as a reader sees it, for a diagnostic that has to name the
// missing declaration: `fun T.countItems(): Int`.
fun semProtocolSignatureText(protocolDecl: *AstXmlNode): Str {
    var out: Str = "fun "
    val receiver: AstXmlNode = semaReceiverPattern(protocolDecl)
    out = out + semaTypeText(receiver)
    out = out + "."
    out = out + semProtocolMethodName(protocolDecl)
    out = out + "("
    val params: List<AstXmlNode> = semProtocolValueParams(protocolDecl)
    var i: Int = 0
    while (i < params.size()) {
        if (i > 0) {
            out = out + ", "
        }
        val name: Str = xmlAttr(params[i], AstNodeAttributeKind.Name)
        val typeText: Str = semaTypeText(xmlChildPtr(params[i], AstNodeKind.Type))
        out = out + name + ": " + typeText
        i = i + 1
    }
    out = out + ")"
    val ret: *AstXmlNode = xmlChildPtr(protocolDecl, AstNodeKind.ReturnType)
    if (!semReturnsNothing(ret)) {
        out = out + ": " + semaTypeText(ret)
    }
    return out
}
