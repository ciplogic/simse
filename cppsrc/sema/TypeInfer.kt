// TypeInfer.kt
//
// A semantic step that runs on a *lowered* body - the Simse mirror of
// cppsrc/sema/TypeInfer.cpp (impl_specs/linear-lowering.md, "Expression
// lowering"). After the linear and expression lowering have produced the emitter's
// final vocabulary, it gives every declaration the lowering introduced - and any
// `val`/`var` the program left unannotated - a type, so the emitter does not have to
// guess one while it emits and does not fall back to `auto`.
//
// It is deliberately *not* a reifier: a type parameter in scope is a perfectly good
// type to spell (the emitted C++ is a template, so the C++ compiler specializes it
// later), and an explicit instantiation substitutes its type arguments into the
// call's result. Anything the pass cannot spell in this body - a type parameter that
// is not in scope, a lambda, `&x`/`*x`/`copy(x)`/`null`, a call it cannot resolve -
// leaves the declaration untyped, exactly as it was before this pass existed.
//
// Types are `AstXmlNode` subtrees like everywhere else in this ring, and an empty
// node (`xmlEmptyNode`) is "no/unknown type". The pass reads the program-level facts
// the emitter has already collected (`SemFacts`), never files, and it never modifies
// the parsed AST: only a declaration that gains a type is rebuilt.

package sema

import common

// ---- type nodes -----------------------------------------------------------

fun semNamedType(name: Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

fun semGenericType(name: Str, args: List<AstXmlNode>): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeGeneric, List<AstNodeAttribute>(), args.toArray())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    return node
}

// The built-in (RTL) type names: they keep their C++ spelling and need no
// declaration to be usable in a type position. This is the one list - the emitter
// calls it instead of keeping its own (`cgIsRtlTypeName` used to be a copy).
fun semIsRtlTypeName(name: Str): Bool {
    if (name == "Int" || name == "Int8" || name == "Int16" || name == "Int32" || name == "Int64") {
        return true
    }
    if (name == "Float32" || name == "Float64" || name == "Char" || name == "Bool" || name == "Str") {
        return true
    }
    if (name == "List" || name == "Array" || name == "RawArray" || name == "Opt" || name == "Res") {
        return true
    }
    if (name == "Dictionary" || name == "SmallVector" || name == "PList") {
        return true
    }
    if (name == "Attribute" || name == "XmlNode" || name == "AstXmlNode" || name == "AstNodeAttribute") {
        return true
    }
    if (name == "Span" || name == "StrView" || name == "FileStream") {
        return true
    }
    return false
}

// The node re-rooted under `role`. A type read out of a declaration carries the
// role it was read from (`ReturnType` in a signature, `TypeArg` in an argument
// list, `Type` in a field) while the place it is put back into expects its own -
// the emitter looks children up by role, so a type that is re-used must be
// re-rooted, exactly like the emitter's own `renameRole` does for `Inner`.
fun semReRole(node: AstXmlNode, role: AstNodeKind): AstXmlNode {
    if (node.name == role) {
        return node
    }
    var renamed: AstXmlNode = copy(node)
    renamed.name = role
    return renamed
}

// A one-element list, for `semReplaceRole`.
fun semOne(node: AstXmlNode): List<AstXmlNode> {
    var out: List<AstXmlNode> = List<AstXmlNode>()
    out.append(node)
    return out
}

// The same node with every child whose role is `role` replaced, in order, by
// `replacements`; the other children keep their places. The lowering pass's
// `exprReplaceRole`, which this package cannot import (the semantics are imported
// by the lowering, never the other way round).
fun semReplaceRole(like: *AstXmlNode, role: AstNodeKind, replacements: List<AstXmlNode>): AstXmlNode {
    var kids: List<AstXmlNode> = List<AstXmlNode>()
    val existing: List<AstXmlNode> = like.Children.toList()
    var seen: Int = 0
    var i: Int = 0
    while (i < existing.size()) {
        val child: AstXmlNode = existing[i]
        if (child.name == role) {
            if (seen < replacements.size()) {
                kids.append(replacements[seen])
            }
            seen = seen + 1
        } else {
            kids.append(child)
        }
        i = i + 1
    }
    while (seen < replacements.size()) {
        kids.append(replacements[seen])
        seen = seen + 1
    }
    return AstXmlNode(like.name, like.kind, copy(like.attributes), kids.toArray())
}

// The declaration with a `Type` child of its own: what an untyped declaration was
// missing. The child comes first, like a parsed `val x: T = ...`.
fun semWithType(decl: *AstXmlNode, typeNode: AstXmlNode): AstXmlNode {
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
    var current: AstXmlNode = copy(typeNode)
    while (!xmlIsEmpty(*current)) {
        val kind: AstNodeCategory = xmlKind(*current)
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
            val inner: AstXmlNode = xmlChild(*current, AstNodeKind.Inner)
            if (xmlIsEmpty(*inner)) {
                return current
            }
            current = inner
        } else {
            return current
        }
    }
    return current
}

// Structural equality, used to refuse a second, different binding for a pattern
// type parameter (`Map<K, K>` against `Map<Str, Int>` is not a match).
fun semSameType(left: *AstXmlNode, right: *AstXmlNode): Bool {
    val leftKind: AstNodeCategory = xmlKind(left)
    if (leftKind != xmlKind(right)) {
        return false
    }
    if (leftKind == AstNodeCategory.TypeIntLit) {
        return xmlAttr(left, AstNodeAttributeKind.Text) == xmlAttr(right, AstNodeAttributeKind.Text)
    }
    if (leftKind == AstNodeCategory.TypeNamed) {
        return xmlAttr(left, AstNodeAttributeKind.Name) == xmlAttr(right, AstNodeAttributeKind.Name)
    }
    if (leftKind == AstNodeCategory.TypeGeneric) {
        if (xmlAttr(left, AstNodeAttributeKind.Name) != xmlAttr(right, AstNodeAttributeKind.Name)) {
            return false
        }
        return semSameTypeList(xmlChildren(left, AstNodeKind.TypeArg), xmlChildren(right, AstNodeKind.TypeArg))
    }
    if (leftKind == AstNodeCategory.TypeReference || leftKind == AstNodeCategory.TypePointer) {
        return semSameType(*xmlChild(left, AstNodeKind.Inner), *xmlChild(right, AstNodeKind.Inner))
    }
    return false
}

fun semSameTypeList(left: List<AstXmlNode>, right: List<AstXmlNode>): Bool {
    if (left.size() != right.size()) {
        return false
    }
    var i: Int = 0
    while (i < left.size()) {
        if (!semSameType(*left[i], *right[i])) {
            return false
        }
        i = i + 1
    }
    return true
}

// Records `name := type`, refusing a second, different binding. A pattern type
// parameter that stays unbound is not an error here: the caller finds out when it
// substitutes the result type and nothing is left to spell.
fun semBindOne(bindings: *Dictionary<Str, AstXmlNode>, name: Str, typeNode: AstXmlNode): Bool {
    if (!bindings.has(name)) {
        bindings.insert(name, typeNode)
        return true
    }
    return semSameType(*bindings.get(name).value(), *typeNode)
}

// The binding form of unification: what each pattern type parameter matched
// (`List<T>` against `List<Str>` binds T := Str). Mirrors `unifyType`, which the
// emitter uses when it only needs a yes/no.
// Whether a receiver pattern matches an actual type, with the pattern's type parameters
// matching anything (`sema::unifyType` in the C++ ring). It is what answers "does a
// `smToYield` take this receiver?" for the `for` check (`Sema.kt`).
fun semUnifyType(pattern: *AstXmlNode, actual: *AstXmlNode, typeParams: *List<Str>): Bool {
    var actualPtr: AstXmlNode = copy(actual)
    val patternKind: AstNodeCategory = xmlKind(pattern)
    if (patternKind != AstNodeCategory.TypeReference && patternKind != AstNodeCategory.TypePointer) {
        while (true) {
            val actualKind: AstNodeCategory = xmlKind(*actualPtr)
            if (actualKind == AstNodeCategory.TypeReference || actualKind == AstNodeCategory.TypePointer) {
                val inner: AstXmlNode = xmlChild(*actualPtr, AstNodeKind.Inner)
                if (xmlIsEmpty(*inner)) {
                    break
                }
                actualPtr = inner
            } else {
                break
            }
        }
    }
    val ak: AstNodeCategory = xmlKind(*actualPtr)
    if (patternKind == AstNodeCategory.TypeIntLit) {
        return ak == AstNodeCategory.TypeIntLit && xmlAttr(*actualPtr, AstNodeAttributeKind.Text) == xmlAttr(
            pattern,
            AstNodeAttributeKind.Text
        )
    }
    if (patternKind == AstNodeCategory.TypeNamed) {
        if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
            return true
        }
        return ak == AstNodeCategory.TypeNamed && xmlAttr(*actualPtr, AstNodeAttributeKind.Name) == xmlAttr(
            pattern,
            AstNodeAttributeKind.Name
        )
    }
    if (patternKind == AstNodeCategory.TypeGeneric) {
        if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
            return true
        }
        if (ak != AstNodeCategory.TypeGeneric) {
            return false
        }
        val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
        val actualName: Str = xmlAttr(*actualPtr, AstNodeAttributeKind.Name)
        // `PList<T>` is the alias of `&List<T>`; match it against a `List<T>` pattern (the
        // call dereferences).
        if (actualName != patternName
            && !(patternName == "List" && actualName == "PList")
            && !(patternName == "PList" && actualName == "List")
        ) {
            return false
        }
        val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
        val actualArgs: List<AstXmlNode> = xmlChildren(*actualPtr, AstNodeKind.TypeArg)
        if (patternArgs.size() != actualArgs.size()) {
            return false
        }
        var i: Int = 0
        while (i < patternArgs.size()) {
            if (!semUnifyType(*patternArgs[i], *actualArgs[i], typeParams)) {
                return false
            }
            i = i + 1
        }
        return true
    }
    if (patternKind == AstNodeCategory.TypeReference) {
        if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
            && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
        ) {
            return semUnifyType(
                *xmlChild(pattern, AstNodeKind.Inner),
                *xmlChild(*actualPtr, AstNodeKind.Inner),
                typeParams
            )
        }
        return false
    }
    if (patternKind == AstNodeCategory.TypePointer) {
        if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
            && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
        ) {
            return semUnifyType(
                *xmlChild(pattern, AstNodeKind.Inner),
                *xmlChild(*actualPtr, AstNodeKind.Inner),
                typeParams
            )
        }
        return false
    }
    // `..T` is a state machine (impl_specs/yield.md) and carries its element type the way
    // a pointer carries its pointee, so a pattern `..T` matches `..Int` element-wise.
    if (patternKind == AstNodeCategory.TypeYield) {
        if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
            && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
        ) {
            return semUnifyType(
                *xmlChild(pattern, AstNodeKind.Inner),
                *xmlChild(*actualPtr, AstNodeKind.Inner),
                typeParams
            )
        }
        return false
    }
    return false
}

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
    var actualPtr: AstXmlNode = copy(actual)
    val patternKind: AstNodeCategory = xmlKind(pattern)
    if (patternKind != AstNodeCategory.TypeReference && patternKind != AstNodeCategory.TypePointer) {
        while (true) {
            val actualKind: AstNodeCategory = xmlKind(*actualPtr)
            if (actualKind == AstNodeCategory.TypeReference || actualKind == AstNodeCategory.TypePointer) {
                val inner: AstXmlNode = xmlChild(*actualPtr, AstNodeKind.Inner)
                if (xmlIsEmpty(*inner)) {
                    break
                }
                actualPtr = inner
            } else {
                break
            }
        }
    }
    val ak: AstNodeCategory = xmlKind(*actualPtr)
    if (patternKind == AstNodeCategory.TypeIntLit) {
        return ak == AstNodeCategory.TypeIntLit
                && xmlAttr(*actualPtr, AstNodeAttributeKind.Text) == xmlAttr(pattern, AstNodeAttributeKind.Text)
    }
    if (patternKind == AstNodeCategory.TypeNamed) {
        if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
            return semBindOne(bindings, xmlAttr(pattern, AstNodeAttributeKind.Name), actualPtr)
        }
        return ak == AstNodeCategory.TypeNamed
                && xmlAttr(*actualPtr, AstNodeAttributeKind.Name) == xmlAttr(pattern, AstNodeAttributeKind.Name)
    }
    if (patternKind == AstNodeCategory.TypeGeneric) {
        if (xmlIsTypeParam(xmlAttr(pattern, AstNodeAttributeKind.Name), typeParams)) {
            return semBindOne(bindings, xmlAttr(pattern, AstNodeAttributeKind.Name), actualPtr)
        }
        if (ak != AstNodeCategory.TypeGeneric) {
            return false
        }
        val patternName: Str = xmlAttr(pattern, AstNodeAttributeKind.Name)
        val actualName: Str = xmlAttr(*actualPtr, AstNodeAttributeKind.Name)
        // `PList<T>` is the alias of `&List<T>`; match it against a `List<T>`
        // receiver pattern (the call dereferences).
        if (actualName != patternName
            && !(patternName == "List" && actualName == "PList")
            && !(patternName == "PList" && actualName == "List")
        ) {
            return false
        }
        val patternArgs: List<AstXmlNode> = xmlChildren(pattern, AstNodeKind.TypeArg)
        val actualArgs: List<AstXmlNode> = xmlChildren(*actualPtr, AstNodeKind.TypeArg)
        if (patternArgs.size() != actualArgs.size()) {
            return false
        }
        var i: Int = 0
        while (i < patternArgs.size()) {
            if (!semBindTypes(*patternArgs[i], *actualArgs[i], typeParams, bindings)) {
                return false
            }
            i = i + 1
        }
        return true
    }
    if (patternKind == AstNodeCategory.TypeReference) {
        if (ak == AstNodeCategory.TypeReference && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
            && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
        ) {
            return semBindTypes(
                *xmlChild(pattern, AstNodeKind.Inner),
                *xmlChild(*actualPtr, AstNodeKind.Inner),
                typeParams,
                bindings
            )
        }
        return false
    }
    if (patternKind == AstNodeCategory.TypePointer) {
        if (ak == AstNodeCategory.TypePointer && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
            && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
        ) {
            return semBindTypes(
                *xmlChild(pattern, AstNodeKind.Inner),
                *xmlChild(*actualPtr, AstNodeKind.Inner),
                typeParams,
                bindings
            )
        }
        return false
    }
    // `..T` is a state machine (impl_specs/yield.md) and carries its element type the way
    // a pointer carries its pointee, so its parameter is bound the same way.
    if (patternKind == AstNodeCategory.TypeYield) {
        if (ak == AstNodeCategory.TypeYield && !xmlIsEmpty(*xmlChild(*actualPtr, AstNodeKind.Inner))
            && !xmlIsEmpty(*xmlChild(pattern, AstNodeKind.Inner))
        ) {
            return semBindTypes(
                *xmlChild(pattern, AstNodeKind.Inner),
                *xmlChild(*actualPtr, AstNodeKind.Inner),
                typeParams,
                bindings
            )
        }
        return false
    }
    return false
}

// Replaces every type parameter of `typeNode` from `bindings`. An empty result means
// one of them was unbound and there is nothing to spell. The result is re-rooted as
// a standalone type (`AstNodeKind.Type`); the caller re-roots it for the place it
// puts it in.
fun semSubstitute(typeNode: *AstXmlNode, bindings: *Dictionary<Str, AstXmlNode>, typeParams: *List<Str>): AstXmlNode {
    if (xmlIsEmpty(typeNode)) {
        return xmlEmptyNode()
    }
    val kind: AstNodeCategory = xmlKind(typeNode)
    if (kind == AstNodeCategory.TypeIntLit) {
        return semReRole(copy(typeNode), AstNodeKind.Type)
    }
    if (kind == AstNodeCategory.TypeNamed) {
        val name: Str = xmlAttr(typeNode, AstNodeAttributeKind.Name)
        if (!xmlIsTypeParam(name, typeParams)) {
            return semReRole(copy(typeNode), AstNodeKind.Type)
        }
        if (!bindings.has(name)) {
            return xmlEmptyNode()
        }
        return semReRole(bindings.get(name).value(), AstNodeKind.Type)
    }
    if (kind == AstNodeCategory.TypeGeneric) {
        var args: List<AstXmlNode> = List<AstXmlNode>()
        val existing: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
        var i: Int = 0
        while (i < existing.size()) {
            val mapped: AstXmlNode = semSubstitute(*existing[i], bindings, typeParams)
            if (xmlIsEmpty(*mapped)) {
                return xmlEmptyNode()
            }
            args.append(semReRole(mapped, AstNodeKind.TypeArg))
            i = i + 1
        }
        return semReRole(semReplaceRole(typeNode, AstNodeKind.TypeArg, args), AstNodeKind.Type)
    }
    if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer
        || kind == AstNodeCategory.TypeYield
    ) {
        // `..T` (a machine yielding `T`, impl_specs/yield.md) carries its element type the
        // same way a pointer carries its pointee, so it substitutes the same way: the
        // type is unspellable either way, but its *element* type is what a `for`'s loop
        // variable is typed from.
        val inner: AstXmlNode = semSubstitute(*xmlChild(typeNode, AstNodeKind.Inner), bindings, typeParams)
        if (xmlIsEmpty(*inner)) {
            return xmlEmptyNode()
        }
        return semReRole(
            semReplaceRole(typeNode, AstNodeKind.Inner, semOne(semReRole(inner, AstNodeKind.Inner))),
            AstNodeKind.Type
        )
    }
    if (kind == AstNodeCategory.TypeFunction) {
        var params: List<AstXmlNode> = List<AstXmlNode>()
        val existingParams: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
        var i: Int = 0
        while (i < existingParams.size()) {
            val mapped: AstXmlNode = semSubstitute(*existingParams[i], bindings, typeParams)
            if (xmlIsEmpty(*mapped)) {
                return xmlEmptyNode()
            }
            params.append(semReRole(mapped, AstNodeKind.ParamType))
            i = i + 1
        }
        val ret: AstXmlNode = semSubstitute(*xmlChild(typeNode, AstNodeKind.ReturnType), bindings, typeParams)
        if (xmlIsEmpty(*ret)) {
            return xmlEmptyNode()
        }
        val withParams: AstXmlNode = semReplaceRole(typeNode, AstNodeKind.ParamType, params)
        return semReRole(
            semReplaceRole(*withParams, AstNodeKind.ReturnType, semOne(semReRole(ret, AstNodeKind.ReturnType))),
            AstNodeKind.Type
        )
    }
    return xmlEmptyNode()
}

// ---- the facts and the body context ---------------------------------------

// A program-level function/method fact the inference resolves a call with.
data class SemFnFact(var decl: AstXmlNode;

var receiver: AstXmlNode;
var templateParams: List<Str>)

// A `native fun` extension (`this` first parameter): its receiver pattern picks the
// overload and its return type answers the call.
data class SemExtFact(var receiver: AstXmlNode;

var returnType: AstXmlNode;
var typeParams: List<Str>)

// Everything about the program the inference reads. The emitter fills this in from
// its own symbol collection; it holds the same nodes, so filling it copies no
// declarations.
data class SemFacts(
    var types: Dictionary<Str, AstXmlNode>;

var enumNames: Dictionary<Str, Bool>;
var functions: List<SemFnFact>;
var nativeExtensions: Dictionary<Str, List<SemExtFact>>;
var statics: Dictionary<Str, AstXmlNode>
)

fun semNewFacts(): SemFacts {
    return SemFacts(
        Dictionary<Str, AstXmlNode>(), Dictionary<Str, Bool>(), List<SemFnFact>(),
        Dictionary<Str, List<SemExtFact>>(), Dictionary<Str, AstXmlNode>()
    )
}

// One function-like body being annotated: its declaration (which carries the
// parameters), the type of `this`, and the type parameters in scope (the class's
// plus the function's).
//
// A *lambda* body has no declaration. Its frame is its own parameters plus the values
// it captures, which the language models as fields of the closure instance
// (specs/memory-model.md): inside the body a captured name simply *has* that type, so
// the pass seeds it like a parameter. `paramNames` and `paramTypes` are parallel, and
// a parameter's type may be missing where the callable type the lambda is used
// against supplies it.
data class SemBody(
    var decl: AstXmlNode;

var typeParams: List<Str>;
var selfType: AstXmlNode;
var paramNames: List<Str>;
var paramTypes: List<AstXmlNode>;
var captures: Dictionary<Str, AstXmlNode>
)

// ---- the pass -------------------------------------------------------------

// Both the facts and the body are **borrowed**, not copied: the pass only reads
// them, and a body is annotated once per function, so copying the program tables
// (hundreds of functions, three dictionaries) per body would dominate the run.
data class SemInfer(
    var facts: *SemFacts;
    var body: *SemBody;
    var scopes: List<Dictionary<Str, AstXmlNode>>;

// The flat record of every binding the pass proved, whatever a declaration can
// spell - a name holding a state machine is `..T`, and `Stmt.type` never carries
// that. A frame is keyed by name (the lowering gives each scope its own
// variables), so this is what the backend seeds a body's frame from.
var types: Dictionary<Str, AstXmlNode>
) {
    fun pushScope(): Unit {
        this.scopes.append(Dictionary<Str, AstXmlNode>())
    }

    fun popScope(): Unit {
        this.scopes.removeAt(this.scopes.size() - 1)
    }

    // Records a binding in the scope being built *and* in the flat record.
    fun mark(name: Str, typeNode: AstXmlNode): Unit {
        if (this.scopes.size() == 0) {
            return
        }
        this.scopes[this.scopes.size() - 1].insert(name, typeNode)
        this.types.insert(name, typeNode)
    }

    fun lookup(name: Str): AstXmlNode {
        var i: Int = this.scopes.size() - 1
        while (i >= 0) {
            if (this.scopes[i].has(name)) {
                return this.scopes[i].get(name).value()
            }
            i = i - 1
        }
        return xmlEmptyNode()
    }

    // A declaration is annotated only when its initializer is an expression whose
    // *value* the C++ type of the declaration can name. A lambda needs its expected
    // callable type and `null` has no type of its own; everything else - including
    // `&x`, `*x` and `copy(x)` - is typed below, so the emitter never has to fall
    // back to `auto` for it.
    fun declarable(init: *AstXmlNode): Bool {
        val kind: AstNodeCategory = xmlKind(init)
        if (kind == AstNodeCategory.ExprLambda || kind == AstNodeCategory.ExprNullLit) {
            return false
        }
        return true
    }

    fun spellableName(name: Str): Bool {
        if (name == "Unit") {
            return false // `void` has no values
        }
        if (xmlIsTypeParam(name, *this.body.typeParams)) {
            return true
        }
        if (this.facts.types.has(name)) {
            return true
        }
        return semIsRtlTypeName(name)
    }

    // Whether the emitter can spell this type in this body. A type parameter that is
    // not in scope would name nothing in the emitted C++ and an undeclared name would
    // make the emitter fail; both mean "leave it to `auto`" instead.
    fun spellable(typeNode: *AstXmlNode): Bool {
        if (xmlIsEmpty(typeNode)) {
            return false
        }
        val kind: AstNodeCategory = xmlKind(typeNode)
        if (kind == AstNodeCategory.TypeIntLit) {
            return true
        }
        if (kind == AstNodeCategory.TypeNamed) {
            return this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))
        }
        if (kind == AstNodeCategory.TypeGeneric) {
            if (!this.spellableName(xmlAttr(typeNode, AstNodeAttributeKind.Name))) {
                return false
            }
            val args: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.TypeArg)
            var i: Int = 0
            while (i < args.size()) {
                if (!this.spellable(*args[i])) {
                    return false
                }
                i = i + 1
            }
            return true
        }
        if (kind == AstNodeCategory.TypeReference || kind == AstNodeCategory.TypePointer) {
            return this.spellable(*xmlChild(typeNode, AstNodeKind.Inner))
        }
        if (kind == AstNodeCategory.TypeFunction) {
            if (!this.spellable(*xmlChild(typeNode, AstNodeKind.ReturnType))) {
                return false
            }
            val params: List<AstXmlNode> = xmlChildren(typeNode, AstNodeKind.ParamType)
            var i: Int = 0
            while (i < params.size()) {
                if (!this.spellable(*params[i])) {
                    return false
                }
                i = i + 1
            }
            return true
        }
        return false
    }

    fun isTypeName(name: Str): Bool {
        return this.facts.types.has(name) || semIsRtlTypeName(name)
    }

    // ---- statements -------------------------------------------------------

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
        val kind: AstNodeCategory = xmlKind(*stmtNode)
        if (kind == AstNodeCategory.StmtVarDecl) {
            val declared: AstXmlNode = xmlChild(*stmtNode, AstNodeKind.Type)
            val init: AstXmlNode = xmlChild(*stmtNode, AstNodeKind.Init)
            var typeNode: AstXmlNode = copy(declared)
            if (xmlIsEmpty(*typeNode) && !xmlIsEmpty(*init)) {
                typeNode = this.infer(*init)
            }
            val name: Str = xmlAttr(*stmtNode, AstNodeAttributeKind.Name)
            if (!xmlIsEmpty(*typeNode)) {
                this.mark(name, typeNode)
            }
            if (!xmlIsEmpty(*declared) || xmlIsEmpty(*typeNode) || xmlIsEmpty(*init)
                || !this.declarable(*init) || !this.spellable(*typeNode)
            ) {
                return copy(stmtNode)
            }
            return semWithType(*stmtNode, semReRole(typeNode, AstNodeKind.Type))
        }
        if (kind == AstNodeCategory.StmtBlock) {
            this.pushScope()
            val inner: List<AstXmlNode> = xmlChildren(*xmlChild(*stmtNode, AstNodeKind.Body), AstNodeKind.Stmt)
            val rebuilt: List<AstXmlNode> = this.stmts(*inner)
            this.popScope()
            var body: AstXmlNode =
                AstXmlNode(AstNodeKind.Body, AstNodeCategory.None, List<AstNodeAttribute>(), rebuilt.toArray())
            return semReplaceRole(*stmtNode, AstNodeKind.Body, semOne(body))
        }
        return copy(stmtNode)
    }

    // ---- expressions ------------------------------------------------------

    fun handle(kind: AstNodeCategory, inner: AstXmlNode): AstXmlNode {
        if (xmlIsEmpty(*inner)) {
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
        if (xmlIsEmpty(*memberType)) {
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
        return semSubstitute(*memberType, *bindings, *classParams)
    }

    // The return type of a call, with the callee's type parameters bound from an
    // explicit instantiation (`identity<Int>(7)`) or from the receiver
    // (`Box<Int>.get()`). A function whose result still mentions a type parameter is
    // *not* monomorphized here - it stays symbolic and the emitted C++ template
    // specializes it later - but a parameter nothing binds leaves no type to spell,
    // so the call answers with an empty node.
    fun functionReturn(name: Str, typeArgs: List<AstXmlNode>, receiver: AstXmlNode): AstXmlNode {
        var i: Int = 0
        while (i < this.facts.functions.size()) {
            val fn: *SemFnFact = *this.facts.functions[i]
            i = i + 1
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) != name) {
                continue
            }
            val ret: AstXmlNode = xmlChild(*fn.decl, AstNodeKind.ReturnType)
            if (xmlIsEmpty(*ret)) {
                continue
            }
            val hasReceiver: Bool = !xmlIsEmpty(*fn.receiver)
            if (hasReceiver != !xmlIsEmpty(*receiver)) {
                continue
            }
            var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
            if (hasReceiver) {
                if (!semBindTypes(*fn.receiver, *receiver, *fn.templateParams, *bindings)) {
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
                        bound = semBindOne(*bindings, fn.templateParams[a], typeArgs[a])
                    }
                    a = a + 1
                }
                if (!bound) {
                    continue
                }
            }
            val result: AstXmlNode = semSubstitute(*ret, *bindings, *fn.templateParams)
            if (!xmlIsEmpty(*result)) {
                return result
            }
        }
        return xmlEmptyNode()
    }

    // The result of a member call (`recv.name(...)`), resolved the way the emitter
    // lowers it: a Simse-declared extension/method first, then a native extension,
    // then the built-in accessors.
    fun memberReturn(callee: *AstXmlNode): AstXmlNode {
        val receiverType: AstXmlNode = this.infer(*xmlChild(callee, AstNodeKind.Receiver))
        val recv: AstXmlNode = semPointee(*receiverType)
        if (xmlIsEmpty(*recv)) {
            return xmlEmptyNode()
        }
        val calleeText: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
        var i: Int = 0
        while (i < this.facts.functions.size()) {
            val fn: *SemFnFact = *this.facts.functions[i]
            i = i + 1
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.IsNative) == "true" || xmlIsEmpty(*fn.receiver)) {
                continue
            }
            if (xmlAttr(*fn.decl, AstNodeAttributeKind.Name) != calleeText) {
                continue
            }
            val ret: AstXmlNode = xmlChild(*fn.decl, AstNodeKind.ReturnType)
            if (xmlIsEmpty(*ret)) {
                continue
            }
            var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
            if (!semBindTypes(*fn.receiver, *recv, *fn.templateParams, *bindings)) {
                continue
            }
            val result: AstXmlNode = semSubstitute(*ret, *bindings, *fn.templateParams)
            if (!xmlIsEmpty(*result)) {
                return result
            }
        }
        if (this.facts.nativeExtensions.has(calleeText)) {
            val extensions: List<SemExtFact> = this.facts.nativeExtensions.get(calleeText).value()
            var e: Int = 0
            while (e < extensions.size()) {
                val ext: *SemExtFact = *extensions[e]
                e = e + 1
                if (xmlIsEmpty(*ext.receiver) || xmlIsEmpty(*ext.returnType)) {
                    continue
                }
                var bindings: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
                if (!semBindTypes(*ext.receiver, *recv, *ext.typeParams, *bindings)) {
                    continue
                }
                val result: AstXmlNode = semSubstitute(*ext.returnType, *bindings, *ext.typeParams)
                if (!xmlIsEmpty(*result)) {
                    return result
                }
            }
        }
        if (xmlKind(*recv) == AstNodeCategory.TypeYield) {
            // `..T` is a state machine (impl_specs/yield.md), and its two methods are part
            // of the lowering's ABI: `next()` hands out the optional, `advance(*v)` answers
            // whether there was a value. Typing them here is what makes a `for`'s loop
            // variable a *typed* binding rather than an `auto` the emitter would have to
            // guess a symbol for.
            if (calleeText == "next") {
                val element: AstXmlNode = xmlChild(*recv, AstNodeKind.Inner)
                if (!xmlIsEmpty(*element)) {
                    var args: List<AstXmlNode> = List<AstXmlNode>()
                    args.append(semReRole(element, AstNodeKind.TypeArg))
                    return semGenericType("Opt", args)
                }
            }
            if (calleeText == "advance") {
                return semNamedType("Bool")
            }
            // A machine is already iterable: `x.smToYield()` on one is `x`, so the wrap a
            // `for` puts around what it iterates is the identity there (impl_specs/for.md).
            // `..T` is not a spellable type, so no function could take one.
            if (calleeText == "smToYield") {
                return receiverType
            }
        }
        if (xmlKind(*recv) == AstNodeCategory.TypeGeneric) {
            val typeArgs: List<AstXmlNode> = xmlChildren(*recv, AstNodeKind.TypeArg)
            val recvName: Str = xmlAttr(*recv, AstNodeAttributeKind.Name)
            if (calleeText == "value" && recvName == "Opt" && typeArgs.size() > 0) {
                return semReRole(typeArgs[0], AstNodeKind.Type)
            }
            if ((calleeText == "size" || calleeText == "count")
                && (recvName == "List" || recvName == "Array" || recvName == "Dictionary" || recvName == "SmallVector")
            ) {
                return semNamedType("Int")
            }
        }
        if (xmlKind(*recv) == AstNodeCategory.TypeNamed && calleeText == "size"
            && xmlAttr(*recv, AstNodeAttributeKind.Name) == "Str"
        ) {
            return semNamedType("Int")
        }
        if (calleeText == "isOk" || calleeText == "hasValue") {
            return semNamedType("Bool")
        }
        return xmlEmptyNode()
    }

    fun callReturn(callee: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(callee)
        if (kind == AstNodeCategory.ExprGenericName) {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.isTypeName(name)) {
                return semGenericType(name, xmlChildren(callee, AstNodeKind.TypeArg))
            }
            return this.functionReturn(name, xmlChildren(callee, AstNodeKind.TypeArg), xmlEmptyNode())
        }
        if (kind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(callee, AstNodeAttributeKind.Name)
            if (this.isTypeName(name)) {
                return semNamedType(name)
            }
            return this.functionReturn(name, List<AstXmlNode>(), xmlEmptyNode())
        }
        if (kind == AstNodeCategory.ExprMember) {
            return this.memberReturn(callee)
        }
        return xmlEmptyNode()
    }

    fun infer(e: *AstXmlNode): AstXmlNode {
        val kind: AstNodeCategory = xmlKind(e)
        if (kind == AstNodeCategory.ExprIntLit) {
            return semNamedType("Int")
        }
        if (kind == AstNodeCategory.ExprFloatLit) {
            return semNamedType("Float64")
        }
        if (kind == AstNodeCategory.ExprStrLit) {
            return semNamedType("Str")
        }
        if (kind == AstNodeCategory.ExprCharLit) {
            return semNamedType("Char")
        }
        if (kind == AstNodeCategory.ExprBoolLit) {
            return semNamedType("Bool")
        }
        if (kind == AstNodeCategory.ExprNullLit) {
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (name == "this") {
                return semReRole(copy(this.body.selfType), AstNodeKind.Type)
            }
            val local: AstXmlNode = this.lookup(name)
            if (!xmlIsEmpty(*local)) {
                return local
            }
            // File-level static storage (specs/statics.md).
            if (this.facts.statics.has(name)) {
                return semReRole(this.facts.statics.get(name).value(), AstNodeKind.Type)
            }
            // A bare enum type name used as the receiver of a static conversion.
            if (this.facts.enumNames.has(name)) {
                return semNamedType(name)
            }
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprGenericName) {
            val name: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (!this.isTypeName(name)) {
                return xmlEmptyNode()
            }
            return semGenericType(name, xmlChildren(e, AstNodeKind.TypeArg))
        }
        if (kind == AstNodeCategory.ExprMember) {
            val lhs: AstXmlNode = xmlChild(e, AstNodeKind.Receiver)
            // An enum member expression has the enum's type.
            if (xmlKind(*lhs) == AstNodeCategory.ExprName
                && this.facts.enumNames.has(xmlAttr(*lhs, AstNodeAttributeKind.Name))
            ) {
                return semNamedType(xmlAttr(*lhs, AstNodeAttributeKind.Name))
            }
            val baseType: AstXmlNode = this.infer(*lhs)
            val base: AstXmlNode = semPointee(*baseType)
            if (xmlIsEmpty(*base)) {
                return xmlEmptyNode()
            }
            val memberText: Str = xmlAttr(e, AstNodeAttributeKind.Name)
            if (xmlKind(*base) == AstNodeCategory.TypeGeneric && xmlAttr(*base, AstNodeAttributeKind.Name) == "Res") {
                val typeArgs: List<AstXmlNode> = xmlChildren(*base, AstNodeKind.TypeArg)
                if (memberText == "value" && typeArgs.size() > 0) {
                    return semReRole(typeArgs[0], AstNodeKind.Type)
                }
                if (memberText == "error") {
                    return semNamedType("Str")
                }
            }
            val baseKind: AstNodeCategory = xmlKind(*base)
            if (baseKind == AstNodeCategory.TypeNamed || baseKind == AstNodeCategory.TypeGeneric) {
                val baseName: Str = xmlAttr(*base, AstNodeAttributeKind.Name)
                if (this.facts.types.has(baseName)) {
                    val decl: AstXmlNode = this.facts.types.get(baseName).value()
                    if (decl.name == AstNodeKind.DataClass) {
                        val fields: List<AstXmlNode> = xmlChildren(*decl, AstNodeKind.Field)
                        var i: Int = 0
                        while (i < fields.size()) {
                            if (xmlAttr(*fields[i], AstNodeAttributeKind.Name) == memberText) {
                                return this.instantiate(*decl, *base, xmlChild(*fields[i], AstNodeKind.Type))
                            }
                            i = i + 1
                        }
                    }
                }
            }
            return xmlEmptyNode()
        }
        if (kind == AstNodeCategory.ExprCall) {
            return this.callReturn(*xmlChild(e, AstNodeKind.Callee))
        }
        if (kind == AstNodeCategory.ExprIndex) {
            val baseType: AstXmlNode = this.infer(*xmlChild(e, AstNodeKind.Receiver))
            val base: AstXmlNode = semPointee(*baseType)
            if (xmlIsEmpty(*base)) {
                return xmlEmptyNode()
            }
            if (xmlKind(*base) == AstNodeCategory.TypeNamed && xmlAttr(*base, AstNodeAttributeKind.Name) == "Str") {
                return semNamedType("Char")
            }
            if (xmlKind(*base) != AstNodeCategory.TypeGeneric) {
                return xmlEmptyNode()
            }
            val typeArgs: List<AstXmlNode> = xmlChildren(*base, AstNodeKind.TypeArg)
            if (typeArgs.size() == 0) {
                return xmlEmptyNode()
            }
            val baseName: Str = xmlAttr(*base, AstNodeAttributeKind.Name)
            if (baseName == "SmallVector" && typeArgs.size() == 2) {
                return semReRole(typeArgs[1], AstNodeKind.Type) // <N, T>
            }
            if (baseName == "Dictionary" && typeArgs.size() == 2) {
                return semReRole(typeArgs[1], AstNodeKind.Type)
            }
            return semReRole(typeArgs[0], AstNodeKind.Type)
        }
        if (kind == AstNodeCategory.ExprRef) {
            // `&x` boxes a copy for the call (`std::make_shared<T>(x)`), so the C++
            // type is a counted reference to whatever `x` is.
            return this.handle(AstNodeCategory.TypeReference, this.infer(*xmlChild(e, AstNodeKind.Operand)))
        }
        if (kind == AstNodeCategory.ExprDeref) {
            // `*x` is the *address* of what `x` denotes: of a value's own storage
            // (`&x`), of a counted reference's pointee (`x.get()`), or the pointer
            // itself when `x` already is one - in which case the emitter reads
            // through it, so the type is the pointee.
            val operand: AstXmlNode = this.infer(*xmlChild(e, AstNodeKind.Operand))
            if (xmlIsEmpty(*operand)) {
                return xmlEmptyNode()
            }
            val operandKind: AstNodeCategory = xmlKind(*operand)
            if (operandKind == AstNodeCategory.TypePointer) {
                return semReRole(xmlChild(*operand, AstNodeKind.Inner), AstNodeKind.Type)
            }
            if (operandKind == AstNodeCategory.TypeReference) {
                return this.handle(AstNodeCategory.TypePointer, xmlChild(*operand, AstNodeKind.Inner))
            }
            return this.handle(AstNodeCategory.TypePointer, operand)
        }
        if (kind == AstNodeCategory.ExprCopy) {
            // `copy(x)` is the *value*: a plain read of a value, or the pointee of a
            // handle (`*(x)`).
            val operand: AstXmlNode = this.infer(*xmlChild(e, AstNodeKind.Operand))
            val value: AstXmlNode = semPointee(*operand)
            if (xmlIsEmpty(*value)) {
                return xmlEmptyNode()
            }
            return semReRole(value, AstNodeKind.Type)
        }
        if (kind == AstNodeCategory.ExprUnary) {
            return this.infer(*xmlChild(e, AstNodeKind.Operand))
        }
        if (kind == AstNodeCategory.ExprBinary) {
            val op: Str = xmlAttr(e, AstNodeAttributeKind.Op)
            if (op == "==" || op == "!=" || op == "<" || op == ">" || op == "<=" || op == ">="
                || op == "&&" || op == "||"
            ) {
                return semNamedType("Bool")
            }
            return this.infer(*xmlChild(e, AstNodeKind.Lhs))
        }
        // A lambda's type comes from the callable type it is used against, which is
        // the emitter's business (its parameters may even be inferred from there).
        return xmlEmptyNode()
    }
}

// Fills in the type of every untyped `VarDecl` in `body` the pass can prove; the
// statements that gain nothing come back as they are. `inferred` receives what the
// pass proved for every name - including the ones a C++ declaration cannot spell
// (`..T`), which the frame still needs (see `SemInfer.types`).
fun semInferTypes(
    body: *List<AstXmlNode>, facts: *SemFacts, ctx: *SemBody,
    inferred: *Dictionary<Str, AstXmlNode>
): List<AstXmlNode> {
    val infer: SemInfer = SemInfer(facts, ctx, List<Dictionary<Str, AstXmlNode>>(), Dictionary<Str, AstXmlNode>())
    infer.pushScope()
    val params: List<AstXmlNode> = xmlChildren(*ctx.decl, AstNodeKind.Param)
    var i: Int = 0
    while (i < params.size()) {
        val paramType: AstXmlNode = xmlChild(*params[i], AstNodeKind.Type)
        if (!xmlIsEmpty(*paramType)) {
            infer.mark(xmlAttr(*params[i], AstNodeAttributeKind.Name), paramType)
        }
        i = i + 1
    }
    // A lambda's frame: its own parameters (a type may be missing where the callable
    // type it is used against supplies it), then the values it captures, which are
    // fields of the closure instance and therefore simply have their type inside the
    // body.
    i = 0
    while (i < ctx.paramNames.size()) {
        if (i < ctx.paramTypes.size() && !xmlIsEmpty(*ctx.paramTypes[i])) {
            infer.mark(ctx.paramNames[i], ctx.paramTypes[i])
        }
        i = i + 1
    }
    val captureNames: List<Str> = ctx.captures.keys()
    i = 0
    while (i < captureNames.size()) {
        infer.mark(captureNames[i], ctx.captures.get(captureNames[i]).value())
        i = i + 1
    }
    val out: List<AstXmlNode> = infer.stmts(body)
    infer.popScope()
    val proven: List<Str> = infer.types.keys()
    i = 0
    while (i < proven.size()) {
        inferred.insert(proven[i], infer.types.get(proven[i]).value())
        i = i + 1
    }
    return out
}
