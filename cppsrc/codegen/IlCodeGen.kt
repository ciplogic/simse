// IlCodeGen.kt
//
// The instruction-list backend: the IL's own types and every walk that turns a body's
// instruction list into C++ text. `Codegen.kt`'s `emitFunction`/`emitYieldable`/
// `emitMachine` lower a body and hand it to `emitBodyAt` below; an instruction the IL
// cannot spell is a hard error, never a fallback (impl_specs/linear-il.md).
//
// The walks are extension functions on `Emitter` (`fun Emitter.ilEmitOps(...)`) rather
// than methods inside the data class: a split file cannot reopen the class, and the
// emitted C++ is the same either way - an extension's receiver is the `Emitter* self`
// a class method's is, so no call site and no symbol changes.
//
// This is a Kotlin-ring split: the hand-written ring keeps one `Codegen.cpp`, and the
// two still agree on every fixture (T22's differential).

package codegen

import sema
import common
import linear
import profiling

// One type-table entry's node, or an empty node when the extractor had none (a
// synthesised place: it can only be *folded* into the instruction that reads it, never
// declared).
data class IlFrame(
    // Keyed by *slot index*: two scopes may declare the same name, and the frame keeps
    // them apart (the lowering gives each its own slot), so an analysis keyed by name
    // would merge two different variables.
    var defOp: Dictionary<Int, Int>,

    var defineCount: Dictionary<Int, Int>,
    var useCount: Dictionary<Int, Int>
)

// Where a jump crosses a declaration, C++ wants a scope: a `goto` may not skip an
// initialization ([stmt.dcl]/3, MSVC C2362). `end` is the earliest label a crossing
// jump lands on, `lastJump` the last jump that crosses.
data class IlCrossing(
    var end: Int,

    var lastJump: Int
)

// One open block of the flat form, and the label it ends before.
data class IlScope(
    var start: Int,

    var end: Int
)

// The text one body's instructions spell, or why they could not be spelled.
data class IlText(
    var ok: Bool,

    var text: Str,
    var reason: Str
)

// ---- the linear IL ----------------------------------------------------

// `--showLinearRepresentation`: the IL of the body the emitter is about to read, on
// stderr (impl_specs/linear-il.md). The extraction is pure, so the emitted C++ is the
// same with and without it.
fun Emitter.dumpIl(
    fn: *CgFn, decl: *AstXmlNode, body: List<AstXmlNode>, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): Unit {
    if (!ilShow()) {
        return
    }
    val unit: IlUnit = ilExtractUnit(
        this.ilFunctionFor(fn, decl, facts, inferred), body, fn.file
    )
    val text: Str = printIlUnit(unit)
    // `eprintln` is the one stderr write the prelude has, and it adds the newline the
    // dump already ends with: drop that one byte so the rings' dumps compare byte for
    // byte.
    if (text.size() > 0) {
        eprintln(text.substr(0, text.size() - 1))
    }
}

// What the extractor needs to know about the body's function: the declaration (name,
// parameters, return type), the receiver, the emitted symbol, and the file-level
// statics the body may name.
fun Emitter.ilFunctionFor(
    fn: *CgFn, decl: *AstXmlNode, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): IlFunction {
    var symbol: Str = this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
    if (xmlIsEmpty(fn.receiver) && xmlAttr(decl, AstNodeAttributeKind.Name) == "main") {
        symbol = "main"
    }
    var info: IlFunction = IlFunction(
        decl, fn.receiver, symbol,
        Dictionary<Str, Str>(), xmlEmptyNode(), List<Str>(), List<AstXmlNode>(),
        "", Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        facts, fn.templateParams, inferred
    )
    for (*entry in this.statics) {
        val typeNode: AstXmlNode = xmlChild(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(
                xmlAttr(entry.decl, AstNodeAttributeKind.Name),
                ilTypeText(typeNode)
            )
        }
    }
    return info
}

// ---- emitting from the IL ---------------------------------------------
//
// The C++ of a body comes from its instruction list - the IL is the *only* codegen
// (impl_specs/linear-il.md). The IL is not a second language with a second spelling: an
// operand becomes a leaf `AstXmlNode` - a slot is a name, a constant is its literal, a
// place is the path it came from, folded back out of the instruction that built it -
// and the helpers above write the text.

fun Emitter.ilIntAt(map: *Dictionary<Int, Int>, key: Int, fallback: Int): Int {
    if (map.has(key)) {
        return map.get(key).value()
    }
    return fallback
}

fun Emitter.ilBump(map: *Dictionary<Int, Int>, key: Int): Unit {
    map.insert(key, this.ilIntAt(map, key, 0) + 1)
}

// The operand at `index`, or -1 when out of range - the same helper `linear`'s
// `ilOpComment` has for itself (`cppsrc/linear/LinearForm.kt`), under a different name on
// purpose: a *bare* call binds by simple name across the whole compilation, so two
// same-named helpers in different packages are resolved by which package the scan reaches
// first, not by the caller's package. `ilOperandAt` here shadowed the linear one in its
// own body and the emitted call lost its receiver; see T71's entry.
fun Emitter.ilOpOperand(operands: *List<Int>, index: Int): Int {
    if (index >= 0 && index < operands.size()) {
        return operands[index]
    }
    return -1
}

fun Emitter.ilNameNode(text: Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, text))
    return node
}

// The destination slot of an instruction, or -1 when it writes memory or jumps
// instead (`ilWritesDestination` is the one place that is stated).
fun Emitter.ilDst(op: *IlOp): Int {
    if (!ilWritesDestination(op.kind) || op.operands.size() == 0) {
        return -1
    }
    return op.operands[0]
}

// The frame's types, from the body's own tables - no statement tree is read. First
// what the *type pass* proved for every name in the body, then the slots' declared
// types (which win: they are the spelled ones, and the map may still hold a name the
// shadowing pass renamed). The pass's record is what carries a machine: a slot holding
// one is `..T`, a declaration is never written with that (the emitted C++ uses
// `auto`), so the frame is the only place the type survives - and the emitter needs
// it, because `x.smToYield()` on a machine *is* `x`, an identity decided from the
// receiver's type (see `call`, impl_specs/for.md).
fun Emitter.ilSeedFrameTypes(il: *IlBody): Unit {
    val proven: List<Str> = il.inferredTypes.keys()
    var i: Int = 0
    while (i < proven.size()) {
        val typeNode: AstXmlNode = il.inferredTypes.get(proven[i]).value()
        this.localTypes.insert(proven[i], typeNode)
        this.nameKinds.insert(proven[i], this.kindOf(typeNode))
        i = i + 1
    }
    i = 0
    while (i < il.vars.size()) {
        val slotType: AstXmlNode = ilVarType(il, i)
        if (!xmlIsEmpty(slotType)) {
            this.localTypes.insert(il.vars[i].name, slotType)
            this.nameKinds.insert(il.vars[i].name, this.kindOf(slotType))
        }
        i = i + 1
    }
}

fun Emitter.ilAnalyze(il: *IlBody, frame: *IlFrame): Unit {
    var i: Int = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        // A declaration reads nothing.
        if (op.kind != IlOpKind.Declare) {
            // An instruction that writes memory or jumps has no destination, but its
            // operands are reads like any other - so the two are counted apart, and
            // the first operand is only skipped when it is in fact the destination.
            val dst: Int = this.ilDst(op)
            if (dst >= 0 && dst < il.vars.size()) {
                // The *first* write is what initialises a slot: a loop target is
                // written again every iteration, and the declaration that spells it
                // wants the initializer, not the increment.
                if (!frame.defOp.has(dst)) {
                    frame.defOp.insert(dst, i)
                }
                this.ilBump(*frame.defineCount, dst)
            }
            var j: Int = 0
            while (j < op.operands.size()) {
                var read: Bool = true
                if (j == 0 && dst >= 0) {
                    read = false
                }
                val kind: IlOperandKind = ilOperandKind(op, j)
                if (kind != IlOperandKind.Var && kind != IlOperandKind.Value) {
                    // A label, a pool entry, a type, a callee.
                    read = false
                }
                if (read) {
                    val operand: Int = op.operands[j]
                    if (operand >= 0 && operand < il.vars.size()) {
                        this.ilBump(*frame.useCount, operand)
                    }
                }
                j = j + 1
            }
        }
        i = i + 1
    }
}

// Whether an instruction's result is inlined at its use instead of being assigned to a
// slot. The instruction list is one operation per instruction and every *typed* slot is
// a declared slot of the frame, so a value is read where it was written and no
// instruction inlines another. The one exception is a slot the type rules could not
// name - the extractor's own temporary for a shape that has no type of its own, such
// as a bare `null`, whose C++ spelling depends on the context it is read in. It cannot
// be declared at the top of the body (`auto x;` is not a declaration) and it has
// exactly one definition and one use, so that use is where the expression went.
fun Emitter.ilFolded(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
    if (slot < 0 || slot >= il.vars.size()) {
        return false
    }
    if (il.vars[slot].kind != IlVarKind.Temp) {
        return false
    }
    val slotType: AstXmlNode = ilVarType(il, slot)
    if (!xmlIsEmpty(slotType)) {
        return false // a typed slot is declared
    }
    if (this.ilIntAt(frame.defineCount, slot, 0) != 1) {
        return false
    }
    if (this.ilIntAt(frame.useCount, slot, 0) != 1) {
        return false
    }
    // A closure is an aggregate, not an expression: it keeps its slot.
    return !this.ilSlotHoldsClosure(il, frame, slot)
}

// Whether a slot is declared with the frame at the top of the body (a typed slot)
// rather than in front of the instruction that first writes it.
fun Emitter.ilDeclaredAtTop(il: *IlBody, slot: Int): Bool {
    if (slot < 0 || slot >= il.vars.size()) {
        return false
    }
    val slotType: AstXmlNode = ilVarType(il, slot)
    return !xmlIsEmpty(slotType)
}

// Whether an instruction builds a closure class instance, which is an *aggregate* and
// not an expression: it cannot stand inside another expression, so its slot is never
// folded away.
fun Emitter.ilConstructsClosure(op: *IlOp, il: *IlBody): Bool {
    if (op.kind != IlOpKind.CallCtor) {
        return false
    }
    val typeAt: Int = this.ilOpOperand(op.operands, 1)
    if (typeAt < 0 || typeAt >= il.types.size()) {
        return false
    }
    return this.closureSymbols.has(il.types[typeAt])
}

// The same question, asked about a slot: is the instruction that defines it a closure
// construction?
fun Emitter.ilSlotHoldsClosure(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
    val def: Int = this.ilIntAt(frame.defOp, slot, -1)
    if (def < 0 || def >= il.ops.size()) {
        return false
    }
    return this.ilConstructsClosure(il.ops[def], il)
}

// The C++ of a slot's declared type. A closure class is spelled by its own name: it is
// emitted just above the body that constructs it, so no type dictionary knows it.
fun Emitter.ilDeclTypeText(il: *IlBody, slot: Int): Str {
    val slotType: AstXmlNode = ilVarType(il, slot)
    if (xmlIsEmpty(slotType)) {
        return ""
    }
    val typeIndex: Int = il.vars[slot].typeIndex
    if (typeIndex >= 0 && typeIndex < il.types.size() && this.closureSymbols.has(il.types[typeIndex])) {
        return il.types[typeIndex]
    }
    return this.type(slotType)
}

// A constant operand: the pool holds the text the C++ prints, so all that is left is
// to give it the node kind the emitter expects (`true` is a BoolLit, `"abc"` a
// StrLit, a digit run an IntLit or a FloatLit).
fun Emitter.ilLiteralNode(text: Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprIntLit, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Text, text))
    var dot: Bool = false
    var i: Int = 0
    while (i < text.size()) {
        if (text[i] == '.') {
            dot = true
        }
        i = i + 1
    }
    if (text.isEmpty() || text[0] == '\"') {
        node.kind = AstNodeCategory.ExprStrLit
    } else if (text[0] == '\'') {
        node.kind = AstNodeCategory.ExprCharLit
    } else if (text == "true" || text == "false") {
        node.kind = AstNodeCategory.ExprBoolLit
        node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Value, text))
    } else if (dot) {
        node.kind = AstNodeCategory.ExprFloatLit
    }
    return node
}

fun Emitter.ilSlotNode(il: *IlBody, frame: *IlFrame, slot: Int, depth: Int): AstXmlNode {
    if (slot < 0 || slot >= il.vars.size() || depth > 24) {
        return xmlEmptyNode()
    }
    val name: Str = il.vars[slot].name
    // The receiver slot is the language's `this`, which the emitter spells `(*self)`
    // (`(*this)` inside a closure class).
    if (name == "self") {
        return this.ilNameNode("this")
    }
    if (this.ilFolded(il, frame, slot)) {
        return this.ilOpValueNode(il, frame, this.ilIntAt(frame.defOp, slot, -1), depth + 1)
    }
    return this.ilNameNode(name)
}

fun Emitter.ilOperandNode(il: *IlBody, frame: *IlFrame, operand: Int, depth: Int): AstXmlNode {
    if (operand < 0) {
        val index: Int = -1 - operand
        if (index >= il.pool.size()) {
            return xmlEmptyNode()
        }
        return this.ilLiteralNode(il.pool[index])
    }
    return this.ilSlotNode(il, frame, operand, depth)
}

fun Emitter.ilMemberNode(base: *AstXmlNode, name: Str): AstXmlNode {
    if (xmlIsEmpty(base)) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprMember, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, name))
    xmlAddChild(node, this.renameRole(base, AstNodeKind.Receiver))
    return node
}

// The *address* of a place, as the emitter spells a borrow: `&name` for a plain name,
// `simse_addressOf(...)` otherwise - which is what an `IndexAddr`/`FieldAddr`
// instruction writes (`ldelema`/`ldflda` in the IL's own shape,
// `impl_specs/linear-il.md`). A place is the one value an instruction may not copy: a
// call that mutates its receiver has to reach the original.
fun Emitter.ilBorrowNode(place: *AstXmlNode, depth: Int): AstXmlNode {
    if (xmlIsEmpty(place) || depth > 24) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprDeref, List<AstNodeAttribute>(), Array<AstXmlNode>())
    xmlAddChild(node, this.renameRole(place, AstNodeKind.Operand))
    return node
}

fun Emitter.ilBinaryNode(lhs: *AstXmlNode, op: Str, rhs: *AstXmlNode): AstXmlNode {
    if (xmlIsEmpty(lhs) || xmlIsEmpty(rhs)) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprBinary, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, op))
    xmlAddChild(node, this.renameRole(lhs, AstNodeKind.Lhs))
    xmlAddChild(node, this.renameRole(rhs, AstNodeKind.Rhs))
    return node
}

// The last `.` in `text`, or -1.
fun Emitter.ilLastDot(text: Str): Int {
    var dot: Int = -1
    var i: Int = 0
    while (i < text.size()) {
        if (text[i] == '.') {
            dot = i
        }
        i = i + 1
    }
    return dot
}

// A name in a value position that is not a local: an enum member (`Color.Red`,
// spelled `ns1_Color::Red`) or a file-level `var`.
fun Emitter.ilGetStaticNode(il: *IlBody, op: *IlOp): AstXmlNode {
    val textIndex: Int = this.ilOpOperand(op.operands, 1)
    if (textIndex < 0 || textIndex >= il.pool.size()) {
        return xmlEmptyNode()
    }
    val text: Str = il.pool[textIndex]
    val dot: Int = this.ilLastDot(text)
    if (dot <= 0) {
        return this.ilNameNode(text)
    }
    val base: AstXmlNode = this.ilNameNode(text.substr(0, dot))
    return this.ilMemberNode(base, text.substr(dot + 1, text.size() - dot - 1))
}

// The node for the type a *static* call is reached through: `Color.fromInt` keeps its
// name, `Res<Str>.ok` its type arguments.
//
// `asGenericName` is for a construction, whose callee the parser always produced as
// `Name<T>` (that is what makes it a `CallCtor`); a static call's base is a generic
// name only when the source wrote one.
fun Emitter.ilTypeBaseNode(il: *IlBody, typeIndex: Int, asGenericName: Bool): AstXmlNode {
    val baseType: AstXmlNode = ilTypeNode(il, typeIndex)
    if (xmlIsEmpty(baseType)) {
        return xmlEmptyNode()
    }
    val args: List<AstXmlNode> = xmlChildren(baseType, AstNodeKind.TypeArg)
    if (xmlKind(baseType) == AstNodeCategory.TypeGeneric && (asGenericName || args.size() > 0)) {
        var node: AstXmlNode = AstXmlNode(
            AstNodeKind.Expr,
            AstNodeCategory.ExprGenericName,
            List<AstNodeAttribute>(),
            Array<AstXmlNode>()
        )
        node.attributes.append(
            AstNodeAttribute(
                AstNodeAttributeKind.Name,
                xmlAttr(baseType, AstNodeAttributeKind.Name)
            )
        )
        for (*arg in args) {
            xmlAddChild(node, this.renameRole(arg, AstNodeKind.TypeArg))
        }
        return node
    }
    return this.ilNameNode(xmlAttr(baseType, AstNodeAttributeKind.Name))
}

// A call instruction as the expression the emitter spells: the callee from the method
// table, the arguments from the operands.
fun Emitter.ilCallNode(il: *IlBody, frame: *IlFrame, op: *IlOp): AstXmlNode {
    val hasDst: Bool = this.ilDst(op) >= 0
    var methodAt: Int = 0
    if (hasDst) {
        methodAt = 1
    }
    val methodIndex: Int = this.ilOpOperand(op.operands, methodAt)
    if (methodIndex < 0 || methodIndex >= il.methods.size()) {
        this.ilWhy = "a call with no callee"
        return xmlEmptyNode()
    }
    val method: *IlMethod = *il.methods[methodIndex]

    var call: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprCall, List<AstNodeAttribute>(), Array<AstXmlNode>())
    var first: Int = methodAt + 1
    var callee: AstXmlNode = xmlEmptyNode()
    if (method.kind == IlMethodKind.Method) {
        val recv: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, first), 0)
        if (xmlIsEmpty(recv)) {
            this.ilWhy = "the receiver of '" + method.name + "'"
            return xmlEmptyNode()
        }
        callee = this.ilMemberNode(recv, method.name)
        first = first + 1
    } else if (method.staticBase >= 0) {
        val base: AstXmlNode = this.ilTypeBaseNode(il, method.staticBase, false)
        if (xmlIsEmpty(base)) {
            this.ilWhy = "the type '" + method.name + "' is reached through"
            return xmlEmptyNode()
        }
        callee = this.ilMemberNode(base, method.name)
    } else {
        callee = this.ilNameNode(method.name)
    }
    if (xmlIsEmpty(callee)) {
        return xmlEmptyNode()
    }
    xmlAddChild(call, this.renameRole(callee, AstNodeKind.Callee))
    var i: Int = first
    while (i < op.operands.size()) {
        val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
        if (xmlIsEmpty(arg)) {
            this.ilWhy = "an argument of '" + method.name + "'"
            return xmlEmptyNode()
        }
        xmlAddChild(call, this.renameRole(arg, AstNodeKind.Arg))
        i = i + 1
    }
    return call
}

// The expression a value-producing instruction computes, as a node. This is both the
// right-hand side of the instruction and what a *folded* slot stands for wherever it
// is read.
fun Emitter.ilOpValueNode(il: *IlBody, frame: *IlFrame, opIndex: Int, depth: Int): AstXmlNode {
    if (opIndex < 0 || opIndex >= il.ops.size() || depth > 24) {
        return xmlEmptyNode()
    }
    val op: *IlOp = *il.ops[opIndex]
    val kind: IlOpKind = op.kind
    when (kind) {
        IlOpKind.SetVar -> {
            return this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
        }

        IlOpKind.SetVar_Null -> {
            return AstXmlNode(
                AstNodeKind.Expr,
                AstNodeCategory.ExprNullLit,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
        }

        IlOpKind.BinaryOp -> {
            val opIndex2: Int = this.ilOpOperand(op.operands, 1)
            if (opIndex2 < 0 || opIndex2 >= il.pool.size()) {
                return xmlEmptyNode()
            }
            val lhs: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), depth)
            val rhs: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 3), depth)
            return this.ilBinaryNode(lhs, il.pool[opIndex2], rhs)
        }

        IlOpKind.UnaryOp -> {
            val opIndex2: Int = this.ilOpOperand(op.operands, 1)
            val operand: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), depth)
            if (opIndex2 < 0 || opIndex2 >= il.pool.size() || xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            var node: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprUnary,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Op, il.pool[opIndex2]))
            xmlAddChild(node, this.renameRole(operand, AstNodeKind.Operand))
            return node
        }

        IlOpKind.GetField, IlOpKind.FieldAddr -> {
            val textIndex: Int = this.ilOpOperand(op.operands, 2)
            if (textIndex < 0 || textIndex >= il.pool.size()) {
                return xmlEmptyNode()
            }
            val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
            val member: AstXmlNode = this.ilMemberNode(base, il.pool[textIndex])
            if (kind == IlOpKind.GetField) {
                return member
            }
            return this.ilBorrowNode(member, depth)
        }

        IlOpKind.IndexAddr, IlOpKind.GetIndex -> {
            val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
            val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), depth)
            if (xmlIsEmpty(base) || xmlIsEmpty(index)) {
                return xmlEmptyNode()
            }
            var node: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprIndex,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            xmlAddChild(node, this.renameRole(base, AstNodeKind.Receiver))
            xmlAddChild(node, this.renameRole(index, AstNodeKind.Index))
            if (kind == IlOpKind.GetIndex) {
                return node
            }
            return this.ilBorrowNode(node, depth)
        }

        IlOpKind.Deref, IlOpKind.CopyValue, IlOpKind.Box -> {
            val operand: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 1), depth)
            if (xmlIsEmpty(operand)) {
                return xmlEmptyNode()
            }
            var category: AstNodeCategory = AstNodeCategory.ExprDeref
            if (kind == IlOpKind.CopyValue) {
                category = AstNodeCategory.ExprCopy
            } else if (kind == IlOpKind.Box) {
                category = AstNodeCategory.ExprRef
            }
            var node: AstXmlNode =
                AstXmlNode(AstNodeKind.Expr, category, List<AstNodeAttribute>(), Array<AstXmlNode>())
            xmlAddChild(node, this.renameRole(operand, AstNodeKind.Operand))
            return node
        }

        IlOpKind.GetStatic -> {
            return this.ilGetStaticNode(il, op)
        }

        IlOpKind.GetStaticAddr -> {
            // The address of a file-level static: `&name` (`ilBorrowNode` spells a name
            // that way), never the address of a copy of it.
            val place: AstXmlNode = this.ilGetStaticNode(il, op)
            return this.ilBorrowNode(place, depth)
        }

        IlOpKind.Call, IlOpKind.CallVoid -> {
            return this.ilCallNode(il, frame, op)
        }

        IlOpKind.CallCtor -> {
            val callee: AstXmlNode = this.ilTypeBaseNode(il, this.ilOpOperand(op.operands, 1), true)
            if (xmlIsEmpty(callee)) {
                return xmlEmptyNode()
            }
            var call: AstXmlNode =
                AstXmlNode(
                    AstNodeKind.Expr,
                    AstNodeCategory.ExprCall,
                    List<AstNodeAttribute>(),
                    Array<AstXmlNode>()
                )
            xmlAddChild(call, this.renameRole(callee, AstNodeKind.Callee))
            var i: Int = 2
            while (i < op.operands.size()) {
                val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[i], 0)
                if (xmlIsEmpty(arg)) {
                    return xmlEmptyNode()
                }
                xmlAddChild(call, this.renameRole(arg, AstNodeKind.Arg))
                i = i + 1
            }
            return call
        }
    }
    // `Cast` (no source node), `CallIndirect` (a callable slot), a lambda body, and
    // anything the extractor marked: not expressible yet.
    return xmlEmptyNode()
}

// Where a jump crosses a declaration, C++ wants a scope: a `goto` may not skip an
// initialization ([stmt.dcl]/3, MSVC C2362). The flat form has no scopes of its own,
// so the backend opens the *one* block that keeps the declaration legal - the same one
// the statement path keeps - and closes it at the label the jump lands on.
// Where each of the body's labels sits, or `-1` when the label has no `Label`
// instruction - one array per body, because the crossing below asks per *declaration*
// and rebuilding the map there meant a walk of the whole body (with a hash insert per
// label) for every one of them.
fun Emitter.ilLabelPositions(il: *IlBody): List<Int> {
    var positions: List<Int> = List<Int>()
    var i: Int = 0
    while (i < il.labels.size()) {
        positions.append(-1)
        i = i + 1
    }
    i = 0
    while (i < il.ops.size()) {
        val op: *IlOp = *il.ops[i]
        if (op.kind == IlOpKind.Label && op.operands.size() > 0) {
            val label: Int = op.operands[0]
            if (label >= 0 && label < positions.size()) {
                positions[label] = i
            }
        }
        i = i + 1
    }
    return positions
}

fun Emitter.ilJumpCrossing(il: *IlBody, labelPos: *List<Int>, position: Int): IlCrossing {
    var crossing: IlCrossing = IlCrossing(-1, -1)
    var i: Int = 0
    while (i < position) {
        val op: *IlOp = *il.ops[i]
        var labelAt: Int = -1
        if (op.kind == IlOpKind.Goto) {
            labelAt = 0
        } else if (op.kind == IlOpKind.IfTrue || op.kind == IlOpKind.IfFalse) {
            labelAt = 1
        }
        if (labelAt >= 0) {
            val target: Int = this.ilOpOperand(op.operands, labelAt)
            if (target >= 0 && target < labelPos.size()) {
                val at: Int = labelPos[target]
                if (at >= position) {
                    if (crossing.end < 0 || at < crossing.end) {
                        crossing.end = at
                    }
                    crossing.lastJump = i
                }
            }
        }
        i = i + 1
    }
    return crossing
}

// The right-hand side of one instruction, as C++: the expression the instruction
// computes, or - for a construction that is an aggregate - the brace form, which has
// no expression node.
fun Emitter.ilValueText(il: *IlBody, frame: *IlFrame, opIndex: Int, expected: *AstXmlNode): Opt<Str> {
    if (opIndex < 0 || opIndex >= il.ops.size()) {
        return Opt<Str>.none()
    }
    val op: *IlOp = *il.ops[opIndex]
    if (op.kind == IlOpKind.Pack) {
        // A list built from values: `List<T>{v1, v2, ...}`, the RTL's
        // initializer-list construction. `List` is `SmallVector<T, 4>`, so the short
        // list a packed call usually is stays inline and allocates nothing - which is
        // why the pack builds a `List` and not an `Array`.
        val slot: Int = this.ilOpOperand(op.operands, 0)
        val slotType: AstXmlNode = ilVarType(il, slot)
        if (xmlIsEmpty(slotType)) {
            return Opt<Str>.none()
        }
        var values: List<Str> = List<Str>()
        var j: Int = 1
        while (j < op.operands.size()) {
            val value: AstXmlNode = this.ilOperandNode(il, frame, op.operands[j], 0)
            if (xmlIsEmpty(value)) {
                return Opt<Str>.none()
            }
            values.append(this.expr(value, 0, xmlEmptyNode()))
            j = j + 1
        }
        return Opt<Str>.some(this.type(slotType) + "{" + cgJoin(values, ", ") + "}")
    }
    if (op.kind == IlOpKind.CallCtor) {
        val typeAt: Int = this.ilOpOperand(op.operands, 1)
        if (typeAt >= 0 && typeAt < il.types.size() && this.closureSymbols.has(il.types[typeAt])) {
            var captured: List<Str> = List<Str>()
            var j: Int = 2
            while (j < op.operands.size()) {
                val arg: AstXmlNode = this.ilOperandNode(il, frame, op.operands[j], 0)
                if (xmlIsEmpty(arg)) {
                    return Opt<Str>.none()
                }
                captured.append(this.expr(arg, 0, xmlEmptyNode()))
                j = j + 1
            }
            return Opt<Str>.some(il.types[typeAt] + "{" + cgJoin(captured, ", ") + "}")
        }
    }
    val node: AstXmlNode = this.ilOpValueNode(il, frame, opIndex, 0)
    if (xmlIsEmpty(node)) {
        return Opt<Str>.none()
    }
    return Opt<Str>.some(this.expr(node, 0, expected))
}

fun Emitter.ilLine(out: *Str, level: Int, text: Str): Unit {
    out.appendStr(cgIndent(level))
    out.appendStr(text)
    out.append('\n')
}

// One body, instruction by instruction. Each instruction is one statement of the
// emitted C++ (a `Declare` pairs with the instruction that writes it), which is the
// whole point of the form.
fun Emitter.ilEmitOps(il: *IlBody, frame: *IlFrame, level: Int): IlText {
    // A declaration a jump can cross needs a block around it: the jump may not enter
    // the declaration's scope past it.
    var blockEnd: Dictionary<Int, IlCrossing> = Dictionary<Int, IlCrossing>()
    // Both halves of the crossing are asked for a block: where it must end (here) and
    // the last jump that crosses (below, when the block opens). The walk is the same
    // one, so it is computed once and kept with the block rather than run again.
    val labelPos: List<Int> = this.ilLabelPositions(il)
    var scan: Int = 0
    while (scan < il.ops.size()) {
        val op: *IlOp = *il.ops[scan]
        if (op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit) {
            val slot: Int = this.ilOpOperand(op.operands, 0)
            // A declaration that prints nothing - the folding inlines it at its use -
            // keeps nothing legal, so it asks for no block either.
            if (this.ilFolded(il, frame, slot)) {
                scan = scan + 1
                continue
            }
            // A typed slot is declared where the `Declare` stands (the hoisting put
            // it at the top); an untyped one is declared at the instruction that
            // first writes it - or, when the two are adjacent, right here, where they
            // print as one line (`auto x = <value>;`).
            var at: Int = scan
            if (!this.ilDeclaredAtTop(il, slot)) {
                val def: Int = this.ilIntAt(frame.defOp, slot, -1)
                if (def == scan + 1) {
                    at = scan
                } else {
                    at = def
                }
                if (at < 0) {
                    scan = scan + 1
                    continue
                }
            }
            val crossing: IlCrossing = this.ilJumpCrossing(il, labelPos, at)
            if (crossing.end >= 0) {
                blockEnd.insert(at, crossing)
            }
        }
        scan = scan + 1
    }
    var scopes: List<IlScope> = List<IlScope>()
    var consumedByDeclare: Int = -1
    var lvl: Int = level
    var text: Str = Str()

    var i: Int = 0
    while (i < il.ops.size()) {
        // A block ends where its jump lands: the target stays outside it.
        while (scopes.size() > 0 && scopes[scopes.size() - 1].end == i) {
            scopes.removeAt(scopes.size() - 1)
            lvl = lvl - 1
            this.ilLine(text, lvl, "}")
        }
        if (i == consumedByDeclare) {
            i = i + 1
            continue
        }
        val op: *IlOp = *il.ops[i]
        val kind: IlOpKind = op.kind
        val dst: Int = this.ilDst(op)

        if (blockEnd.has(i)) {
            val crossing: IlCrossing = blockEnd.get(i).value()
            val end: Int = crossing.end
            val lastJump: Int = crossing.lastJump
            var covered: Bool = false
            var s: Int = 0
            while (s < scopes.size()) {
                if (scopes[s].start > lastJump && scopes[s].end >= end) {
                    covered = true
                }
                s = s + 1
            }
            if (!covered) {
                this.ilLine(text, lvl, "{")
                lvl = lvl + 1
                scopes.append(IlScope(i, end))
            }
        }

        if (kind == IlOpKind.Declare || kind == IlOpKind.DeclareInit) {
            val slot: Int = this.ilOpOperand(op.operands, 0)
            if (slot < 0 || slot >= il.vars.size()) {
                return IlText(false, "", "a declare with no slot")
            }
            if (this.ilFolded(il, frame, slot)) {
                i = i + 1
                continue // inlined at its use
            }
            val slotType: AstXmlNode = ilVarType(il, slot)
            if (!xmlIsEmpty(slotType)) {
                // A declared slot with a type: one line, and the instruction that
                // computes its value assigns it where that instruction stands. (The
                // hoisting turned the declaration's initializer into exactly such an
                // assignment.)
                this.ilLine(
                    text, lvl, this.ilDeclTypeText(il, slot) + " " + il.vars[slot].name
                            + ";"
                )
                i = i + 1
                continue
            }
            // A slot the type rules could not name is declared with `auto` and its own
            // definition as the initializer - which only works where the two are
            // adjacent; otherwise the definition is where the declaration goes (below)
            // and this prints nothing.
            val def: Int = this.ilIntAt(frame.defOp, slot, -1)
            if (def != i + 1) {
                if (def < 0) {
                    return IlText(
                        false, "", "the slot '" + il.vars[slot].name
                                + "' has neither a type nor an initializer"
                    )
                }
                i = i + 1
                continue
            }
            val valueText: Opt<Str> = this.ilValueText(il, frame, def, xmlEmptyNode())
            if (!valueText.hasValue()) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", "an initializer with no expression form")
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            this.ilLine(text, lvl, "auto " + il.vars[slot].name + " = " + valueText.value() + ";")
            consumedByDeclare = def
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Label) {
            val label: Int = this.ilOpOperand(op.operands, 0)
            if (label < 0 || label >= il.labels.size()) {
                return IlText(false, "", "a label with no name")
            }
            this.ilLine(text, lvl, il.labels[label] + ":;")
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Goto || kind == IlOpKind.IfTrue || kind == IlOpKind.IfFalse) {
            var labelAt: Int = 1
            if (kind == IlOpKind.Goto) {
                labelAt = 0
            }
            val label: Int = this.ilOpOperand(op.operands, labelAt)
            if (label < 0 || label >= il.labels.size()) {
                return IlText(false, "", "a jump with no label")
            }
            val target: Str = il.labels[label]
            if (kind == IlOpKind.Goto) {
                this.ilLine(text, lvl, "goto " + target + ";")
                i = i + 1
                continue
            }
            val cond: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(cond)) {
                return IlText(false, "", "a jump with no condition")
            }
            val test: Str = this.expr(cond, 0, xmlEmptyNode())
            var jumpLine: Str = "if (!(" + test + ")) goto " + target + ";"
            if (kind == IlOpKind.IfTrue) {
                jumpLine = "if (" + test + ") goto " + target + ";"
            }
            this.ilLine(text, lvl, jumpLine)
            i = i + 1
            continue
        }
        if (dst >= 0 && this.ilFolded(il, frame, dst)) {
            i = i + 1
            continue // inlined at its use
        }
        if (dst >= 0) {
            val slotType: AstXmlNode = ilVarType(il, dst)
            // An instruction that defines a slot the type rules could not name *is*
            // that slot's declaration (`auto x = <this>;`), which is where a slot
            // without a type has to be declared - a typed one was declared with the
            // frame at the top of the body.
            val declares: Bool =
                xmlIsEmpty(slotType) && this.ilIntAt(frame.defOp, dst, -1) == i
            val valueText: Opt<Str> = this.ilValueText(il, frame, i, slotType)
            if (!valueText.hasValue()) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", "'" + ilOpKindText(kind) + "' cannot be expressed yet")
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            if (declares) {
                this.ilLine(text, lvl, "auto " + il.vars[dst].name + " = " + valueText.value() + ";")
                i = i + 1
                continue
            }
            if (xmlIsEmpty(slotType)) {
                return IlText(
                    false, "", "the slot '" + il.vars[dst].name
                            + "' has no type to assign"
                )
            }
            this.ilLine(text, lvl, il.vars[dst].name + " = " + valueText.value() + ";")
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Store) {
            val ptr: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
            if (xmlIsEmpty(ptr) || xmlIsEmpty(value)) {
                return IlText(false, "", "a store with no pointer")
            }
            var target: AstXmlNode = AstXmlNode(
                AstNodeKind.Expr,
                AstNodeCategory.ExprDeref,
                List<AstNodeAttribute>(),
                Array<AstXmlNode>()
            )
            xmlAddChild(target, this.renameRole(ptr, AstNodeKind.Operand))
            val targetType: AstXmlNode = this.inferType(target)
            this.ilLine(
                text, lvl, this.expr(target, 0, xmlEmptyNode()) + " = "
                        + this.expr(value, 0, targetType) + ";"
            )
            i = i + 1
            continue
        }
        if (kind == IlOpKind.SetField || kind == IlOpKind.SetIndex) {
            var target: AstXmlNode = xmlEmptyNode()
            if (kind == IlOpKind.SetField) {
                val textIndex: Int = this.ilOpOperand(op.operands, 1)
                if (textIndex < 0 || textIndex >= il.pool.size()) {
                    return IlText(false, "", "a field write with no name")
                }
                val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
                target = this.ilMemberNode(base, il.pool[textIndex])
            } else {
                val base: AstXmlNode = this.ilSlotNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
                val index: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
                if (!xmlIsEmpty(base) && !xmlIsEmpty(index)) {
                    target = AstXmlNode(
                        AstNodeKind.Expr,
                        AstNodeCategory.ExprIndex,
                        List<AstNodeAttribute>(),
                        Array<AstXmlNode>()
                    )
                    xmlAddChild(target, this.renameRole(base, AstNodeKind.Receiver))
                    xmlAddChild(target, this.renameRole(index, AstNodeKind.Index))
                }
            }
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 2), 0)
            if (xmlIsEmpty(target) || xmlIsEmpty(value)) {
                return IlText(false, "", "a write with no target")
            }
            val targetType: AstXmlNode = this.inferType(target)
            this.ilLine(
                text, lvl, this.expr(target, 0, xmlEmptyNode()) + " = "
                        + this.expr(value, 0, targetType) + ";"
            )
            i = i + 1
            continue
        }
        if (kind == IlOpKind.SetStatic) {
            val textIndex: Int = this.ilOpOperand(op.operands, 0)
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 1), 0)
            if (textIndex < 0 || textIndex >= il.pool.size() || xmlIsEmpty(value)) {
                return IlText(false, "", "a static write with no target")
            }
            val staticText: Str = il.pool[textIndex]
            val dot: Int = this.ilLastDot(staticText)
            var target: AstXmlNode = this.ilNameNode(staticText)
            if (dot > 0) {
                val base: AstXmlNode = this.ilNameNode(staticText.substr(0, dot))
                target = this.ilMemberNode(base, staticText.substr(dot + 1, staticText.size() - dot - 1))
            }
            val targetType: AstXmlNode = this.inferType(target)
            this.ilLine(
                text, lvl, this.expr(target, 0, xmlEmptyNode()) + " = "
                        + this.expr(value, 0, targetType) + ";"
            )
            i = i + 1
            continue
        }
        if (kind == IlOpKind.CallVoid || kind == IlOpKind.CallIndirectVoid) {
            val call: AstXmlNode = this.ilCallNode(il, frame, op)
            if (xmlIsEmpty(call)) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", "a void call")
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            this.ilLine(text, lvl, this.expr(call, 0, xmlEmptyNode()) + ";")
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Return || kind == IlOpKind.ReturnVoid) {
            if (kind == IlOpKind.ReturnVoid) {
                this.ilLine(text, lvl, "return;")
                i = i + 1
                continue
            }
            val value: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(value)) {
                return IlText(false, "", "a return with no value")
            }
            this.ilLine(text, lvl, "return " + this.expr(value, 0, this.curReturnType) + ";")
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Lambda) {
            return IlText(false, "", "a lambda body")
        }
        if (kind == IlOpKind.Unsupported) {
            return IlText(false, "", "an unsupported shape")
        }
        return IlText(false, "", "the instruction '" + ilOpKindText(kind) + "'")
    }
    while (scopes.size() > 0) {
        scopes.removeAt(scopes.size() - 1)
        lvl = lvl - 1
        this.ilLine(text, lvl, "}")
    }
    return IlText(true, text, "")
}

// One body's text, with its frame analysed and the type pass's failure state kept out
// of the caller's: a body the IL cannot spell is a *report*, not an error.
fun Emitter.ilEmitOpsChecked(il: *IlBody, level: Int): IlText {
    var frame: IlFrame = IlFrame(Dictionary<Int, Int>(), Dictionary<Int, Int>(), Dictionary<Int, Int>())
    this.ilAnalyze(il, frame)
    val savedFailed: Bool = this.failed
    val savedError: Str = this.error
    val result: IlText = this.ilEmitOps(il, frame, level)
    var final: IlText = result
    if (result.ok && this.failed) {
        final = IlText(false, "", "the emitter reported: " + this.error)
    }
    this.failed = savedFailed
    this.error = savedError
    return final
}

// The C++ of one function body, from its IL. The frame's types are installed so the
// spelling helpers (`memberAccess`, the call resolution) see the same world the type
// pass gave them; nothing that survived a previous body is left behind.
fun Emitter.emitIlBodyText(unit: *IlUnit, level: Int): IlText {
    val il: *IlBody = *unit.body
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    val savedClosures: Dictionary<Str, Bool> = this.closureSymbols
    this.ilSeedFrameTypes(il)
    var closures: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    for (*closure in unit.closures) {
        closures.insert(closure.symbol, true)
    }
    this.closureSymbols = closures
    val result: IlText = this.ilEmitOpsChecked(il, level)
    this.nameKinds = savedKinds
    this.localTypes = savedTypes
    this.closureSymbols = savedClosures
    return result
}

// C++ of a lambda's body, as the class's method: the frame is the lambda's (its
// parameters and the closure instance), and `self` is C++'s `this`.
fun Emitter.emitClosureMethodText(unit: *IlUnit, closure: *IlClosure, level: Int): IlText {
    if (closure.bodyIndex < 0 || closure.bodyIndex >= unit.lambdas.size()) {
        return IlText(false, "", "a closure with no body")
    }
    val body: *IlBody = *unit.lambdas[closure.bodyIndex]
    val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
    val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
    val savedSelfKind: NameKind = this.selfKind
    val savedSelfType: AstXmlNode = this.selfType
    val savedClosure: Bool = this.inClosureMethod
    this.nameKinds.clear()
    this.localTypes.clear()
    this.ilSeedFrameTypes(body)
    var classType: AstXmlNode =
        AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
    classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, closure.symbol))
    this.selfKind = NameKind.Value
    this.selfType = classType
    this.inClosureMethod = true
    val result: IlText = this.ilEmitOpsChecked(body, level)
    this.nameKinds = savedKinds
    this.localTypes = savedTypes
    this.selfKind = savedSelfKind
    this.selfType = savedSelfType
    this.inClosureMethod = savedClosure
    return result
}

// The class a lambda is: one field per captured variable, and one method - the
// language's `invoke`, which C++ spells `operator()`. A `[=]` capture list becomes an
// explicit struct, which is what lets a lambda live in the instruction list (and what
// `&lambda` counts references to).
fun Emitter.emitClosureClass(unit: *IlUnit, closure: *IlClosure): IlText {
    var text: Str = "struct " + closure.symbol + " {\n"
    var i: Int = 0
    while (i < closure.captures.size()) {
        var fieldType: AstXmlNode = xmlEmptyNode()
        if (i < closure.captureTypes.size()) {
            fieldType = closure.captureTypes[i]
        }
        if (xmlIsEmpty(fieldType)) {
            return IlText(false, "", "the capture '" + closure.captures[i] + "' has no type")
        }
        text.appendStr("    " + this.type(fieldType) + " " + closure.captures[i] + ";\n")
        i = i + 1
    }
    var params: List<Str> = List<Str>()
    for (*param in closure.params) {
        val paramType: AstXmlNode = ilTypeNode(unit.lambdas[closure.bodyIndex], param.typeIndex)
        if (xmlIsEmpty(paramType)) {
            return IlText(false, "", "the lambda parameter '" + param.name + "' has no type")
        }
        params.append(this.type(paramType) + " " + param.name)
    }
    text.appendStr("    auto operator()(" + cgJoin(params, ", ") + ") {\n")
    val preamble: Str = profPreamble(closure.symbol + "::operator()")
    if (preamble != "") {
        text.appendStr(cgIndent(2) + preamble + "\n")
    }
    val bodyText: IlText = this.emitClosureMethodText(unit, closure, 2)
    if (!bodyText.ok) {
        return bodyText
    }
    text.appendStr(bodyText.text)
    text.appendStr("    }\n")
    text.appendStr("};\n\n")
    return IlText(true, text, "")
}

// Writes out the classes of every lambda this body constructs, the first time one is
// needed. A definition has to precede its construction, and the functions are emitted
// in a fixed order, so "just before the body" is both legal and reproducible.
fun Emitter.emitClosureClasses(unit: *IlUnit): IlText {
    var text: Str = Str()
    for (*closure in unit.closures) {
        if (!this.emittedClosures.has(closure.symbol)) {
            val classText: IlText = this.emitClosureClass(unit, closure)
            if (!classText.ok) {
                return classText
            }
            text.appendStr(classText.text)
            this.emittedClosures.insert(closure.symbol, true)
        }
    }
    return IlText(true, text, "")
}

// A yielding function, emitted as the state machine it was lowered to
// (impl_specs/yield.md):
//
//   struct evens_yieldable { Int n; Int i; Int branch; Opt<Int> next() {...} };
//   ns1_evens_yieldable ns1_evens(Int n) { ...machine.n = n; ... }
//
// The function builds a machine on the stack and returns it by value, so a local
// iterator is a local struct; `&evens(n)` boxes a copy for a life that outlives the
// frame (the language's `&T`, as everywhere else).
fun Emitter.emitYieldable(
    fn: *
    CgFn,
    decl: *
    AstXmlNode,
    className: Str,
    classType: Str,
    prototypeOnly: Bool,
    selfK: NameKind,
    selfTypePtr: *
    AstXmlNode,
    facts: *
    SemFacts
): Unit {
    val returnNode: AstXmlNode = xmlChild(decl, AstNodeKind.ReturnType)
    val elementType: AstXmlNode = xmlChild(returnNode, AstNodeKind.Inner)
    if (xmlIsEmpty(elementType)) {
        this.fail(decl, "unsupported: '..' without an element type")
        return
    }

    // A machine is emitted once: the prototype pass writes the class and the factory's
    // declaration, the definition pass only fills the factory in.
    if (!this.emittedYieldables.has(className)) {
        this.emittedYieldables.insert(className, true)
        // The linear body first: `yield` is a *lowering*, and it trades on the control
        // flow being labels and gotos with the value already one operand
        // (impl_specs/yield.md).
        var lowered: List<AstXmlNode> =
            linLowerForEmission(xmlChildren(xmlChild(decl, AstNodeKind.Body), AstNodeKind.Stmt))
        val semantics: SemBody = SemBody(
            decl, fn.templateParams, selfTypePtr, xmlEmptyNode(),
            List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
        )
        // The map is the machine's methods' frame too: the bodies are the same
        // statements (the lowering rewrites them in place), so the names the pass
        // proved are the names they carry.
        var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        lowered = semInferTypes(lowered, facts, semantics, inferred)
        // The machine's methods: the body's storage is the machine's fields (`this->`),
        // and the names a method has of its own are what it yielded (`current`), the
        // dispatcher's branch and the receiver field - a local of any of those names
        // would alias one of them.
        var machineReserved: List<Str> = listOf<Str>("current", "branch", "_sm_self")
        val finalBody: List<AstXmlNode> = linFinishForEmission(lowered, machineReserved)
        val machine: Yielded = linLowerYield(decl, elementType, finalBody, "advance")
        if (!machine.error.isEmpty()) {
            this.fail(decl, machine.error)
            return
        }
        this.emitMachine(fn, decl, className, elementType, machine, facts, inferred)
        if (this.failed) {
            return
        }
    }

    val factory: Str = classType + " " + this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name))
    val tmpl: Str = this.templateClause(fn.templateParams)
    val factoryParams: List<Str> = this.parameterList(fn, decl)
    if (prototypeOnly) {
        if (tmpl != "") {
            this.line(0, tmpl)
        }
        this.line(0, fmtStr("|(|);", factory, cgJoin(factoryParams, ", ")))
        return
    }
    this.sourceComment(decl)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, fmtStr("|(|) {", factory, cgJoin(factoryParams, ", ")))
    this.line(1, classType + " machine{};")
    val declared: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in declared) {
        val name: Str = xmlAttr(param, AstNodeAttributeKind.Name)
        // The field carries a mangled name when the parameter's own would collide with a
        // machine member (`linear::yldFieldName`), and the rewrite inside the body maps it
        // the same way - so `machine.<field> = <param>` is the same rule on both sides.
        this.line(1, fmtStr("machine.| = |;", yldFieldName(name), name))
    }
    if (!xmlIsEmpty(xmlChild(decl, AstNodeKind.Receiver))) {
        // The receiver of an extension function crosses a yield like any other value,
        // so it is a field and the factory fills it from its own `self` parameter
        // (`yldReceiverField`).
        this.line(1, fmtStr("machine.| = self;", yldReceiverField()))
    }
    this.line(1, "machine.branch = 0;")
    this.line(1, "return machine;")
    this.line(0, "}")
}

// The parameters of the factory: the receiver first when the function has one (an
// extension function's receiver is an ordinary parameter in the emitted C++, `T* self`
// for a value receiver), then the declaration's own.
fun Emitter.parameterList(fn: *CgFn, decl: *AstXmlNode): List<Str> {
    var params: List<Str> = List<Str>()
    if (!xmlIsEmpty(fn.receiver)) {
        params.append(this.receiverParam(fn.receiver))
        if (this.failed) {
            return params
        }
    }
    val declared: List<AstXmlNode> = xmlChildren(decl, AstNodeKind.Param)
    for (*param in declared) {
        val paramType: AstXmlNode = xmlChild(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            this.fail(
                param,
                "unsupported: parameter '" + xmlAttr(param, AstNodeAttributeKind.Name) + "' without a type"
            )
            return params
        }
        params.append(this.type(paramType) + " " + xmlAttr(param, AstNodeAttributeKind.Name))
        if (this.failed) {
            return params
        }
    }
    return params
}

// The machine is a class the type pass never saw - it is the lowering's own output -
// so its fields are registered as a data class here. That is what tells the spelling
// helpers what `this.<field>` is: a receiver field is a *pointer* (`T* self`), so
// `this._sm_self.size()` reaches through it rather than taking its address, and a
// method's parameter that shares a field's name still resolves to the parameter (the
// frame, not this table, decides names).
fun Emitter.registerMachineType(className: Str, machine: *Yielded): Unit {
    var declNode: AstXmlNode =
        AstXmlNode(AstNodeKind.DataClass, AstNodeCategory.DataClass, List<AstNodeAttribute>(), Array<AstXmlNode>())
    declNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
    for (*field in machine.fields) {
        var fieldNode: AstXmlNode =
            AstXmlNode(AstNodeKind.Field, AstNodeCategory.None, List<AstNodeAttribute>(), Array<AstXmlNode>())
        fieldNode.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, field.name))
        xmlAddChild(fieldNode, this.renameRole(field.typeNode, AstNodeKind.Type))
        xmlAddChild(declNode, this.renameRole(fieldNode, AstNodeKind.Field))
    }
    this.types.insert(className, declNode)
    this.machineDecl = declNode
}

// The machine itself: the fields, then one method per way of advancing it.
fun Emitter.emitMachine(
    fn: *CgFn, decl: *AstXmlNode, className: Str, elementType: AstXmlNode,
    machine: *Yielded, facts: *SemFacts, inferred: *Dictionary<Str, AstXmlNode>
): Unit {
    this.sourceComment(decl)
    this.registerMachineType(className, machine)
    // A generic function's machine is a class template: its fields are typed with the
    // function's type parameters, so the emitted C++ has to declare them where it uses
    // them (impl_specs/yield.md).
    val tmpl: Str = this.templateClause(fn.templateParams)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, fmtStr("struct | {", className))
    for (*field in machine.fields) {
        if (xmlIsEmpty(field.typeNode)) {
            this.fail(decl, "yield: the field '" + field.name + "' has no type")
            return
        }
        this.line(1, this.type(field.typeNode) + " " + field.name + "{};")
        if (this.failed) {
            return
        }
    }
    for (*method in machine.methods) {
        var params: List<Str> = List<Str>()
        for (*param in method.params) {
            params.append(this.type(param.typeNode) + " " + param.name)
            if (this.failed) {
                return
            }
        }
        // `advance()` answers whether there was a value; `value()` hands out the element.
        var result: Str = this.type(elementType)
        if (method.name == "advance") {
            result = "Bool"
        }
        this.line(1, fmtStr("| |(|) {", result, method.name, cgJoin(params, ", ")))
        // The method is a C++ member function, so the machine's own values are
        // reached through `this` - the same spelling the closure classes use.
        val savedClosure: Bool = this.inClosureMethod
        val savedSelfKind: NameKind = this.selfKind
        val savedSelfType: AstXmlNode = this.selfType
        val savedReturn: AstXmlNode = this.curReturnType
        val savedKinds: Dictionary<Str, NameKind> = this.nameKinds
        val savedTypes: Dictionary<Str, AstXmlNode> = this.localTypes
        this.inClosureMethod = true
        this.selfKind = NameKind.Value
        var classType: AstXmlNode =
            AstXmlNode(AstNodeKind.Type, AstNodeCategory.TypeNamed, List<AstNodeAttribute>(), Array<AstXmlNode>())
        classType.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, className))
        this.selfType = classType
        this.curReturnType = elementType
        // The method's own parameters are what tells a pointer receiver from a value
        // one (`*value = x` writes through it).
        for (*param in method.params) {
            if (!xmlIsEmpty(param.typeNode)) {
                this.nameKinds.insert(param.name, this.kindOf(param.typeNode))
                this.localTypes.insert(param.name, param.typeNode)
            }
        }
        // The body goes through the same two paths as any other (the IL is what a
        // machine's methods must be expressible in, since the machine *is* the
        // lowering's output): the frame is the machine's, so its fields are read and
        // written through `self`, exactly as a lambda body reads its captures.
        this.emitBodyAt(
            this.ilMachineMethod(className, method, this.machineDecl, facts, inferred), method.body,
            fn.file, 2, false
        )
        this.inClosureMethod = savedClosure
        this.selfKind = savedSelfKind
        this.selfType = savedSelfType
        this.curReturnType = savedReturn
        this.nameKinds = savedKinds
        this.localTypes = savedTypes
        if (this.failed) {
            return
        }
        this.line(1, "}")
    }
    this.line(0, "};")
    this.line(0, "")
}

// A state machine's method, as the extractor's body context: no declaration, the
// method's parameters, and - like a lambda - a class whose fields the body reaches
// through `this`. The *fields* are deliberately not captures: the lowering already
// spelled every field read and write as an explicit `this.x` member, so a bare name in
// the body is the method's own - and a parameter that happens to share a field's name
// (`advance(value: *T)` against a field `value`) must resolve to the parameter.
//
// The machine's class travels as the body's `selfDecl`: the class is the lowering's own
// output, so the type rules never saw it - and a field read (`this._sm_self`) is how
// the body reaches everything that crossed a `yield`.
fun Emitter.ilMachineMethod(
    className: Str, method: *YldMethod, selfDecl: *AstXmlNode, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): IlFunction {
    var info: IlFunction = IlFunction(
        xmlEmptyNode(), xmlEmptyNode(),
        className + "::" + method.name, Dictionary<Str, Str>(),
        selfDecl, List<Str>(), List<AstXmlNode>(), className,
        Dictionary<Str, Bool>(), Dictionary<Str, AstXmlNode>(),
        facts, List<Str>(), inferred
    )
    var i: Int = 0
    for (*param in method.params) {
        info.paramNames.append(param.name)
        info.paramTypes.append(param.typeNode)
    }
    i = 0
    for (*entry in this.statics) {
        val typeNode: AstXmlNode = xmlChild(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(xmlAttr(entry.decl, AstNodeAttributeKind.Name), ilTypeText(typeNode))
        }
    }
    return info
}

// A failure with a position when the frame has a declaration, and position-less for a
// synthesized body (a lambda's, a machine's method).
fun Emitter.failFromInfo(info: IlFunction, message: Str): Unit {
    var node: AstXmlNode = xmlEmptyNode()
    if (!xmlIsEmpty(info.decl)) {
        node = copy(info.decl)
    }
    this.fail(node, message)
}

// A body is emitted from its instruction list - the IL is the *only* codegen
// (impl_specs/linear-il.md). A body the IL cannot spell is a bug in the extractor, not
// something to fall back from: it fails with the reason.
fun Emitter.emitBodyAt(info: IlFunction, body: List<AstXmlNode>, file: Str, level: Int, measure: Bool): Unit {
    val unit: IlUnit = ilExtractUnit(info, body, file)
    val emitted: IlText = this.emitIlBodyText(unit, level)
    if (!emitted.ok) {
        this.failFromInfo(
            info, "internal: the body of '" + info.symbol
                    + "' is not expressible in the IL (" + emitted.reason + ")"
        )
        return
    }
    // A lambda is a closure class, which the text above *constructs* but does not
    // define: the class goes just above the body that builds it.
    var classes: IlText = IlText(true, "", "")
    if (unit.closures.size() > 0) {
        classes = this.emitClosureClasses(unit)
        if (!classes.ok) {
            this.failFromInfo(
                info, "internal: a closure class could not be written ("
                        + classes.reason + ")"
            )
            return
        }
    }
    this.out.appendStr(classes.text)
    // The profiler's timer comes before the body's own storage: the frame's declarations
    // follow it, and nothing precedes it, so no jump can cross into its scope
    // (impl_specs/profiling.md). `measure` is false for a state machine's methods, whose
    // per-element cost is the timer's own (profiling/Profiling.kt states the policy).
    if (measure) {
        val preamble: Str = profPreamble(info.symbol)
        if (preamble != "") {
            this.ilLine(this.out, level, preamble)
        }
    }
    this.out.appendStr(emitted.text)
}
