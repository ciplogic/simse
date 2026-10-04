// TypeInfer.kt
//
// A semantic step on a *lowered* body (impl_specs/linear-lowering.md): after lowering, it
// types every declaration the lowering introduced - and any unannotated `val`/`var` - so
// the emitter never has to guess one or fall back to `auto`. It is not a reifier: a type
// parameter in scope is a good type to spell. An empty `AstXmlNode` is "no/unknown type".

package sema
import compiler

import common
import linq

fun semNamedType(name: *Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

fun semGenericType(name: *Str, args: *List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeGeneric, List<AstNodeAttribute>(), args.toArray())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// The built-in (RTL) type names: they keep their C++ spelling and need no declaration
// to be usable in a type position. The emitter calls this one list (`cgIsRtlTypeName`).
fun semIsRtlTypeName(name: *Str): Bool {
    when (name) {
        "Int", "Int8", "Int16", "Int32", "Int64" -> {
            return true
        }

        "Float32", "Float64", "Char", "Bool", "Str" -> {
            return true
        }

        "List", "Array", "RawArray", "Opt", "Res" -> {
            return true
        }

        "Dictionary", "SmallVector", "PList" -> {
            return true
        }

        "Span", "StrView" -> {
            return true
        }
    }
    return false
}

// The receiver type of a declaration that spells it as an explicit `this` first
// parameter (empty otherwise). Such a declaration is a *member*: `functionReturn`
// refuses it for a plain call (`fun find(...)` must not pick up `Str.find`).
fun semExtensionReceiver(decl: *AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(decl)) {
        return xmlEmptyNode()
    }
    for (*child in decl.Children) {
        if (child.name == AstNodeKind.Param) {
            if (xmlAttr(child, AstNodeAttributeKind.Name) != "this") {
                return xmlEmptyNode()
            }
            return xmlChild(child, AstNodeKind.Type)
        }
    }
    return xmlEmptyNode()
}

// How many leading parameters are the receiver (1 or 0); a call's arguments convert
// against the parameters after it.
fun semReceiverParams(decl: *AstXmlNode): Int {
    if (xmlIsEmpty(semExtensionReceiver(decl))) {
        return 0
    }
    return 1
}

fun semIsExtensionDecl(decl: *AstXmlNode): Bool {
    return !xmlIsEmpty(semExtensionReceiver(decl))
}

// Whether `param` is one of `decl`'s own type parameters, bare (`T`, not `List<T>`):
// the receiver, not this call, binds it.
fun semIsBareTypeParam(decl: *AstXmlNode, param: *AstXmlNode): Bool {
    if (xmlIsEmpty(decl) || xmlIsEmpty(param)) {
        return false
    }
    if (xmlKind(param) != AstNodeCategory.TypeNamed) {
        return false
    }
    val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
    for (*tp in decl.Children) {
        if (tp.name == AstNodeKind.TypeParam && xmlAttr(tp, AstNodeAttributeKind.Name) == name) {
            return true
        }
    }
    return false
}

// The node re-rooted under `role`: the emitter looks children up by role, so a type
// re-used from another position must be re-rooted (as `renameRole` does for `Inner`).
fun semReRole(node: AstXmlNode, role: AstNodeKind): AstXmlNode {
    if (node.name == role) {
        return node
    }
    var renamed: AstXmlNode = node
    renamed.name = role
    return renamed
}

fun semOne(node: *AstXmlNode): List<AstXmlNode> {
    return listOf<AstXmlNode>(node)
}

// The same node with every child whose role is `role` replaced, in order, by
// `replacements`. The lowering's `exprReplaceRole`, which this package cannot import. The
// children are read in place (`iter`): the copy this used to take was one `AstXmlNode` per
// child, attributes included, on a path the type pass walks heavily.
fun semReplaceRole(like: *AstXmlNode, role: AstNodeKind, replacements: *List<AstXmlNode>): AstXmlNode {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    var seen: Int = 0
    for (child in spanOfArray(like.Children).iter()) {
        if (child.name == role) {
            if (seen < replacements.size()) {
                kids.append(replacements[seen])
            }
            seen = seen + 1
        } else {
            kids.append(child)
        }
    }
    while (seen < replacements.size()) {
        kids.append(replacements[seen])
        seen = seen + 1
    }
    return AstXmlNode(like.name, like.kind, copy(like.attributes), kids.toArray())
}

// The declaration with a leading `Type` child, like a parsed `val x: T = ...`.
fun semWithType(decl: *AstXmlNode, typeNode: *AstXmlNode): AstXmlNode {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    kids.append(typeNode)
    for (child in spanOfArray(decl.Children).iter().where((c: *AstXmlNode) -> c.name != AstNodeKind.Type)) {
        kids.append(child)
    }
    return AstXmlNode(decl.name, decl.kind, copy(decl.attributes), kids.toArray())
}

// The pointee after stripping any number of `&`/`*` handles.
fun semPointee(typeNode: *AstXmlNode): AstXmlNode {
    var current: *AstXmlNode = typeNode
    while (!xmlIsEmpty(current)) {
        val kind: AstNodeCategory = xmlKind(current)
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
            val inner: *AstXmlNode = xmlChildPtr(current, AstNodeKind.Inner)
            if (xmlIsEmpty(inner)) {
                return current
            }
            current = inner
        } else {
            return current
        }
    }
    return current
}

// The node form of `semPointee`.
fun semPointeeOf(typeNode: *AstXmlNode): AstXmlNode {
    return semPointee(typeNode)
}

// The `List<T>` a *type* names, through handles and the `PList<T>` alias; empty when not
// a list. The *argument* side of the packing rule (`specs/functions.md`).
fun semListTypeOf(typeNode: *AstXmlNode): AstXmlNode {
    var base: AstXmlNode = semPointee(typeNode)
    // `PList<T>` is `&List<T>` spelled as one name, so it is a list too.
    if (xmlKind(base) == AstNodeCategory.TypeGeneric
        && xmlAttr(base, AstNodeAttributeKind.Name) == "PList"
        && xmlCount(base, AstNodeKind.TypeArg) == 1
    ) {
        base = semPointee(xmlChildPtr(base, AstNodeKind.TypeArg))
    }
    if (xmlKind(base) == AstNodeCategory.TypeGeneric
        && xmlAttr(base, AstNodeAttributeKind.Name) == "List"
        && xmlCount(base, AstNodeKind.TypeArg) == 1
    ) {
        return base
    }
    return xmlEmptyNode()
}

// Whether a *parameter*'s type takes packed trailing arguments: a by-value `List<T>` or a
// borrowed `*List<T>`, deliberately not the counted `&List<T>`/`PList<T>` (a packed list is
// a throwaway temporary), and not a type reached through an alias. The checker and the
// extractor share it.
fun semIsPackTarget(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return false
    }
    // The counted forms would allocate a control block for a temporary that dies in the
    // same statement - more work than the one copy a by-value parameter costs.
    if (xmlKind(typeNode) == AstNodeCategory.TypeGeneric) {
        return xmlAttr(typeNode, AstNodeAttributeKind.Name) == "List"
                && xmlCount(typeNode, AstNodeKind.TypeArg) == 1
    }
    if (xmlKind(typeNode) == AstNodeCategory.TypePointer) {
        val inner: *AstXmlNode = xmlChildPtr(typeNode, AstNodeKind.Inner)
        return xmlKind(inner) == AstNodeCategory.TypeGeneric
                && xmlAttr(inner, AstNodeAttributeKind.Name) == "List"
                && xmlCount(inner, AstNodeKind.TypeArg) == 1
    }
    return false
}

// Structural equality, used to refuse a second, different binding for a pattern
// type parameter (`Map<K, K>` against `Map<Str, Int>` is not a match).
fun semSameType(left: *AstXmlNode, right: *AstXmlNode): Bool {
    val leftKind: AstNodeCategory = xmlKind(left)
    if (leftKind != xmlKind(right)) {
        return false
    }
    when (leftKind) {
        AstNodeCategory.TypeIntLit -> {
            return xmlAttr(left, AstNodeAttributeKind.Text) == xmlAttr(right, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            return xmlAttr(left, AstNodeAttributeKind.Name) == xmlAttr(right, AstNodeAttributeKind.Name)
        }

        AstNodeCategory.TypeGeneric -> {
            if (xmlAttr(left, AstNodeAttributeKind.Name) != xmlAttr(right, AstNodeAttributeKind.Name)) {
                return false
            }
            return semSameTypeList(xmlChildren(left, AstNodeKind.TypeArg), xmlChildren(right, AstNodeKind.TypeArg))
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
            return semSameType(xmlChildPtr(left, AstNodeKind.Inner), xmlChildPtr(right, AstNodeKind.Inner))
        }
    }
    return false
}

fun semSameTypeList(left: *List<AstXmlNode>, right: *List<AstXmlNode>): Bool {
    if (left.size() != right.size()) {
        return false
    }
    var i: Int = 0
    while (i < left.size()) {
        if (!semSameType(left[i], right[i])) {
            return false
        }
        i = i + 1
    }
    return true
}

// Records `name := type`, refusing a second, different binding. An unbound pattern type
// parameter is not an error here: the caller finds out when substitution leaves nothing.
fun semBindOne(bindings: *Dictionary<Str, AstXmlNode>, name: *Str, typeNode: *AstXmlNode): Bool {
    val existing: *AstXmlNode = bindings.getPtr(name)
    if (existing == null) {
        bindings.insert(name, typeNode)
        return true
    }
    return semSameType(existing, typeNode)
}

// Whether a receiver pattern matches an actual type, with the pattern's type parameters
// matching anything. It answers "does an `iter` take this receiver?" for the `for` check
// (`Sema.kt`).
fun semUnifyType(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: *AstXmlNode = actual
    val patternKind: AstNodeCategory = xmlKind(pattern)
    if (patternKind != AstNodeCategory.TypeReference && patternKind != AstNodeCategory.TypePointer) {
        while (true) {
            val actualKind: AstNodeCategory = xmlKind(actualPtr)
            if (actualKind == AstNodeCategory.TypeReference || actualKind == AstNodeCategory.TypePointer) {
                val inner: *AstXmlNode = xmlChildPtr(actualPtr, AstNodeKind.Inner)
                if (xmlIsEmpty(inner)) {
                    break
                }
                actualPtr = inner
            } else {
                break
            }
        }
    }
    val ak: AstNodeCategory = xmlKind(actualPtr)
    when (patternKind) {
        AstNodeCategory.TypeIntLit -> {
            return ak == AstNodeCategory.TypeIntLit && xmlAttr(actualPtr, AstNodeAttributeKind.Text) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Text
            )
        }

        AstNodeCategory.TypeNamed -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            return ak == AstNodeCategory.TypeNamed && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(
                pattern,
                AstNodeAttributeKind.Name
            )
        }

        AstNodeCategory.TypeGeneric -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return true
            }
            if (ak != AstNodeCategory.TypeGeneric) {
                return false
            }
            val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
            val actualName: Str = xmlAttr(actualPtr, AstNodeAttributeKind.Name)
            // `PList<T>` is the alias of `&List<T>`; match it against a `List<T>` pattern.
            if (actualName != patternName
                && !(patternName == "List" && actualName == "PList")
                && !(patternName == "PList" && actualName == "List")
            ) {
                return false
            }
            val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
            val actualArgs: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.TypeArg)
            if (patternArgs.size() != actualArgs.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternArgs.size()) {
                if (!semUnifyType(patternArgs[i], actualArgs[i], typeParams)) {
                    return false
                }
                i = i + 1
            }
            return true
        }

        AstNodeCategory.TypeReference -> {
            if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semUnifyType(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }

        AstNodeCategory.TypePointer -> {
            if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semUnifyType(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }

        AstNodeCategory.TypeYield -> {
            // `..T` carries its element type like a pointer its pointee, so it matches element-wise.
            if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semUnifyType(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams
                )
            }
            return false
        }

        AstNodeCategory.TypeFunction -> {
            // A callable matches a callable: the parameter and return types element-wise,
            // so a pattern `(*T) -> U` accepts a lambda's inferred `(*Int) -> Int`.
            if (ak != AstNodeCategory.TypeFunction) {
                return false
            }
            val patternParams: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.ParamType)
            val actualParams: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.ParamType)
            if (patternParams.size() != actualParams.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternParams.size()) {
                if (!semUnifyType(patternParams[i], actualParams[i], typeParams)) {
                    return false
                }
                i = i + 1
            }
            val patternReturn: *AstXmlNode = xmlChildPtr(pattern, AstNodeKind.ReturnType)
            val actualReturn: *AstXmlNode = xmlChildPtr(actualPtr, AstNodeKind.ReturnType)
            if (xmlIsEmpty(patternReturn) || xmlIsEmpty(actualReturn)) {
                return xmlIsEmpty(patternReturn) && xmlIsEmpty(actualReturn)
            }
            return semUnifyType(patternReturn, actualReturn, typeParams)
        }
    }
    return false
}

// The binding form of unification: what each pattern type parameter matched
// (`List<T>` against `List<Str>` binds T := Str). `semUnifyType` is the yes/no form.
fun semBindTypes(
    pattern: *
    AstXmlNode,
    actual: *
    AstXmlNode,
    typeParams: *
    List<Str>,
    bindings: *
    Dictionary<Str, AstXmlNode>
): Bool {
    var actualPtr: *AstXmlNode = actual
    val patternKind: AstNodeCategory = xmlKind(pattern)
    if (patternKind != AstNodeCategory.TypeReference && patternKind != AstNodeCategory.TypePointer) {
        while (true) {
            val actualKind: AstNodeCategory = xmlKind(actualPtr)
            if (actualKind == AstNodeCategory.TypeReference || actualKind == AstNodeCategory.TypePointer) {
                val inner: *AstXmlNode = xmlChildPtr(actualPtr, AstNodeKind.Inner)
                if (xmlIsEmpty(inner)) {
                    break
                }
                actualPtr = inner
            } else {
                break
            }
        }
    }
    val ak: AstNodeCategory = xmlKind(actualPtr)
    when (patternKind) {
        AstNodeCategory.TypeIntLit -> {
            return ak == AstNodeCategory.TypeIntLit
                    && xmlAttr(actualPtr, AstNodeAttributeKind.Text) == xmlAttr(pattern, AstNodeAttributeKind.Text)
        }

        AstNodeCategory.TypeNamed -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return semBindOne(bindings, xmlAttr(pattern, AstNodeAttributeKind.Name), actualPtr)
            }
            return ak == AstNodeCategory.TypeNamed
                    && xmlAttr(actualPtr, AstNodeAttributeKind.Name) == xmlAttr(pattern, AstNodeAttributeKind.Name)
        }

        AstNodeCategory.TypeGeneric -> {
            if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
                return semBindOne(bindings, xmlAttr(pattern, AstNodeAttributeKind.Name), actualPtr)
            }
            if (ak != AstNodeCategory.TypeGeneric) {
                return false
            }
            val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
            val actualName: Str = xmlAttr(actualPtr, AstNodeAttributeKind.Name)
            // `PList<T>` is the alias of `&List<T>`: match it against a `List<T>` (see `semUnifyType`).
            if (actualName != patternName
                && !(patternName == "List" && actualName == "PList")
                && !(patternName == "PList" && actualName == "List")
            ) {
                return false
            }
            val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
            val actualArgs: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.TypeArg)
            if (patternArgs.size() != actualArgs.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternArgs.size()) {
                if (!semBindTypes(patternArgs[i], actualArgs[i], typeParams, bindings)) {
                    return false
                }
                i = i + 1
            }
            return true
        }

        AstNodeCategory.TypeReference -> {
            if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semBindTypes(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams,
                    bindings
                )
            }
            return false
        }

        AstNodeCategory.TypePointer -> {
            if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semBindTypes(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams,
                    bindings
                )
            }
            return false
        }

        AstNodeCategory.TypeYield -> {
            // `..T` binds like a pointer's pointee (see `semUnifyType`).
            if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(xmlChildPtr(actualPtr, AstNodeKind.Inner))
                && !xmlIsEmpty(xmlChildPtr(pattern, AstNodeKind.Inner))
            ) {
                return semBindTypes(
                    xmlChildPtr(pattern, AstNodeKind.Inner),
                    xmlChildPtr(actualPtr, AstNodeKind.Inner),
                    typeParams,
                    bindings
                )
            }
            return false
        }

        AstNodeCategory.TypeFunction -> {
            // A callable binds a callable (see `semUnifyType`): a lambda's inferred result
            // is what binds a pattern's return parameter (`select`'s `U`).
            if (ak != AstNodeCategory.TypeFunction) {
                return false
            }
            val patternParams: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.ParamType)
            val actualParams: List<AstXmlNode> = xmlChildren(actualPtr, AstNodeKind.ParamType)
            if (patternParams.size() != actualParams.size()) {
                return false
            }
            var i: Int = 0
            while (i < patternParams.size()) {
                if (!semBindTypes(patternParams[i], actualParams[i], typeParams, bindings)) {
                    return false
                }
                i = i + 1
            }
            val patternReturn: *AstXmlNode = xmlChildPtr(pattern, AstNodeKind.ReturnType)
            val actualReturn: *AstXmlNode = xmlChildPtr(actualPtr, AstNodeKind.ReturnType)
            if (xmlIsEmpty(patternReturn) || xmlIsEmpty(actualReturn)) {
                return xmlIsEmpty(patternReturn) && xmlIsEmpty(actualReturn)
            }
            return semBindTypes(patternReturn, actualReturn, typeParams, bindings)
        }
    }
    return false
}

// Replaces every type parameter of `typeNode` from `bindings`; an empty result means one
// was unbound and there is nothing to spell. The result is re-rooted as `AstNodeKind.Type`.
fun semSubstitute(typeNode: *AstXmlNode, bindings: *Dictionary<Str, AstXmlNode>, typeParams: *List<Str>): AstXmlNode {
    if (xmlIsEmpty(typeNode)) {
        return xmlEmptyNode()
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    when (kind) {
        AstNodeCategory.TypeIntLit -> {
            return semReRole(typeNode, AstNodeKind.Type)
        }

        AstNodeCategory.TypeNamed -> {
            val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
            if (!xmlIsTypeParam(name, typeParams)) {
                return semReRole(typeNode, AstNodeKind.Type)
            }
            val bound: *AstXmlNode = bindings.getPtr(name)
            if (bound == null) {
                return xmlEmptyNode()
            }
            return semReRole(*bound, AstNodeKind.Type)
        }

        AstNodeCategory.TypeGeneric -> {
            var args: List<AstXmlNode> = List<AstXmlNode>()
            val existing: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            for (*arg in existing) {
                val mapped: AstXmlNode = semSubstitute(arg, bindings, typeParams)
                if (xmlIsEmpty(mapped)) {
                    return xmlEmptyNode()
                }
                args.append(semReRole(mapped, AstNodeKind.TypeArg))
            }
            return semReRole(semReplaceRole(typeNode, AstNodeKind.TypeArg, args), AstNodeKind.Type)
        }

        AstNodeCategory.TypeReference, AstNodeCategory.TypePointer, AstNodeCategory.TypeYield -> {
            // `..T` substitutes like a pointer's pointee: the type is unspellable either way,
            // but its *element* type is what a `for`'s loop variable is typed from.
            val inner: AstXmlNode = semSubstitute(xmlChildPtr(typeNode, AstNodeKind.Inner), bindings, typeParams)
            if (xmlIsEmpty(inner)) {
                return xmlEmptyNode()
            }
            return semReRole(
                semReplaceRole(typeNode, AstNodeKind.Inner, semOne(semReRole(inner, AstNodeKind.Inner))),
                AstNodeKind.Type
            )
        }

        AstNodeCategory.TypeFunction -> {
            var params: List<AstXmlNode> = List<AstXmlNode>()
            val existingParams: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            for (*existingParam in existingParams) {
                val mapped: AstXmlNode = semSubstitute(existingParam, bindings, typeParams)
                if (xmlIsEmpty(mapped)) {
                    return xmlEmptyNode()
                }
                params.append(semReRole(mapped, AstNodeKind.ParamType))
            }
            val ret: AstXmlNode = semSubstitute(xmlChildPtr(typeNode, AstNodeKind.ReturnType), bindings, typeParams)
            if (xmlIsEmpty(ret)) {
                return xmlEmptyNode()
            }
            val withParams: AstXmlNode = semReplaceRole(typeNode, AstNodeKind.ParamType, params)
            return semReRole(
                semReplaceRole(withParams, AstNodeKind.ReturnType, semOne(semReRole(ret, AstNodeKind.ReturnType))),
                AstNodeKind.Type
            )
        }
    }
    return xmlEmptyNode()
}

// A program-level function/method fact the inference resolves a call with.
data class SemFnFact(
    var decl: AstXmlNode,

    var receiver: AstXmlNode,
    var templateParams: List<Str>,

    // Read once when the fact is built: three walks test these on every collected function
    // for every call site, so reading them from the node each time dominated the profile.
    var name: Str,
    // The package the function is declared in; `semMachineType` qualifies a machine's C++
    // class with it.
    var packageName: Str,
    var isNative: Bool,
    // A `native fun` extension spells its receiver as an explicit `this` first parameter,
    // so `receiver` is empty while the declaration is still a member.
    var isExtension: Bool,
    // The parameters a call's arguments convert against: its own, after the receiver.
    var paramCount: Int,
    // Whether its last parameter packs (a `List<T>`/`*List<T>`), from `semIsPackTarget`.
    var packTarget: Bool,
    // A yielding function's machine class carries its parameter types when the name is
    // declared more than once on one receiver (`Emitter.computeMachineSuffixes`); the same
    // field is what `emitFunction` reads, so a slot's class is the struct's name.
    var machineSuffix: Str
)

// The fact for one collected function: everything above, read from the declaration once.
// `name` and `isNative` come from the emitter's `CgFn`, which read them the same way.
fun semFnFact(
    decl: *AstXmlNode, receiver: *AstXmlNode, templateParams: *List<Str>, name: *Str,
    packageName: *Str, isNative: Bool, machineSuffix: Str
): SemFnFact {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    var packTarget: Bool = false
    if (params.size() > 0) {
        packTarget = semIsPackTarget(xmlChildPtr(params[params.size() - 1], AstNodeKind.Type))
    }
    return SemFnFact(
        decl, receiver, templateParams, name, packageName, isNative, semIsExtensionDecl(decl),
        params.size() - semReceiverParams(decl), packTarget, machineSuffix
    )
}

// The C++ class a `..T` machine *is* (impl_specs/yield.md): the emitter names it after
// the creating function, prefixed with the receiver's outer type name for an extension,
// qualified by the function's package (`List<T>.iter` is `List_iter_yieldable`). Building
// the name at the call site is what makes the slot spellable and lets `linHoistSlots`
// hoist the machine's storage: the type stays a `TypeYield` (name in `Name`, template args
// in `TypeArg`, package in `Package`) so every `..T` rule still sees the category it knows.

// The outer name of a type, ignoring handles and arguments (`*List<Int>` is `List`); the
// emitter's `outerTypeName`, here so the pass does not reach back into codegen.
fun semOuterTypeName(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return ""
    }
    var node: AstXmlNode = typeNode
    while (true) {
        val kind: AstNodeCategory = xmlKind(node)
        if (kind != AstNodeCategory.TypeReference && kind != AstNodeCategory.TypePointer) {
            break
        }
        val inner: AstXmlNode = xmlChild(node, AstNodeKind.Inner)
        if (xmlIsEmpty(inner)) {
            break
        }
        node = inner
    }
    val outerKind: AstNodeCategory = xmlKind(node)
    if (outerKind != AstNodeCategory.TypeNamed && outerKind != AstNodeCategory.TypeGeneric) {
        return ""
    }
    return xmlAttr(node, AstNodeAttributeKind.Name)
}

// The machine a call creates, as a *spellable* type: `ret` is the answered `..T`,
// `bindings` what the call site bound, `receiver` the call's receiver type (empty for a
// plain call). An unspellable result (not yielding, an unbound class argument) leaves the
// declaration an `auto`.
//
// A receiver that is itself a machine (`fun ..*T.select<T, U>(...)`) makes the machine a
// template over the source machine's class, so that class rides last (`emitFunction`
// appends `_SmIter`); a call site can only spell it because it knows the receiver's type.
fun semMachineType(
    ret: *AstXmlNode, fn: *SemFnFact, bindings: *Dictionary<Str, AstXmlNode>,
    receiver: AstXmlNode
): AstXmlNode {
    if (xmlKind(ret) != AstNodeCategory.TypeYield) {
        return ret
    }
    // The class's template arguments: the function's type parameters (`emitFunction`).
    var args: List<AstXmlNode> = List<AstXmlNode>()
    var i: Int = 0
    while (i < fn.templateParams.size()) {
        val bound: *AstXmlNode = bindings.getPtr(fn.templateParams[i])
        if (bound == null) {
            return ret
        }
        args.append(semReRole(*bound, AstNodeKind.TypeArg))
        i = i + 1
    }
    if (semMachineReceiver(fn.receiver)) {
        if (xmlIsEmpty(receiver)) {
            return ret
        }
        args.append(semReRole(receiver, AstNodeKind.TypeArg))
    }
    val outer: Str = semOuterTypeName(fn.receiver)
    val fnNameText: Str = fn.name
    var machine: Str = `@(fnNameText)_yieldable`
    if (outer != "") {
        val fnNameText2: Str = fn.name
        machine = `@(outer)_@(fnNameText2)_yieldable`
    }
    machine = machine + fn.machineSuffix
    var node: AstXmlNode = semReplaceRole(ret, AstNodeKind.TypeArg, args)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, machine))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Package, fn.packageName))
    return node
}

// Whether a declaration's body is a machine (`..T` in return position): the functions a
// machine class is named after.
fun semMachineYielder(decl: *AstXmlNode): Bool {
    val ret: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    return !xmlIsEmpty(ret) && xmlKind(ret) == AstNodeCategory.TypeYield
}

// The parameter types a machine class carries when its creator's name is declared more than
// once on one receiver: `Str_splitIter_yieldable_StrView` and `..._Char` are two classes. A
// kind prefix keeps a handle apart from its pointee (`*T` is `PT`), so the names a
// declaration can spell almost never collide.
fun semMachineParamSuffix(decl: *AstXmlNode): Str {
    var out: Str = ""
    for (*param in xmlChildren(decl, AstNodeKind.Param)) {
        out = out + "_" + semTypeMangle(xmlChildPtr(param, AstNodeKind.Type))
    }
    return out
}

fun semTypeMangle(typeNode: *AstXmlNode): Str {
    if (xmlIsEmpty(typeNode)) {
        return "x"
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeNamed || kind == AstNodeCategory.TypeGeneric) {
        val name: Str = semOuterTypeName(typeNode)
        if (name == "") {
            return "x"
        }
        return name
    }
    if (kind == AstNodeCategory.TypePointer) {
        return "P" + semTypeMangle(xmlChildPtr(typeNode, AstNodeKind.Inner))
    }
    if (kind == AstNodeCategory.TypeReference) {
        return "R" + semTypeMangle(xmlChildPtr(typeNode, AstNodeKind.Inner))
    }
    if (kind == AstNodeCategory.TypeYield) {
        return "Y" + semTypeMangle(xmlChildPtr(typeNode, AstNodeKind.Inner))
    }
    if (kind == AstNodeCategory.TypeFunction) {
        return "F" + xmlCount(typeNode, AstNodeKind.ParamType).toString()
    }
    return "u"
}

// Whether a declaration's receiver is a machine *pattern* (`..T` written by the author, not
// a class the pass named): such a function is a template over the receiver's machine class.
fun semMachineReceiver(receiver: *AstXmlNode): Bool {
    return !xmlIsEmpty(receiver) && xmlKind(receiver) == AstNodeCategory.TypeYield
            && xmlAttr(receiver, AstNodeAttributeKind.Name) == ""
}

// The same over a declaration, whichever receiver spelling it uses (`fun ..T.f()` or
// `fun f(this: ..T)`).
fun semMachineReceiverDecl(decl: *AstXmlNode): Bool {
    if (xmlIsEmpty(decl)) {
        return false
    }
    if (semMachineReceiver(xmlChildPtr(decl, AstNodeKind.Receiver))) {
        return true
    }
    return semMachineReceiver(semExtensionReceiver(decl))
}

// A `native fun` extension (`this` first parameter): its receiver picks the overload,
// its return type answers the call.
data class SemExtFact(
    var receiver: AstXmlNode,

    var returnType: AstXmlNode,
    var typeParams: List<Str>
)

// Everything about the program the inference reads, filled from the emitter's own symbol
// collection. It holds the same nodes, so filling it copies no declarations.
data class SemFacts(
    var types: Dictionary<Str, AstXmlNode>,

    var enumNames: Dictionary<Str, Bool>,
    var functions: List<SemFnFact>,
    var nativeExtensions: Dictionary<Str, List<SemExtFact>>,
    var statics: Dictionary<Str, AstXmlNode>
)

fun semNewFacts(): SemFacts {
    return SemFacts(
        Dictionary<Str, AstXmlNode>(), Dictionary<Str, Bool>(), List<SemFnFact>(),
        Dictionary<Str, List<SemExtFact>>(), Dictionary<Str, AstXmlNode>()
    )
}

// One function-like body being annotated: its declaration (which carries the parameters),
// the type of `this`, and the type parameters in scope (class plus function).
//
// A *lambda* body has no declaration: its frame is its parameters plus its captures
// (fields of the closure instance, specs/memory-model.md), seeded like parameters.
// `paramNames` and `paramTypes` are parallel; a type may be missing where the callable
// type supplies it.
data class SemBody(
    var decl: AstXmlNode,

    var typeParams: List<Str>,
    var selfType: AstXmlNode,
    // The class `this` is an instance of, when the *lowering* built it (a state machine):
    // it has no declaration the program wrote, so its fields are reachable only through this.
    var selfDecl: AstXmlNode,

    var paramNames: List<Str>,
    var paramTypes: List<AstXmlNode>,
    var captures: Dictionary<Str, AstXmlNode>
)

// The empty outermost scope (`semInferTypes` seeds the frame into its own pushed scope):
// a shared, never written dictionary, so `lookup` always has one to read.
val semNoScope: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()

// Both the facts and the body are borrowed, not copied: a body is annotated once per
// function, and copying the program tables per body would dominate the run.
data class SemInfer(
    var facts: *SemFacts,
    var body: *SemBody,
    var scopes: List<Dictionary<Str, AstXmlNode>>,

// The flat record of every binding the pass proved, whatever a declaration can spell (a
// machine is `..T`, which `Stmt.type` never carries). A frame is keyed by name, so this is
// what the backend seeds a body's frame from.
    var types: Dictionary<Str, AstXmlNode>,

// The *outermost* scope, borrowed from the caller (`typeOf`): re-seeding the extractor's
// frame into a scope copied the whole frame per question. Read after the pushed scopes, so
// anything the inference marks still wins.
    var baseScope: *Dictionary<Str, AstXmlNode>
) {
}


// Fills in the type of every untyped `VarDecl` in `body` the pass can prove; others come
// back as they are. `inferred` receives every proven name, including ones a C++
// declaration cannot spell (`..T`), which the frame still needs (see `SemInfer.types`).
fun semInferTypes(
    body: *List<AstXmlNode>, facts: *SemFacts, ctx: *SemBody,
    inferred: *Dictionary<Str, AstXmlNode>
): List<AstXmlNode> {
    val infer: SemInfer = SemInfer(
        facts, ctx, List<Dictionary<Str, AstXmlNode>>(), Dictionary<Str, AstXmlNode>(), *semNoScope
    )
    infer.pushScope()
    val params: List<AstXmlNode> = xmlChildren(ctx.decl, AstNodeKind.Param)
    var i: Int = 0
    for (*param in params) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (!xmlIsEmpty(paramType)) {
            infer.mark(xmlAttr(param, AstNodeAttributeKind.Name), paramType)
        }
    }
    // A lambda's frame: its parameters (a type may come from the callable type it is used
    // against), then its captures, which are closure fields.
    i = 0
    while (i < ctx.paramNames.size()) {
        if (i < ctx.paramTypes.size() && !xmlIsEmpty(ctx.paramTypes[i])) {
            infer.mark(ctx.paramNames[i], ctx.paramTypes[i])
        }
        i = i + 1
    }
    val captureNames: List<Str> = ctx.captures.keys()
    i = 0
    while (i < captureNames.size()) {
        infer.mark(captureNames[i], ctx.captures.getPtr(captureNames[i]))
        i = i + 1
    }
    val out: List<AstXmlNode> = infer.stmts(body)
    infer.popScope()
    val proven: List<Str> = infer.types.keys()
    i = 0
    while (i < proven.size()) {
        val provenType: *AstXmlNode = infer.types.getPtr(proven[i])
        inferred.insert(proven[i], *provenType)
        i = i + 1
    }
    return out
}

// The type of one expression, with the names in scope given explicitly: the same rules the
// pass applies to a whole body, asked about a single node.
//
// The IL extractor needs this: its flat frame's synthesized slots (the place behind a read,
// a value it must declare) have no source declaration to take a type from, and a type per
// slot is what makes every instruction one operation over typed slots (`impl_specs/linear-il.md`).
fun semTypeOfExpr(
    expr: *AstXmlNode, facts: *SemFacts, ctx: *SemBody, names: *Dictionary<Str, AstXmlNode>
): AstXmlNode {
    // The frame is seeded at the entry points, not the constructor.
    val infer: SemInfer = SemInfer(
        facts, ctx, List<Dictionary<Str, AstXmlNode>>(), Dictionary<Str, AstXmlNode>(), *semNoScope
    )
    infer.pushScope()
    val params: List<AstXmlNode> = xmlChildren(ctx.decl, AstNodeKind.Param)
    var i: Int = 0
    for (*param in params) {
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (!xmlIsEmpty(paramType)) {
            infer.mark(xmlAttr(param, AstNodeAttributeKind.Name), paramType)
        }
    }
    i = 0
    while (i < ctx.paramNames.size()) {
        if (i < ctx.paramTypes.size() && !xmlIsEmpty(ctx.paramTypes[i])) {
            infer.mark(ctx.paramNames[i], ctx.paramTypes[i])
        }
        i = i + 1
    }
    val captureNames: List<Str> = ctx.captures.keys()
    i = 0
    while (i < captureNames.size()) {
        infer.mark(captureNames[i], ctx.captures.getPtr(captureNames[i]))
        i = i + 1
    }
    return infer.typeOf(expr, names)
}
