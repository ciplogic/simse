// LambdaInfer.kt
//
// The parameter types a lambda's own body can prove for the parameters it omitted - the
// `val f = { s -> ... }` shape, where no expected callable type and no annotation names them
// (impl_specs/linear-lowering.md's last recorded gap). Three constraint sites, each a refusal
// rather than a guess, because a Simse closure is monomorphic: it cannot stand for a
// polymorphic function, so an ambiguous parameter stays untyped and the emitter's own
// diagnostic answers for it.
//
//   - *pass-through*: the parameter is argument `j` of a plain call, and every arity-matching
//     non-extension declaration of the callee agrees on that parameter's type;
//   - *a member call's receiver*: `p.m(args)` where every non-native method named `m` that
//     takes those arguments agrees on its receiver - the argument types this round knows bind
//     the method's own type parameters (`xs.append(3)` is `List<Int>`'s), and a type parameter
//     left unbound refuses;
//   - *a field selector*: `p.f` where exactly one non-generic data class in the facts declares
//     a field `f`.
//
// The sites are iterated to a fixed point over the body with the parameters already proved in
// scope: one parameter's proof is another site's argument type. A proved parameter a later
// round's site disagrees with is withdrawn and dead - the join of the sites the body actually
// has is the answer, never a subset of it.

package sema
import compiler

import common

// One parameter's join state across a round's sites: the type every site has agreed on, and
// the two refusals - a site disagreed this round, or one disagreed with a type an earlier
// round had proved (dead: it can never come back).
data class SemLambdaParam(
    var agreed: AstXmlNode,
    var refused: Bool,
    var dead: Bool
)

// The index a parameter name occupies, or -1.
fun semLambdaSlotOf(paramNames: *List<Str>, name: *Str): Int {
    var i: Int = 0
    while (i < paramNames.size()) {
        if (paramNames[i] == *name) {
            return i
        }
        i++
    }
    return -1
}

// The type a constraint site sees for one argument: a literal's own, or a name the round
// already knows (an annotated parameter, a proved one, an enclosing capture). Anything else -
// a local the walker does not infer, a nested expression - is unknown, and a site that needs
// it refuses.
fun semLambdaArgType(argNode: *AstXmlNode, known: *Dictionary<Str, AstXmlNode>): AstXmlNode {
    val kind: AstNodeCategory = xmlKind(argNode)
    when (kind) {
        AstNodeCategory.ExprIntLit -> {
            return semNamedType("Int")
        }

        AstNodeCategory.ExprFloatLit -> {
            return semNamedType("Float64")
        }

        AstNodeCategory.ExprBoolLit -> {
            return semNamedType("Bool")
        }

        AstNodeCategory.ExprCharLit -> {
            return semNamedType("Char")
        }

        AstNodeCategory.ExprStrLit -> {
            return semNamedType("Str")
        }

        AstNodeCategory.ExprName -> {
            val found: *AstXmlNode = known.getPtr(xmlAttr(argNode, AstNodeAttributeKind.Name))
            if (found != null) {
                return * found
            }
            return xmlEmptyNode()
        }
    }
    return xmlEmptyNode()
}

// One site's candidate joined into a parameter's state: empty candidates change nothing, the
// first stands, and a disagreement refuses - dead, when the parameter was already proved.
fun semLambdaJoin(params: *List<SemLambdaParam>, slot: Int, candidate: AstXmlNode): Unit {
    if (slot < 0 || slot >= params.size()) {
        return
    }
    // Written through the index: a data class element read out of a list is a copy, and a
    // mutation of the copy would vanish.
    if (params[slot].dead || xmlIsEmpty(candidate)) {
        return
    }
    if (xmlIsEmpty(params[slot].agreed)) {
        params[slot].agreed = candidate
        return
    }
    if (semaTypeText(params[slot].agreed) != semaTypeText(candidate)) {
        params[slot].refused = true
    }
}

// Whether a type still names any of `names`.
fun semTypeMentionsAny(typeNode: *AstXmlNode, names: *List<Str>): Bool {
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        var i: Int = 0
        while (i < names.size()) {
            if (names[i] == name) {
                return true
            }
            i++
        }
    }
    for (*child in typeNode.Children) {
        if (semTypeMentionsAny(child, names)) {
            return true
        }
    }
    return false
}

// The receiver a member call's candidates agree on: every declaration named `name` that is a
// member - a method with its receiver, or a `this:`-parameter native extension - takes
// `argNodes.size()` arguments, binds its type parameters from the argument types this round
// knows, and its receiver - so substituted - must agree across them all. A receiver still
// naming an unbound type parameter refuses (the call site alone cannot spell it).
fun semLambdaReceiverOf(
    facts: *SemFacts, name: *Str, argNodes: *List<AstXmlNode>, known: *Dictionary<Str, AstXmlNode>
): AstXmlNode {
    val named: *List<Int> = facts.functionsByName.getPtr(name)
    if (named == null) {
        return xmlEmptyNode()
    }
    var argTypes: List<AstXmlNode> = List<AstXmlNode>()
    for (argNode in argNodes) {
        argTypes.append(semLambdaArgType(argNode, known))
    }
    var agreed: AstXmlNode = xmlEmptyNode()
    for (i in named) {
        val fn: *SemFnFact = *facts.functions[i]
        var pattern: AstXmlNode = fn.receiver
        if (xmlIsEmpty(pattern)) {
            if (!fn.isExtension) {
                continue
            }
            pattern = semExtensionReceiver(fn.decl)
        }
        if (xmlIsEmpty(pattern) || fn.paramCount != argNodes.size()) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (fn.templateParams.size() > 0) {
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val receiver: AstXmlNode = semSubstitute(pattern, bindings, fn.templateParams)
        if (xmlIsEmpty(receiver) || semTypeMentionsAny(receiver, fn.templateParams)) {
            return xmlEmptyNode()
        }
        if (xmlIsEmpty(agreed)) {
            agreed = receiver
        } else if (semaTypeText(agreed) != semaTypeText(receiver)) {
            return xmlEmptyNode()
        }
    }
    return semLambdaValueParam(agreed)
}

// The parameter type a closure should *take* for a constraint the callee spells as a borrow:
// a `*T` parameter of the callee receives the address of a value, so the closure takes the
// value `T` and the body's own call borrows its storage - the same shape an un-borrowed
// declaration has, and the one a literal argument at the closure's call converts to. A raw
// pointer (`*Unit`) keeps itself: its pointee is not a value.
fun semLambdaValueParam(typeNode: AstXmlNode): AstXmlNode {
    if (xmlKind(typeNode) != AstNodeCategory.TypePointer) {
        return typeNode
    }
    val inner: AstXmlNode = xmlChild(typeNode, AstNodeKind.Inner)
    if (xmlIsEmpty(inner) || xmlIsRawPtrType(typeNode)) {
        return typeNode
    }
    return inner
}

// The type every arity-matching non-extension declaration of `name` agrees on for parameter
// `at`, with the declaration's type parameters bound from the argument types this round knows.
// A parameter that stays unbound, or candidates that disagree, refuses.
fun semLambdaParamOf(
    facts: *SemFacts, name: *Str, at: Int, argNodes: *List<AstXmlNode>,
    known: *Dictionary<Str, AstXmlNode>
): AstXmlNode {
    val named: *List<Int> = facts.functionsByName.getPtr(name)
    if (named == null) {
        return xmlEmptyNode()
    }
    var argTypes: List<AstXmlNode> = List<AstXmlNode>()
    for (argNode in argNodes) {
        argTypes.append(semLambdaArgType(argNode, known))
    }
    var agreed: AstXmlNode = xmlEmptyNode()
    var considered: Int = 0
    for (i in named) {
        val fn: *SemFnFact = *facts.functions[i]
        // A `native fun` extension has no recorded receiver but is still a member: a plain
        // call must not reach it, the rule `functionReturn` applies.
        if (fn.isExtension && xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.paramCount != argNodes.size()) {
            continue
        }
        considered++
        val declParams: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
        if (at < 0 || at >= declParams.size()) {
            return xmlEmptyNode()
        }
        val paramType: *AstXmlNode = xmlChildPtr(declParams[at], AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            return xmlEmptyNode()
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (fn.templateParams.size() > 0) {
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val bound: AstXmlNode = semSubstitute(paramType, bindings, fn.templateParams)
        if (xmlIsEmpty(bound) || semTypeMentionsAny(bound, fn.templateParams)) {
            return xmlEmptyNode()
        }
        if (xmlIsEmpty(agreed)) {
            agreed = bound
        } else if (semaTypeText(agreed) != semaTypeText(bound)) {
            return xmlEmptyNode()
        }
    }
    if (considered == 0) {
        return xmlEmptyNode()
    }
    return semLambdaValueParam(agreed)
}

// A type's own name with every `typealias` in the way walked (`StrView` is `Span<Char>`;
// a generic alias carries its arguments into the target, `Row<Int>` is `List<Int>`), the
// free-function shape of `semResolveTypeAlias` for the walker, which has facts but no
// `SemInfer`.
fun semLambdaResolveAlias(facts: *SemFacts, typeNode: AstXmlNode): AstXmlNode {
    return semResolveTypeAlias(typeNode, facts.types)
}

// The type every receiver-matching non-native method named `name` agrees on for parameter
// `at` of a *member* call: the receiver the round knows binds the method's own parameters,
// then the argument types it knows. The twin of `semLambdaReceiverOf` for the argument side
// of the same call - and refuses the same ways.
fun semLambdaMemberParamOf(
    facts: *SemFacts, name: *Str, at: Int, recvType: AstXmlNode, argNodes: *List<AstXmlNode>,
    known: *Dictionary<Str, AstXmlNode>
): AstXmlNode {
    val named: *List<Int> = facts.functionsByName.getPtr(name)
    if (named == null || at < 0) {
        return xmlEmptyNode()
    }
    val recv: AstXmlNode = semLambdaResolveAlias(facts, semPointee(recvType))
    if (xmlIsEmpty(recv)) {
        return xmlEmptyNode()
    }
    var argTypes: List<AstXmlNode> = List<AstXmlNode>()
    for (argNode in argNodes) {
        argTypes.append(semLambdaArgType(argNode, known))
    }
    var agreed: AstXmlNode = xmlEmptyNode()
    for (i in named) {
        val fn: *SemFnFact = *facts.functions[i]
        var pattern: AstXmlNode = fn.receiver
        var offset: Int = 0
        if (xmlIsEmpty(pattern)) {
            if (!fn.isExtension) {
                continue
            }
            pattern = semExtensionReceiver(fn.decl)
            offset = semReceiverParams(fn.decl)
        }
        if (xmlIsEmpty(pattern) || fn.paramCount != argNodes.size()) {
            continue
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        if (!semBindTypes(semLambdaResolveAlias(facts, pattern), recv, fn.templateParams, bindings)) {
            continue
        }
        if (fn.templateParams.size() > 0) {
            semBindCallArgs(fn.decl, xmlEmptyNode(), *argTypes, *fn.templateParams, *bindings)
        }
        val declParams: List<AstXmlNode> = xmlChildren(fn.decl, AstNodeKind.Param)
        if (at + offset >= declParams.size()) {
            return xmlEmptyNode()
        }
        val paramType: *AstXmlNode = xmlChildPtr(declParams[at + offset], AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            return xmlEmptyNode()
        }
        val bound: AstXmlNode = semSubstitute(paramType, bindings, fn.templateParams)
        if (xmlIsEmpty(bound) || semTypeMentionsAny(bound, fn.templateParams)) {
            return xmlEmptyNode()
        }
        if (xmlIsEmpty(agreed)) {
            agreed = bound
        } else if (semaTypeText(agreed) != semaTypeText(bound)) {
            return xmlEmptyNode()
        }
    }
    return semLambdaValueParam(agreed)
}

// The one class that declares a field `name`: a *non-generic* data class only - a generic
// owner's type arguments are not spelled by the field read, so it refuses.
fun semLambdaFieldOwner(facts: *SemFacts, name: *Str): AstXmlNode {
    var agreed: Str = ""
    for (*typeName in facts.types.keys()) {
        val decl: *AstXmlNode = facts.types.getPtr(*typeName)
        if (decl == null || ( * decl).name != AstNodeKind.DataClass) {
            continue
        }
        if (xmlTypeParamNames(*decl).size() > 0) {
            continue
        }
        if (xmlIsEmpty(semFindField(*facts.types, *decl, name))) {
            continue
        }
        if (agreed == "") {
            agreed = *typeName
        } else {
            // Two owners: the read alone cannot pick one.
            return xmlEmptyNode()
        }
    }
    if (agreed == "") {
        return xmlEmptyNode()
    }
    return semNamedType(*agreed)
}

// One walk of one statement: every constraint site in it offers its candidates into `params`,
// against the types `known` this round can already see. A call's arguments are walked but its
// callee is not (the callee's own sites are the call's); a nested lambda is its own frame.
fun semLambdaWalk(
    node: *AstXmlNode, paramNames: *List<Str>, params: *List<SemLambdaParam>,
    known: *Dictionary<Str, AstXmlNode>, facts: *SemFacts
): Unit {
    if (xmlKind(node) == AstNodeCategory.ExprLambda) {
        return
    }
    val kind: AstNodeCategory = xmlKind(node)
    if (kind == AstNodeCategory.ExprCall) {
        val callee: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Callee)
        val argNodes: List<AstXmlNode> = xmlChildren(node, AstNodeKind.Arg)
        if (xmlKind(callee) == AstNodeCategory.ExprMember) {
            // A member call on a parameter: the receiver the candidates agree on. On a *known*
            // receiver: each argument the round does not know is constrained by the method the
            // receiver picks - the argument side of the same call.
            val receiver: *AstXmlNode = xmlChildPtr(callee, AstNodeKind.Receiver)
            if (xmlKind(receiver) == AstNodeCategory.ExprName) {
                val name: Str = xmlAttr(receiver, AstNodeAttributeKind.Name)
                val calleeName: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                val slot: Int = semLambdaSlotOf(paramNames, *name)
                if (slot >= 0 && xmlIsEmpty(params[slot].agreed)) {
                    semLambdaJoin(
                        params, slot, semLambdaReceiverOf(facts, *calleeName, *argNodes, known)
                    )
                } else {
                    val recvType: AstXmlNode = semLambdaArgType(receiver, known)
                    if (!xmlIsEmpty(recvType)) {
                        var at: Int = 0
                        for (*argNode in argNodes) {
                            if (xmlKind(argNode) == AstNodeCategory.ExprName) {
                                val argName: Str = xmlAttr(argNode, AstNodeAttributeKind.Name)
                                val argSlot: Int = semLambdaSlotOf(paramNames, *argName)
                                if (argSlot >= 0 && xmlIsEmpty(params[argSlot].agreed)) {
                                    semLambdaJoin(
                                        params, argSlot,
                                        semLambdaMemberParamOf(
                                            facts, *calleeName, at, recvType, *argNodes, known
                                        )
                                    )
                                }
                            }
                            at++
                        }
                    }
                }
            }
        } else if (xmlKind(callee) == AstNodeCategory.ExprName) {
            // A plain call's arguments: the parameter type the callee's declarations agree on.
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            var at: Int = 0
            for (*argNode in argNodes) {
                if (xmlKind(argNode) == AstNodeCategory.ExprName) {
                    val argName: Str = xmlAttr(argNode, AstNodeAttributeKind.Name)
                    semLambdaJoin(
                        params, semLambdaSlotOf(paramNames, *argName),
                        semLambdaParamOf(facts, *name, at, *argNodes, known)
                    )
                }
                at++
            }
        }
        for (*argNode in argNodes) {
            semLambdaWalk(argNode, paramNames, params, known, facts)
        }
        return
    }
    if (kind == AstNodeCategory.ExprMember) {
        // A field read on a parameter: the one class that owns the field.
        val receiver: *AstXmlNode = xmlChildPtr(node, AstNodeKind.Receiver)
        if (xmlKind(receiver) == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(receiver, AstNodeAttributeKind.Name)
            val fieldName: Str = xmlAttr(node, AstNodeAttributeKind.Name)
            semLambdaJoin(
                params, semLambdaSlotOf(paramNames, *name),
                semLambdaFieldOwner(facts, *fieldName)
            )
        }
    }
    for (*child in node.Children) {
        semLambdaWalk(child, paramNames, params, known, facts)
    }
}

// The entry point (`IlExtractor.lambdaOf`): fills `paramTypes[i]` in place for every parameter
// the body proves, leaving the rest as they were. `known` seeds the first round with the types
// the enclosing frame holds for the names the body reads (its captures).
fun semInferLambdaParams(
    body: *List<AstXmlNode>, paramNames: *List<Str>, paramTypes: *List<AstXmlNode>,
    known: *Dictionary<Str, AstXmlNode>, facts: *SemFacts
): Unit {
    var params: List<SemLambdaParam> = List<SemLambdaParam>()
    var i: Int = 0
    while (i < paramNames.size()) {
        var agreed: AstXmlNode = xmlEmptyNode()
        if (i < paramTypes.size() && !xmlIsEmpty(paramTypes[i])) {
            agreed = paramTypes[i]
        }
        params.append(SemLambdaParam(agreed, false, false))
        i++
    }
    var rounds: Int = 0
    var changed: Bool = true
    while (changed && rounds < 8) {
        changed = false
        rounds++
        i = 0
        while (i < params.size()) {
            params[i].refused = false
            i++
        }
        for (*stmt in body) {
            semLambdaWalk(stmt, paramNames, *params, known, facts)
        }
        i = 0
        while (i < params.size()) {
            if (params[i].refused) {
                if (!xmlIsEmpty(paramTypes[i])) {
                    // A proved parameter a later site disputes: withdrawn, and dead - the
                    // body's sites join to a disagreement, not to a prefix of themselves.
                    paramTypes[i] = xmlEmptyNode()
                    known.remove(paramNames[i])
                    params[i].agreed = xmlEmptyNode()
                    params[i].dead = true
                    changed = true
                }
                i++
                continue
            }
            if (!params[i].dead && !xmlIsEmpty(params[i].agreed) && xmlIsEmpty(paramTypes[i])) {
                paramTypes[i] = params[i].agreed
                known.insert(paramNames[i], params[i].agreed)
                changed = true
            }
            i++
        }
    }
}
