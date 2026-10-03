// CgResolve.kt
//
// Symbol and type resolution: extension/overload/receiver lookups, member access, alias
// and callable resolution. Extension methods on `Emitter` (Codegen.kt).

package codegen
import compiler

import sema
import common
import linear
import optimizations
import profiling
import resources

// Re-roots `child` under `role` (a shallow copy whose element name changes).
fun Emitter.renameRole(child: *AstXmlNode, role: AstNodeKind): AstXmlNode {
    var renamed: AstXmlNode = child
    renamed.name = role
    return renamed
}

// The extension function a member call names, for a receiver whose type is already known
// (the IL's slot types - `ilCallNode` attaches the explicit template arguments a
// machine-receiver call needs).
fun Emitter.findExtensionFnByType(name: *Str, recv: AstXmlNode, argCount: Int): Int {
    if (xmlIsEmpty(recv)) {
        return -1
    }
    var i: Int = 0
    while (i < this.functions.size()) {
        val fn: *CgFn = *this.functions[i]
        i = i + 1
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name != *name || fn.paramCount != argCount) {
            continue
        }
        if (this.unifyType(this.resolveAlias(fn.receiver), recv, fn.templateParams)) {
            return i - 1
        }
    }
    return -1
}

// The receiver argument for a lowered Simse call: a value receiver is a raw pointer, so
// the argument is the receiver's address (`simse_addressOf`, src/rtl/types.hpp); a
// counted reference is unwrapped with `.get()`, and a bare `this` is already that pointer.
fun Emitter.receiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
    if (this.isHandleType(pattern)) {
        return this.expr(recv, 12, xmlEmptyNode())
    }
    if (this.selfKind == NameKind.Value && xmlKind(recv) == AstNodeCategory.ExprName
        && xmlAttr(recv, AstNodeAttributeKind.Name) == "this"
    ) {
        return this.selfPointer()
    }
    val recvType: AstXmlNode = this.inferType(recv)
    if (!xmlIsEmpty(recvType)) {
        val kind: AstNodeCategory = xmlKind(recvType)
        if (kind == AstNodeCategory.TypeReference
            || (kind == AstNodeCategory.TypeGeneric && xmlAttr(recvType, AstNodeAttributeKind.Name) == "PList")
        ) {
            val exprText: Str = this.expr(recv, 12, xmlEmptyNode())
            return `(@exprText).get()`
        }
        if (kind == AstNodeCategory.TypePointer) {
            return this.expr(recv, 12, xmlEmptyNode())
        }
    }
    val exprText2: Str = this.expr(recv, 12, xmlEmptyNode())
    return `simse_addressOf(@exprText2)`
}

// The emitted receiver as the raw pointer it already is: `self`, or C++'s `this` inside a
// closure class. That pointer is the receiver's address, so a borrow of `this` spells no
// dereference.
fun Emitter.selfPointer(): Str {
    if (this.inClosureMethod) {
        return "this"
    }
    return "self"
}

// The receiver argument for a lowered native call: the host signature decides the form, so
// the expression passes as it is, dereferenced through a handle (the RTL's value receivers
// are `T&`).
fun Emitter.nativeReceiverArg(pattern: *AstXmlNode, recv: *AstXmlNode): Str {
    if (this.isHandleType(pattern)) {
        return this.expr(recv, 12, xmlEmptyNode())
    }
    val recvType: AstXmlNode = this.inferType(recv)
    if (this.isHandleType(recvType)) {
        val exprText3: Str = this.expr(recv, 12, xmlEmptyNode())
        return `(*@exprText3)`
    }
    return this.expr(recv, 12, xmlEmptyNode())
}

// Index into `functions` of the first Simse-declared receiver function with this name, or -1.
fun Emitter.findReceiverFnByName(name: *Str): Int {
    for ((*fn, i) in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name == name) {
            return i
        }
    }
    return -1
}

// Index into `functions` of a Simse extension matching the receiver and arity, or -1.
fun Emitter.findExtensionFn(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val recvType: AstXmlNode = this.inferType(recvExpr)
    val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
    if (xmlIsEmpty(recv)) {
        return -1
    }
    for ((*fn, i) in this.functions) {
        if (fn.isNative || xmlIsEmpty(fn.receiver)) {
            continue
        }
        if (fn.name == name && fn.paramCount == argCount && this.unifyType(
                this.resolveAlias(fn.receiver),
                recv,
                fn.templateParams
            )
        ) {
            return i
        }
    }
    return -1
}

// Index into `nativeExtensions[name]` of a matching receiver, or -1.
fun Emitter.findNativeExt(name: *Str, recvExpr: *AstXmlNode, argCount: Int): Int {
    val extensions: *List<CgNativeExt> = this.nativeExtensions.getPtr(name)
    if (extensions == null) {
        return -1
    }
    val recvType: AstXmlNode = this.inferType(recvExpr)
    val recv: AstXmlNode = this.resolveAlias(this.pointee(recvType))
    if (xmlIsEmpty(recv)) {
        return -1
    }
    for ((*ext, i) in extensions) {
        if (!xmlIsEmpty(ext.receiver) && ext.argCount == argCount
            && this.unifyType(this.resolveAlias(ext.receiver), recv, ext.typeParams)
        ) {
            return i
        }
    }
    return -1
}

fun Emitter.memberAccess(base: *AstXmlNode, name: *Str): Str {
    var arrow: Bool = false
    val baseType: AstXmlNode = this.inferType(base)
    if (xmlKind(base) == AstNodeCategory.ExprName
        && xmlAttr(base, AstNodeAttributeKind.Name) == "this" && this.selfKind == NameKind.Value
    ) {
        // A value receiver is a raw pointer (`T* self`), so its members are reached with `->`.
        arrow = true
    } else if (!xmlIsEmpty(baseType)) {
        arrow = this.isHandleType(baseType)
    } else if (xmlKind(base) == AstNodeCategory.ExprName) {
        val baseName: Str = xmlAttr(base, AstNodeAttributeKind.Name)
        if (baseName == "this") {
            arrow = this.selfKind != NameKind.Value
        } else {
            val kind: *NameKind = this.nameKinds.getPtr(baseName)
            if (kind != null && *kind == NameKind.Shared) {
                arrow = true
            }
        }
    }
    var field: Str = name
    val recv: AstXmlNode = this.pointee(baseType)
    if (!xmlIsEmpty(recv) && xmlKind(recv) == AstNodeCategory.TypeGeneric && xmlAttr(
            recv,
            AstNodeAttributeKind.Name
        ) == "Res"
    ) {
        if (name == "value") {
            field = "Value"
        } else if (name == "error") {
            field = "Error"
        }
    }
    var op: Str = "."
    if (arrow) {
        op = "->"
    }
    // A value receiver's own member access reads through its pointer (`self->field`); the
    // bare name `this` elsewhere reads as the object (`(*self)`).
    if (xmlKind(base) == AstNodeCategory.ExprName && xmlAttr(base, AstNodeAttributeKind.Name) == "this"
        && this.selfKind == NameKind.Value
    ) {
        if (this.inClosureMethod) {
            return "this->" + field
        }
        return "self->" + field
    }
    return this.expr(base, 12, xmlEmptyNode()) + op + field
}

fun Emitter.nullTo(expected: *AstXmlNode): Str {
    if (!xmlIsEmpty(expected) && xmlKind(expected) == AstNodeCategory.TypeGeneric && xmlAttr(
            expected,
            AstNodeAttributeKind.Name
        ) == "Opt"
    ) {
        val typeArgsStringText: Str = this.typeArgsString("Opt", xmlChildren(expected, AstNodeKind.TypeArg))
        return `Opt<@typeArgsStringText>()`
    }
    return "nullptr"
}

// A receiver's type is resolved through a `typealias` before matching (`StrView` is
// `Span<Char>`, src/rtl/StrView.kt), and so is the declared receiver, so the match works
// from either side. A non-alias type is returned as it came in.
fun Emitter.resolveAlias(typeNode: *AstXmlNode): AstXmlNode {
    var current: AstXmlNode = typeNode
    var guard: Int = 0
    while (!xmlIsEmpty(current) && xmlKind(current) == AstNodeCategory.TypeNamed) {
        guard = guard + 1
        if (guard >= 100) {
            break
        }
        val name: Str = xmlAttr(current, AstNodeAttributeKind.Name)
        val decl: *AstXmlNode = this.types.getPtr(name)
        if (decl == null) {
            break
        }
        if (decl.name != AstNodeKind.TypeAlias) {
            break
        }
        val target: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.TargetType)
        if (xmlIsEmpty(target)) {
            break
        }
        current = target
    }
    return current
}

fun Emitter.expectedCallable(expected: *AstXmlNode): AstXmlNode {
    val resolved: AstXmlNode = this.resolveAlias(expected)
    if (!xmlIsEmpty(resolved) && xmlKind(resolved) == AstNodeCategory.TypeFunction) {
        return resolved
    }
    return xmlEmptyNode()
}

// A non-native function with the given name and parameter count; a method is not one.
fun Emitter.findFunction(name: *Str, argCount: Int): AstXmlNode {
    for (*fn in this.functions) {
        if (fn.isNative || fn.isMethod
            || fn.name != name
        ) {
            continue
        }
        if (xmlCount(fn.decl, AstNodeKind.Param) == argCount) {
            return fn.decl
        }
    }
    return xmlEmptyNode()
}

fun Emitter.isUnitType(typeNode: *AstXmlNode): Bool {
    if (xmlIsEmpty(typeNode)) {
        return true
    }
    return xmlKind(typeNode) == AstNodeCategory.TypeNamed && xmlAttr(typeNode, AstNodeAttributeKind.Name) == "Unit"
}
