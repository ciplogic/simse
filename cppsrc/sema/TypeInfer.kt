// TypeInfer.kt
//
// A semantic step on a *lowered* body (impl_specs/linear-lowering.md): after lowering, it
// types every declaration the lowering introduced - and any unannotated `val`/`var` - so
// the emitter never has to guess one or fall back to `auto`. It is not a reifier: a type
// parameter in scope is a good type to spell. An empty `AstXmlNode` is "no/unknown type".

package sema

import common

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

        "Attribute", "XmlNode", "AstXmlNode", "AstNodeAttribute" -> {
            return true
        }

        "Span", "StrView", "FileStream" -> {
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
// `replacements`. The lowering's `exprReplaceRole`, which this package cannot import.
fun semReplaceRole(like: *AstXmlNode, role: AstNodeKind, replacements: *List<AstXmlNode>): AstXmlNode {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    val existing: List<AstXmlNode> = like.Children.toList()
    var seen: Int = 0
    for (*child in existing) {
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
    val existing: List<AstXmlNode> = decl.Children.toList()
    var i: Int = 0
    while (i < existing.size()) {
        if (existing[i].name != AstNodeKind.Type) {
            kids.append(existing[i])
        }
        i = i + 1
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
            // `..T` carries its element type like a pointer its pointee, so it binds the same way.
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
    var packTarget: Bool
)

// The fact for one collected function: everything above, read from the declaration once.
// `name` and `isNative` come from the emitter's `CgFn`, which read them the same way.
fun semFnFact(
    decl: *AstXmlNode, receiver: *AstXmlNode, templateParams: *List<Str>, name: *Str,
    packageName: *Str, isNative: Bool
): SemFnFact {
    val params: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    var packTarget: Bool = false
    if (params.size() > 0) {
        packTarget = semIsPackTarget(xmlChildPtr(params[params.size() - 1], AstNodeKind.Type))
    }
    return SemFnFact(
        decl, receiver, templateParams, name, packageName, isNative, semIsExtensionDecl(decl),
        params.size() - semReceiverParams(decl), packTarget
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
// `bindings` what the call site bound. An unspellable result (not yielding, or an
// unbound class argument) leaves the declaration an `auto`.
fun semMachineType(
    ret: *AstXmlNode, fn: *SemFnFact, bindings: *Dictionary<Str, AstXmlNode>
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
    val outer: Str = semOuterTypeName(fn.receiver)
    var machine: Str = fmtStr("|_yieldable", fn.name)
    if (outer != "") {
        machine = fmtStr("|_|_yieldable", outer, fn.name)
    }
    var node: AstXmlNode = semReplaceRole(ret, AstNodeKind.TypeArg, args)
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, machine))
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Package, fn.packageName))
    return node
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
    fun pushScope(): Unit {
        this.scopes.append(Dictionary<Str, AstXmlNode>())
    }

    fun popScope(): Unit {
        this.scopes.removeAt(this.scopes.size() - 1)
    }

    // One expression, typed against the caller's (flat) frame `names`; the scope is pushed
    // and popped so the call leaves nothing behind, and `names` is borrowed, not copied.
    fun typeOf(e: *AstXmlNode, names: *Dictionary<Str, AstXmlNode>): AstXmlNode {
        this.pushScope()
        this.baseScope = names
        val result: AstXmlNode = this.infer(e)
        this.popScope()
        return result
    }

    // Records a binding in the scope being built *and* in the flat record.
    fun mark(name: *Str, typeNode: *AstXmlNode): Unit {
        if (this.scopes.size() == 0) {
            return
        }
        this.scopes[this.scopes.size() - 1].insert(name, typeNode)
        this.types.insert(name, typeNode)
    }

    fun lookup(name: *Str): AstXmlNode {
        var i: Int = this.scopes.size() - 1
        while (i >= 0) {
            val declared: *AstXmlNode = this.scopes[i].getPtr(name)
            if (declared != null) {
                return * declared
            }
            i = i - 1
        }
        val base: *AstXmlNode = this.baseScope.getPtr(name)
        if (base != null) {
            return * base
        }
        return xmlEmptyNode()
    }

    // A declaration is annotated only when the *value* of its initializer has a C++ type:
    // a lambda needs its expected callable type and `null` has none. Everything else,
    // including `&x`/`*x`/`copy(x)`, is typed below.
    fun declarable(init: *AstXmlNode): Bool {
        val kind: AstNodeCategory = xmlKind(init)
        if (kind == AstNodeCategory.ExprLambda || kind == AstNodeCategory.ExprNullLit) {
            return false
        }
        return true
    }

    fun spellableName(name: *Str): Bool {
        if (name == "Unit") {
            return false // `void` has no values
        }
        if (xmlIsTypeParam(name, this.body.typeParams)) {
            return true
        }
        if (this.facts.types.has(name)) {
            return true
        }
        return semIsRtlTypeName(name)
    }

    // Whether the emitter can spell this type in this body: an out-of-scope type parameter
    // or an undeclared name would not compile, so both mean "leave it to `auto`".
    fun spellable(typeNode: *AstXmlNode): Bool {
        if (xmlIsEmpty(typeNode)) {
            return false
        }
        val kind: AstNodeCategory = xmlKind(typeNode)
        when (kind) {
            AstNodeCategory.TypeIntLit -> {
                return true
            }

            AstNodeCategory.TypeNamed -> {
                return this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))
            }

            AstNodeCategory.TypeGeneric -> {
                if (!this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))) {
                    return false
                }
                val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
                for (*arg in args) {
                    if (!this.spellable(arg)) {
                        return false
                    }
                }
                return true
            }

            AstNodeCategory.TypeReference, AstNodeCategory.TypePointer -> {
                return this.spellable(xmlChildPtr(typeNode, AstNodeKind.Inner))
            }

            AstNodeCategory.TypeFunction -> {
                if (!this.spellable(xmlChildPtr(typeNode, AstNodeKind.ReturnType))) {
                    return false
                }
                val params: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
                for (*param in params) {
                    if (!this.spellable(param)) {
                        return false
                    }
                }
                return true
            }

            AstNodeCategory.TypeYield -> {
                // A machine (`..T`) is spellable only once `semMachineType` named its class;
                // an anonymous one has no C++ spelling, so its declaration stays an `auto`.
                if (xmlAttr(typeNode, AstNodeAttributeKind.Name) == "") {
                    return false
                }
                val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
                for (*arg in args) {
                    if (!this.spellable(arg)) {
                        return false
                    }
                }
                return true
            }
        }
        return false
    }

    fun isTypeName(name: *Str): Bool {
        return this.facts.types.has(name) || semIsRtlTypeName(name)
    }

    fun stmts(list: *List<AstXmlNode>): List<AstXmlNode> {
        var out: List<AstXmlNode> = List<AstXmlNode>()
        var i: Int = 0
        while (i < list.size()) {
            out.append(this.stmt(list[i]))
            i = i + 1
        }
        return out
    }

    fun stmt(stmtNode: AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(stmtNode)
        when (kind) {
            AstNodeCategory.StmtVarDecl -> {
                val declared: *AstXmlNode = xmlChildPtr(stmtNode, AstNodeKind.Type)
                val init: *AstXmlNode = xmlChildPtr(stmtNode, AstNodeKind.Init)
                var typeNode: AstXmlNode = declared
                if (xmlIsEmpty(typeNode) && !xmlIsEmpty(init)) {
                    typeNode = this.infer(init)
                }
                val name: Str = xmlAttr(stmtNode, AstNodeAttributeKind.Name)
                if (!xmlIsEmpty(typeNode)) {
                    this.mark(name, typeNode)
                }
                if (!xmlIsEmpty(declared) || xmlIsEmpty(typeNode) || xmlIsEmpty(init)
                    || !this.declarable(init) || !this.spellable(typeNode)
                ) {
                    return stmtNode
                }
                return semWithType(stmtNode, semReRole(typeNode, AstNodeKind.Type))
            }

            AstNodeCategory.StmtBlock -> {
                this.pushScope()
                val inner: List<AstXmlNode> = xmlChildren(xmlChildPtr(stmtNode, AstNodeKind.Body), AstNodeKind.Stmt)
                val rebuilt: List<AstXmlNode> = this.stmts(inner)
                this.popScope()
                var body: AstXmlNode =
                    AstXmlNode(AstNodeKind.Body, AstNodeCategory.None, List<AstNodeAttribute>(), rebuilt.toArray())
                return semReplaceRole(stmtNode, AstNodeKind.Body, semOne(body))
            }
        }
        return stmtNode
    }

    fun handle(kind: AstNodeCategory, inner: *AstXmlNode): AstXmlNode {
        if (xmlIsEmpty(inner)) {
            return xmlEmptyNode()
        }
        return AstXmlNode(
            AstNodeKind.Type, kind, List<AstNodeAttribute>(),
            semOne(semReRole(inner, AstNodeKind.Inner)).toArray()
        )
    }

    // A data class member type with the class's type parameters bound to the
    // receiver's arguments (`Box<Int>.value` with `value: T` is `Int`).
    fun instantiate(decl: *AstXmlNode, base: *AstXmlNode, memberType: AstXmlNode): AstXmlNode {
        if (xmlIsEmpty(memberType)) {
            return xmlEmptyNode()
        }
        val classParams: List<Str> = xmlTypeParamNames(decl)
        if (classParams.size() == 0) {
            return memberType
        }
        if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
            return memberType
        }
        val args: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
        if (args.size() != classParams.size()) {
            return memberType
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        var i: Int = 0
        while (i < classParams.size()) {
            bindings.insert(classParams[i], args[i])
            i = i + 1
        }
        return semSubstitute(memberType, bindings, classParams)
    }

    // The return type of a call, the callee's type parameters bound from an explicit
    // instantiation (`identity<Int>(7)`) or the receiver (`Box<Int>.get()`). A result that
    // still mentions a type parameter stays symbolic (the C++ template specializes it
    // later); a parameter nothing binds leaves no type to spell.
    fun functionReturn(name: *Str, typeArgs: *List<AstXmlNode>, receiver: *AstXmlNode): AstXmlNode {
        var i: Int = 0
        while (i < this.facts.functions.size()) {
            val fn: *SemFnFact = *this.facts.functions[i]
            i = i + 1
            if (fn.name != name) {
                continue
            }
            val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
            if (xmlIsEmpty(ret)) {
                continue
            }
            val hasReceiver: Bool = !xmlIsEmpty(fn.receiver)
            if (hasReceiver != !xmlIsEmpty(receiver)) {
                continue
            }
            // A `native fun` extension has no recorded receiver but is still a *member*: a
            // plain call must not reach it (`functionReturn` for `fun find(...)`).
            if (!hasReceiver && fn.isExtension) {
                continue
            }
            var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
            if (hasReceiver) {
                if (!semBindTypes(fn.receiver, receiver, fn.templateParams, bindings)) {
                    continue
                }
            }
            if (typeArgs.size() > 0) {
                if (typeArgs.size() != fn.templateParams.size()) {
                    continue
                }
                var bound: Bool = true
                var a: Int = 0
                while (a < typeArgs.size()) {
                    if (bound) {
                        bound = semBindOne(bindings, fn.templateParams[a], typeArgs[a])
                    }
                    a = a + 1
                }
                if (!bound) {
                    continue
                }
            }
            val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
            if (!xmlIsEmpty(result)) {
                return semMachineType(result, fn, bindings)
            }
        }
        return xmlEmptyNode()
    }

    // The result of a member call (`recv.name(...)`): a declared extension/method, then a
    // native extension, then the built-in accessors, the way the emitter lowers it.
    // A receiver type through a `typealias` (`Emitter.resolveAlias`): `StrView` is
    // `Span<Char>`, so an extension on `Span<T>` is reachable through a view.
    fun resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
        var current: AstXmlNode = typeNode
        var guard: Int = 0
        while (!xmlIsEmpty(current) && guard < 16) {
            guard = guard + 1
            if (xmlKind(current) != AstNodeCategory.TypeNamed) {
                return current
            }
            val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
            val decl: *AstXmlNode = this.facts.types.getPtr(name)
            if (decl == null) {
                return current
            }
            if (decl.name != AstNodeKind.TypeAlias) {
                return current
            }
            val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
            if (xmlIsEmpty(target)) {
                return current
            }
            current = target
        }
        return current
    }

    fun memberReturn(callee: *AstXmlNode): AstXmlNode {
        val receiverType: AstXmlNode = this.infer(xmlChildPtr(callee, AstNodeKind.Receiver))
        val recv: AstXmlNode = this.resolveAlias(semPointee(receiverType))
        if (xmlIsEmpty(recv)) {
            return xmlEmptyNode()
        }
        val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        var i: Int = 0
        while (i < this.facts.functions.size()) {
            val fn: *SemFnFact = *this.facts.functions[i]
            i = i + 1
            if (fn.isNative || xmlIsEmpty(fn.receiver)) {
                continue
            }
            if (fn.name != calleeText) {
                continue
            }
            val ret: *AstXmlNode = xmlChildPtr(fn.decl, AstNodeKind.ReturnType)
            if (xmlIsEmpty(ret)) {
                continue
            }
            var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
            if (!semBindTypes(this.resolveAlias(fn.receiver), recv, fn.templateParams, bindings)) {
                continue
            }
            val result: AstXmlNode = semSubstitute(ret, bindings, fn.templateParams)
            if (!xmlIsEmpty(result)) {
                return semMachineType(result, fn, bindings)
            }
        }
        val extensions: *List<SemExtFact> = this.facts.nativeExtensions.getPtr(calleeText)
        if (extensions != null) {
            var e: Int = 0
            while (e < extensions.size()) {
                val ext: *SemExtFact = *extensions[e]
                e = e + 1
                if (xmlIsEmpty(ext.receiver) || xmlIsEmpty(ext.returnType)) {
                    continue
                }
                var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
                if (!semBindTypes(this.resolveAlias(ext.receiver), recv, ext.typeParams, bindings)) {
                    continue
                }
                val result: AstXmlNode = semSubstitute(ext.returnType, bindings, ext.typeParams)
                if (!xmlIsEmpty(result)) {
                    return result
                }
            }
        }
        if (xmlKind(recv) == AstNodeCategory.TypeYield) {
            // A machine's surface is the lowering's ABI: `advance()` steps it, and `current`
            // holds what it yielded. Typing them makes a `for`'s loop variable a typed binding.
            if (calleeText == "advance") {
                return semNamedType("Bool")
            }
            // A machine is already iterable: `x.iter()` is `x` (`impl_specs/for.md`); `..T`
            // is not spellable, so no function could take one.
            if (calleeText == "iter") {
                return receiverType
            }
        }
        if (xmlKind(recv) == AstNodeCategory.TypeGeneric) {
            val typeArgs: List<AstXmlNode> = xmlChildren(recv, AstNodeKind.TypeArg)
            val recvName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
            // The static constructor forms (`Opt<int>.none()`, `Res<int>.ok(42)`,
            // specs/core-types.md) have no declaration, so their result type is stated here:
            // the type they are qualified by. Per *name* - `EnumType.fromInt(n)` is the enum
            // rule's (`specs/declarations.md`), deliberately not in this list.
            if ((recvName == "Opt" && (calleeText == "none" || calleeText == "some"))
                || (recvName == "Res" && (calleeText == "ok" || calleeText == "err"))
            ) {
                return semReRole(recv, AstNodeKind.Type)
            }
            if (calleeText == "value" && recvName == "Opt" && typeArgs.size() > 0) {
                return semReRole(typeArgs[0], AstNodeKind.Type)
            }
            if ((calleeText == "size" || calleeText == "count")
                && (recvName == "List" || recvName == "Array" || recvName == "Dictionary"
                        || recvName == "SmallVector" || recvName == "Span")
            ) {
                return semNamedType("Int")
            }
        }
        if (xmlKind(recv) == AstNodeCategory.TypeNamed && calleeText == "size"
            && xmlAttr(recv, AstNodeAttributeKind.Name) == "Str"
        ) {
            return semNamedType("Int")
        }
        if (calleeText == "isOk" || calleeText == "hasValue") {
            return semNamedType("Bool")
        }
        // An enum's conversions (`specs/declarations.md`): `E.toInt()` is the member's
        // integer value, `E.fromInt(n)` the unchecked cast back.
        if (xmlKind(recv) == AstNodeCategory.TypeNamed
            && this.facts.enumNames.has(xmlAttr(recv, AstNodeAttributeKind.Name))
        ) {
            if (calleeText == "toInt") {
                return semNamedType("Int")
            }
            if (calleeText == "fromInt") {
                return semReRole(recv, AstNodeKind.Type)
            }
        }
        return this.classMemberReturn(*recv, calleeText)
    }

    // A member of a data class no `SemFnFact` stands for: a *prelude* class's methods
    // (`Emitter.collect` skips prelude declarations, so `Span<Char>.at`'s `T` is not in
    // the facts). The receiver's own type arguments bind the class's type parameters.
    fun classMemberReturn(recv: *AstXmlNode, calleeName: *Str): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(recv)
        if (kind != AstNodeCategory.TypeNamed && kind != AstNodeCategory.TypeGeneric) {
            return xmlEmptyNode()
        }
        val typeName: Str = xmlAttr(recv, AstNodeAttributeKind.Name)
        val classDecl: *AstXmlNode = this.facts.types.getPtr(typeName)
        if (classDecl == null) {
            return xmlEmptyNode()
        }
        if (classDecl.name != AstNodeKind.DataClass) {
            return xmlEmptyNode()
        }
        val classParams: List<Str> = xmlTypeParamNames(classDecl)
        val typeArgs: List<AstXmlNode> = xmlChildren(recv, AstNodeKind.TypeArg)
        if (classParams.size() != typeArgs.size()) {
            return xmlEmptyNode()
        }
        var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        var i: Int = 0
        while (i < classParams.size()) {
            bindings.insert(classParams[i], typeArgs[i])
            i = i + 1
        }
        val methods: List<AstXmlNode> = xmlChildren(classDecl, AstNodeKind.Function)
        for (*method in methods) {
            if (xmlAttr(method, AstNodeAttributeKind.Name) != calleeName) {
                continue
            }
            val ret: *AstXmlNode = xmlChildPtr(method, AstNodeKind.ReturnType)
            if (xmlIsEmpty(ret)) {
                continue
            }
            val result: AstXmlNode = semSubstitute(ret, bindings, classParams)
            if (!xmlIsEmpty(result)) {
                return result
            }
        }
        return xmlEmptyNode()
    }

    // A callable's own return type: calling a *value* of function type (`predicate(x)`) is
    // an indirect call, resolved through a `typealias` as the emitter resolves it.
    fun callableReturn(typeNode: *AstXmlNode): AstXmlNode {
        var current: AstXmlNode = semPointee(typeNode)
        var guard: Int = 0
        while (!xmlIsEmpty(current) && guard < 16) {
            guard = guard + 1
            if (xmlKind(current) == AstNodeCategory.TypeFunction) {
                return xmlChild(current, AstNodeKind.ReturnType)
            }
            if (xmlKind(current) != AstNodeCategory.TypeNamed) {
                return xmlEmptyNode()
            }
            val aliasName: Str = xmlAttr(current, AstNodeAttributeKind.Name)
            val decl: *AstXmlNode = this.facts.types.getPtr(aliasName)
            if (decl == null) {
                return xmlEmptyNode()
            }
            if (decl.name != AstNodeKind.TypeAlias) {
                return xmlEmptyNode()
            }
            current = xmlChild(decl, AstNodeKind.TargetType)
        }
        return xmlEmptyNode()
    }

    fun callReturn(callee: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(callee)
        when (kind) {
            AstNodeCategory.ExprGenericName -> {
                val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                if (this.isTypeName(name)) {
                    return semGenericType(name, xmlChildren(callee, AstNodeKind.TypeArg))
                }
                return this.functionReturn(name, xmlChildren(callee, AstNodeKind.TypeArg), xmlEmptyNode())
            }

            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
                if (this.isTypeName(name)) {
                    return semNamedType(name)
                }
                val direct: AstXmlNode = this.functionReturn(name, List<AstXmlNode>(), xmlEmptyNode())
                if (!xmlIsEmpty(direct)) {
                    return direct
                }
                return this.callableReturn(this.lookup(name))
            }

            AstNodeCategory.ExprMember -> {
                return this.memberReturn(callee)
            }
        }
        return xmlEmptyNode()
    }

    fun infer(e: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        when (kind) {
            AstNodeCategory.ExprIntLit -> {
                return semNamedType("Int")
            }

            AstNodeCategory.ExprFloatLit -> {
                return semNamedType("Float64")
            }

            AstNodeCategory.ExprStrLit -> {
                return semNamedType("Str")
            }

            AstNodeCategory.ExprCharLit -> {
                return semNamedType("Char")
            }

            AstNodeCategory.ExprBoolLit -> {
                return semNamedType("Bool")
            }

            AstNodeCategory.ExprNullLit -> {
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprName -> {
                val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (name == "this") {
                    return semReRole(copy(this.body.selfType), AstNodeKind.Type)
                }
                val local: AstXmlNode = this.lookup(name)
                if (!xmlIsEmpty(local)) {
                    return local
                }
                // File-level static storage (specs/statics.md).
                val staticType: *AstXmlNode = this.facts.statics.getPtr(name)
                if (staticType != null) {
                    return semReRole(*staticType, AstNodeKind.Type)
                }
                // A bare enum type name used as the receiver of a static conversion.
                if (this.facts.enumNames.has(name)) {
                    return semNamedType(name)
                }
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprGenericName -> {
                val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (!this.isTypeName(name)) {
                    return xmlEmptyNode()
                }
                return semGenericType(name, xmlChildren(e, AstNodeKind.TypeArg))
            }

            AstNodeCategory.ExprMember -> {
                val lhs: *AstXmlNode = xmlChildPtr(e, AstNodeKind.Receiver)
                // An enum member expression has the enum's type.
                if (xmlKind(lhs) == AstNodeCategory.ExprName
                    && this.facts.enumNames.has(xmlAttr(lhs, AstNodeAttributeKind.Name))
                ) {
                    return semNamedType(xmlAttr(lhs, AstNodeAttributeKind.Name))
                }
                val baseType: AstXmlNode = this.infer(lhs)
                val base: AstXmlNode = semPointee(baseType)
                if (xmlIsEmpty(base)) {
                    return xmlEmptyNode()
                }
                val memberText: Str = xmlAttr(e, AstNodeAttributeKind.Name)
                if (xmlKind(base) == AstNodeCategory.TypeYield) {
                    // A machine's `current` field (`impl_specs/for.md`): what it last yielded.
                    // For `iterPtr` the element type *is* `*T`, a place rather than a copy.
                    if (memberText == "current") {
                        return semReRole(xmlChild(base, AstNodeKind.Inner), AstNodeKind.Type)
                    }
                }
                if (xmlKind(base) == AstNodeCategory.TypeGeneric && xmlAttr(
                        base,
                        AstNodeAttributeKind.Name
                    ) == "Res"
                ) {
                    val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
                    // The spec spells these `value`/`error`, the RTL's fields `Value`/`Error`;
                    // an unremapped name is emitted as written, so both reach C++ (core-types.md).
                    if ((memberText == "value" || memberText == "Value") && typeArgs.size() > 0) {
                        return semReRole(typeArgs[0], AstNodeKind.Type)
                    }
                    if (memberText == "error" || memberText == "Error") {
                        return semNamedType("Str")
                    }
                }
                val baseKind: AstNodeCategory = xmlKind(base)
                if (baseKind == AstNodeCategory.TypeNamed || baseKind == AstNodeCategory.TypeGeneric) {
                    val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
                    var decl: *AstXmlNode = this.facts.types.getPtr(baseName)
                    if (decl == null) {
                        if (!xmlIsEmpty(this.body.selfDecl)
                            && xmlAttr(this.body.selfDecl, AstNodeAttributeKind.Name) == baseName
                        ) {
                            // A class the lowering built (a state machine): its fields are reached
                            // through the body's own context, not a written declaration.
                            decl = *this.body.selfDecl
                        }
                    }
                    if (decl != null && decl.name == AstNodeKind.DataClass) {
                        val fields: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Field)
                        for (*field in fields) {
                            if (xmlAttr(field, AstNodeAttributeKind.Name) == memberText) {
                                return this.instantiate(decl, base, xmlChild(field, AstNodeKind.Type))
                            }
                        }
                    }
                }
                return xmlEmptyNode()
            }

            AstNodeCategory.ExprCall -> {
                return this.callReturn(xmlChildPtr(e, AstNodeKind.Callee))
            }

            AstNodeCategory.ExprIndex -> {
                val baseType: AstXmlNode = this.infer(xmlChildPtr(e, AstNodeKind.Receiver))
                val base: AstXmlNode = semPointee(baseType)
                if (xmlIsEmpty(base)) {
                    return xmlEmptyNode()
                }
                if (xmlKind(base) == AstNodeCategory.TypeNamed && xmlAttr(base, AstNodeAttributeKind.Name) == "Str") {
                    return semNamedType("Char")
                }
                if (xmlKind(base) != AstNodeCategory.TypeGeneric) {
                    return xmlEmptyNode()
                }
                val typeArgs: List<AstXmlNode> = xmlChildren(base, AstNodeKind.TypeArg)
                if (typeArgs.size() == 0) {
                    return xmlEmptyNode()
                }
                val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
                if (baseName == "SmallVector" && typeArgs.size() == 2) {
                    return semReRole(typeArgs[1], AstNodeKind.Type) // <N, T>
                }
                if (baseName == "Dictionary" && typeArgs.size() == 2) {
                    return semReRole(typeArgs[1], AstNodeKind.Type)
                }
                return semReRole(typeArgs[0], AstNodeKind.Type)
            }

            AstNodeCategory.ExprRef -> {
                // `&x` boxes a copy for the call (`std::make_shared<T>(x)`), so the C++
                // type is a counted reference to whatever `x` is.
                return this.handle(AstNodeCategory.TypeReference, this.infer(xmlChildPtr(e, AstNodeKind.Operand)))
            }

            AstNodeCategory.ExprDeref -> {
                // `*x` is the *address* of what `x` denotes: a value's own storage (`&x`), a
                // counted reference's pointee (`x.get()`), or a pointer (read through).
                val operand: AstXmlNode = this.infer(xmlChildPtr(e, AstNodeKind.Operand))
                if (xmlIsEmpty(operand)) {
                    return xmlEmptyNode()
                }
                val operandKind: AstNodeCategory = xmlKind(operand)
                if (operandKind == AstNodeCategory.TypePointer) {
                    return semReRole(xmlChild(operand, AstNodeKind.Inner), AstNodeKind.Type)
                }
                if (operandKind == AstNodeCategory.TypeReference) {
                    return this.handle(AstNodeCategory.TypePointer, xmlChildPtr(operand, AstNodeKind.Inner))
                }
                return this.handle(AstNodeCategory.TypePointer, operand)
            }

            AstNodeCategory.ExprCopy -> {
                // `copy(x)` is the *value*: a plain read, or the pointee of a handle (`*(x)`).
                val operand: AstXmlNode = this.infer(xmlChildPtr(e, AstNodeKind.Operand))
                val value: AstXmlNode = semPointee(operand)
                if (xmlIsEmpty(value)) {
                    return xmlEmptyNode()
                }
                return semReRole(value, AstNodeKind.Type)
            }

            AstNodeCategory.ExprUnary -> {
                return this.infer(xmlChildPtr(e, AstNodeKind.Operand))
            }

            AstNodeCategory.ExprBinary -> {
                val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
                if (op == "==" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">="
                    || op == "&&" || op == "||"
                ) {
                    return semNamedType("Bool")
                }
                // The operation is on *values*: a handle operand is read through to its
                // pointee, so the result is the left operand as a value. Returning the handle
                // itself would make the emitter declare every slot for `a + b` as one.
                return semPointee(this.infer(xmlChildPtr(e, AstNodeKind.Lhs)))
            }
        }
        // A lambda's type comes from the callable type it is used against (the emitter's
        // business, which may infer its parameters from there).
        return xmlEmptyNode()
    }
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
