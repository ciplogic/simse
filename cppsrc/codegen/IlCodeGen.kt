// IlCodeGen.kt
//
// The instruction-list backend: the IL's own types and the walks that turn a body's
// instruction list into C++ text (`emitBodyAt`). The IL is the only codegen, and an
// instruction it cannot spell is a hard error, never a fallback (impl_specs/linear-il.md).
// The walks are `Emitter` extension functions: a split file cannot reopen the class.

package codegen

import sema
import common
import linear
import optimizations
import profiling

data class IlFrame(
    // Keyed by *slot index*, not name: two scopes may declare the same name.
    var defOp: Dictionary<Int, Int>,

    var defineCount: Dictionary<Int, Int>,
    var useCount: Dictionary<Int, Int>
)

// A jump crossing a declaration needs a scope: a `goto` may not skip an initialization
// ([stmt.dcl]/3, MSVC C2362). `end` is the label it lands on, `lastJump` the last jump.
data class IlCrossing(
    var end: Int,

    var lastJump: Int
)

// One open block, and the label it ends before.
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

// `--showLinearRepresentation`: the IL of the body the emitter is about to read, on
// stderr (impl_specs/linear-il.md).
fun Emitter.dumpIl(
    fn: *CgFn, decl: *AstXmlNode, body: *List<AstXmlNode>, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): Unit {
    if (!ilShow()) {
        return
    }
    val unit: IlUnit = ilExtractUnit(this.ilFunctionFor(fn, decl, facts, inferred), body, fn.file)
    val text: Str = printIlUnit(unit)
    // `eprintln` adds a newline the dump already ends with: drop that byte.
    if (text.size() > 0) {
        eprintln(text.substr(0, text.size() - 1))
    }
}

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
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(
                xmlAttr(entry.decl, AstNodeAttributeKind.Name),
                ilTypeText(typeNode)
            )
        }
    }
    return info
}

// An operand becomes a leaf `AstXmlNode` - a slot is a name, a constant its literal, a
// place the path it came from, folded out of the instruction that built it.
fun Emitter.ilIntAt(map: *Dictionary<Int, Int>, key: Int, fallback: Int): Int {
    val found: *Int = map.getPtr(key)
    if (found != null) {
        return * found
    }
    return fallback
}

fun Emitter.ilBump(map: *Dictionary<Int, Int>, key: Int): Unit {
    map.insert(key, this.ilIntAt(map, key, 0) + 1)
}

// The operand at `index`, or -1 when out of range. Not `ilOperandAt`: a bare call binds
// by simple name across the whole compilation (`cppsrc/linear/LinearForm.kt`).
fun Emitter.ilOpOperand(operands: *List<Int>, index: Int): Int {
    if (index >= 0 && index < operands.size()) {
        return operands[index]
    }
    return -1
}

fun Emitter.ilNameNode(text: *Str): AstXmlNode {
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprName, List<AstNodeAttribute>(), Array<AstXmlNode>())
    node.attributes.append(AstNodeAttribute(AstNodeAttributeKind.Name, text))
    return node
}

// The destination slot of an instruction, or -1 when it writes memory or jumps instead
// (`ilWritesDestination`).
fun Emitter.ilDst(op: *IlOp): Int {
    if (!ilWritesDestination(op.kind) || op.operands.size() == 0) {
        return -1
    }
    return op.operands[0]
}

// The frame's types, from the body's own tables. Declared types win over the pass's; a
// machine's type survives only here, its declaration never written (impl_specs/for.md).
fun Emitter.ilSeedFrameTypes(il: *IlBody): Unit {
    val proven: List<Str> = il.inferredTypes.keys()
    var i: Int = 0
    while (i < proven.size()) {
        val typeNode: *AstXmlNode = il.inferredTypes.getPtr(proven[i])
        this.localTypes.insert(proven[i], *typeNode)
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
        if (op.kind != IlOpKind.Declare) {
            // An instruction that writes memory or jumps has no destination, but its
            // operands are still reads.
            val dst: Int = this.ilDst(op)
            if (dst >= 0 && dst < il.vars.size()) {
                // The *first* write initialises a slot: a loop target is written again
                // every iteration, and the declaration wants the initializer.
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

// Whether a value is inlined at its use: only an untyped temp with exactly one
// definition and one use (`auto x;` is not a declaration, so it cannot be declared).
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

// Whether a slot is declared with the frame at the top of the body (a typed slot).
fun Emitter.ilDeclaredAtTop(il: *IlBody, slot: Int): Bool {
    if (slot < 0 || slot >= il.vars.size()) {
        return false
    }
    val slotType: AstXmlNode = ilVarType(il, slot)
    return !xmlIsEmpty(slotType)
}

// Whether an instruction builds a closure class instance: an *aggregate*, not an
// expression, so it cannot stand inside another expression and its slot never folds.
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

fun Emitter.ilSlotHoldsClosure(il: *IlBody, frame: *IlFrame, slot: Int): Bool {
    val def: Int = this.ilIntAt(frame.defOp, slot, -1)
    if (def < 0 || def >= il.ops.size()) {
        return false
    }
    return this.ilConstructsClosure(il.ops[def], il)
}

// The C++ of a slot's declared type. A closure class is spelled by its own name: it is
// emitted above the body that constructs it, so no type dictionary knows it.
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

// One declarator of a line that has already written its type: `* b`, or `b`.
fun ilDeclarator(ptr: *Str, name: *Str): Str {
    if (ptr == "") {
        return name
    }
    return ptr + " " + name
}

// One type's declaration lines, wrapped at `kIlDeclWidth` columns. A wrapped line is a
// *continuation* with no type of its own; the caller indents it one level. A trailing
// `*` binds only the name it stands before, so it is written once per name.
val kIlDeclWidth: Int = 100

fun ilDeclLines(typeText: Str, names: *List<Str>): List<Str> {
    var base: Str = typeText
    var ptr: Str = ""
    while (base.size() > 0 && base[base.size() - 1] == '*') {
        ptr = ptr + "*"
        base = base.substr(0, base.size() - 1)
    }
    var out: List<Str> = List<Str>()
    var line: Str = Str()
    var open: Bool = false
    var i: Int = 0
    while (i < names.size()) {
        val piece: Str = ilDeclarator(ptr, names[i])
        if (!open) {
            line.appendStrPtr(base)
            line.appendStrPtr(ptr)
            line.append(' ')
            line.appendStrPtr(names[i])
            open = true
        } else if (line.size() + 2 + piece.size() > kIlDeclWidth) {
            line.append(',')
            out.append(line)
            line = Str()
            line.appendStrPtr(piece)
        } else {
            line.appendStr(", ")
            line.appendStrPtr(piece)
        }
        i = i + 1
    }
    line.append(';')
    out.append(line)
    return out
}

// The frame's storage grouped by type: the lines to print at each declaration's position,
// the first of a type printing all its names. Safe because a declaration *is* storage and
// a group per type keeps the across-type order; a declaration a jump can cross ends the
// run and gets a block (`ilEmitOps`'s `blockEnd`).
fun Emitter.ilDeclGroups(
    il: *IlBody, frame: *IlFrame, blockEnd: *Dictionary<Int, IlCrossing>
): Dictionary<Int, List<Str>> {
    var lines: Dictionary<Int, List<Str>> = Dictionary<Int, List<Str>>()
    var i: Int = 0
    while (i < il.ops.size()) {
        if (il.ops[i].kind != IlOpKind.Declare || blockEnd.has(i)) {
            i = i + 1
            continue
        }
        var types: List<Str> = List<Str>()
        var groups: List<List<Str>> = List<List<Str>>()
        var at: Int = i
        while (at < il.ops.size() && il.ops[at].kind == IlOpKind.Declare && !blockEnd.has(at)) {
            val slot: Int = this.ilOpOperand(il.ops[at].operands, 0)
            if (slot < 0 || slot >= il.vars.size() || this.ilFolded(il, frame, slot)
                || !this.ilDeclaredAtTop(il, slot)
            ) {
                break
            }
            val typeText: Str = this.ilDeclTypeText(il, slot)
            if (typeText == "") {
                break
            }
            var index: Int = -1
            var t: Int = 0
            while (t < types.size()) {
                if (types[t] == typeText) {
                    index = t
                }
                t = t + 1
            }
            if (index < 0) {
                types.append(typeText)
                groups.append(List<Str>())
                index = types.size() - 1
            }
            groups[index].append(il.vars[slot].name)
            at = at + 1
        }
        if (at - i < 2) {
            // One declaration: nothing to group.
            i = i + 1
            continue
        }
        var printed: Int = i
        var t: Int = 0
        while (t < types.size()) {
            lines.insert(printed, ilDeclLines(types[t], *groups[t]))
            printed = printed + 1
            t = t + 1
        }
        while (printed < at) {
            lines.insert(printed, List<Str>())
            printed = printed + 1
        }
        i = at
    }
    return lines
}

// A constant operand: the pool already holds the text the C++ prints, so only the node
// kind is left (`true` a BoolLit, `"abc"` a StrLit, a digit run an IntLit or FloatLit).
fun Emitter.ilLiteralNode(text: *Str): AstXmlNode {
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

fun Emitter.ilMemberNode(base: *AstXmlNode, name: *Str): AstXmlNode {
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
// `simse_addressOf(...)` otherwise (`impl_specs/linear-il.md`). A place is the one value
// an instruction may not copy.
fun Emitter.ilBorrowNode(place: *AstXmlNode, depth: Int): AstXmlNode {
    if (xmlIsEmpty(place) || depth > 24) {
        return xmlEmptyNode()
    }
    var node: AstXmlNode =
        AstXmlNode(AstNodeKind.Expr, AstNodeCategory.ExprDeref, List<AstNodeAttribute>(), Array<AstXmlNode>())
    xmlAddChild(node, this.renameRole(place, AstNodeKind.Operand))
    return node
}

fun Emitter.ilBinaryNode(lhs: *AstXmlNode, op: *Str, rhs: *AstXmlNode): AstXmlNode {
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

fun Emitter.ilLastDot(text: *Str): Int {
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

// A non-local name in a value position: an enum member (`Color.Red`) or a file-level `var`.
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

// The node for the type a *static* call is reached through. `asGenericName` is for a
// construction, whose callee the parser always produces as `Name<T>` (what makes it a
// `CallCtor`); a static call's base is generic only when the source wrote one.
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

// A call instruction as the expression the emitter spells.
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
            this.ilWhy = fmtStr("the receiver of '|'", method.name)
            return xmlEmptyNode()
        }
        callee = this.ilMemberNode(recv, method.name)
        first = first + 1
    } else if (method.staticBase >= 0) {
        val base: AstXmlNode = this.ilTypeBaseNode(il, method.staticBase, false)
        if (xmlIsEmpty(base)) {
            this.ilWhy = fmtStr("the type '|' is reached through", method.name)
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
            this.ilWhy = fmtStr("an argument of '|'", method.name)
            return xmlEmptyNode()
        }
        xmlAddChild(call, this.renameRole(arg, AstNodeKind.Arg))
        i = i + 1
    }
    return call
}

// The expression a value-producing instruction computes, as a node. Both the
// instruction's right-hand side and what a *folded* slot stands for wherever it is read.
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
            // The address of a file-level static: `&name`, never of a copy of it.
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
    // `Cast`, `CallIndirect`, a lambda body, and anything the extractor marked: not
    // expressible yet.
    return xmlEmptyNode()
}

// Where each of the body's labels sits, or -1 when it has no `Label` instruction. One
// array per body: `ilJumpCrossing` asks per *declaration*.
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

// The scope a crossed declaration needs: the earliest label a jump lands on and the
// last jump that crosses ([stmt.dcl]/3, MSVC C2362).
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

// The right-hand side of one instruction, as C++: the computed expression, or - for an
// aggregate construction - the brace form, which has no expression node.
fun Emitter.ilValueText(il: *IlBody, frame: *IlFrame, opIndex: Int, expected: *AstXmlNode): Opt<Str> {
    if (opIndex < 0 || opIndex >= il.ops.size()) {
        return Opt<Str>.none()
    }
    val op: *IlOp = *il.ops[opIndex]
    if (op.kind == IlOpKind.Pack) {
        // `List<T>{v1, v2, ...}`: `List` is `SmallVector<T, 4>`, so a short list stays
        // inline and allocates nothing - which is why a pack builds a `List`, not an `Array`.
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
        return Opt<Str>.some(fmtStr("|{|}", this.type(slotType), cgJoin(values, ", ")))
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
            return Opt<Str>.some(fmtStr("|{|}", il.types[typeAt], cgJoin(captured, ", ")))
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

// One body, instruction by instruction: each instruction is one statement of the
// emitted C++ (a `Declare` pairs with the instruction that writes it).
fun Emitter.ilEmitOps(il: *IlBody, frame: *IlFrame, level: Int): IlText {
    // A declaration a jump can cross needs a block of its own.
    var blockEnd: Dictionary<Int, IlCrossing> = Dictionary<Int, IlCrossing>()
    // Both halves of the crossing: where the block ends (here) and the last jump that
    // crosses (below, when the block opens).
    val labelPos: List<Int> = this.ilLabelPositions(il)
    var scan: Int = 0
    while (scan < il.ops.size()) {
        val op: *IlOp = *il.ops[scan]
        if (op.kind == IlOpKind.Declare || op.kind == IlOpKind.DeclareInit) {
            val slot: Int = this.ilOpOperand(op.operands, 0)
            // A folded declaration prints nothing, so it needs no block.
            if (this.ilFolded(il, frame, slot)) {
                scan = scan + 1
                continue
            }
            // A typed slot is declared where the `Declare` stands; an untyped one at
            // the instruction that first writes it (or here, when the two are adjacent).
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
    // Grouped storage (`ilDeclGroups`): a declaration that needs a block ends a run.
    val declLines: Dictionary<Int, List<Str>> = this.ilDeclGroups(il, frame, *blockEnd)
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

        val crossing: *IlCrossing = blockEnd.getPtr(i)
        if (crossing != null) {
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
                val shared: *List<Str> = declLines.getPtr(i)
                if (shared != null) {
                    // A declaration a group already lists: the first of its type prints
                    // the group's lines (`ilDeclLines`), the others print nothing.
                    var s: Int = 0
                    while (s < shared.size()) {
                        // A continuation line is one level deeper: the same declaration.
                        var lineLvl: Int = lvl
                        if (s > 0) {
                            lineLvl = lvl + 1
                        }
                        this.ilLine(text, lineLvl, shared[s])
                        s = s + 1
                    }
                    i = i + 1
                    continue
                }
                // A typed slot on its own line: the instruction that computes its value
                // assigns it where that instruction stands.
                this.ilLine(
                    text, lvl, fmtStr("| |;", this.ilDeclTypeText(il, slot), il.vars[slot].name)
                )
                i = i + 1
                continue
            }
            // An untyped slot is declared with `auto` and its own definition as the
            // initializer - only where the two are adjacent; otherwise it prints nothing.
            val def: Int = this.ilIntAt(frame.defOp, slot, -1)
            if (def != i + 1) {
                if (def < 0) {
                    return IlText(
                        false, "", fmtStr("the slot '|' has neither a type nor an initializer", il.vars[slot].name)
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
            this.ilLine(text, lvl, fmtStr("auto | = |;", il.vars[slot].name, valueText.value()))
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
                this.ilLine(text, lvl, fmtStr("goto |;", target))
                i = i + 1
                continue
            }
            val cond: AstXmlNode = this.ilOperandNode(il, frame, this.ilOpOperand(op.operands, 0), 0)
            if (xmlIsEmpty(cond)) {
                return IlText(false, "", "a jump with no condition")
            }
            val test: Str = this.expr(cond, 0, xmlEmptyNode())
            var jumpLine: Str = fmtStr("if (!(|)) goto |;", test, target)
            if (kind == IlOpKind.IfTrue) {
                jumpLine = fmtStr("if (|) goto |;", test, target)
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
            // An instruction that defines an untyped slot *is* that slot's declaration
            // (`auto x = <this>;`); a typed one was declared with the frame at the top.
            val declares: Bool = xmlIsEmpty(slotType) && this.ilIntAt(frame.defOp, dst, -1) == i
            val valueText: Opt<Str> = this.ilValueText(il, frame, i, slotType)
            if (!valueText.hasValue()) {
                if (this.ilWhy.isEmpty()) {
                    return IlText(false, "", fmtStr("'|' cannot be expressed yet", ilOpKindText(kind)))
                }
                return IlText(false, "", "cannot express " + this.ilWhy)
            }
            if (declares) {
                this.ilLine(text, lvl, fmtStr("auto | = |;", il.vars[dst].name, valueText.value()))
                i = i + 1
                continue
            }
            if (xmlIsEmpty(slotType)) {
                return IlText(
                    false, "", fmtStr("the slot '|' has no type to assign", il.vars[dst].name)
                )
            }
            this.ilLine(text, lvl, fmtStr("| = |;", il.vars[dst].name, valueText.value()))
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
                text, lvl, fmtStr("| = |;", this.expr(target, 0, xmlEmptyNode()), this.expr(value, 0, targetType))
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
                text, lvl, fmtStr("| = |;", this.expr(target, 0, xmlEmptyNode()), this.expr(value, 0, targetType))
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
                text, lvl, fmtStr("| = |;", this.expr(target, 0, xmlEmptyNode()), this.expr(value, 0, targetType))
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
            this.ilLine(text, lvl, fmtStr("return |;", this.expr(value, 0, this.curReturnType)))
            i = i + 1
            continue
        }
        if (kind == IlOpKind.Lambda) {
            return IlText(false, "", "a lambda body")
        }
        if (kind == IlOpKind.Unsupported) {
            return IlText(false, "", "an unsupported shape")
        }
        return IlText(false, "", fmtStr("the instruction '|'", ilOpKindText(kind)))
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

// The C++ of one function body, from its IL. The frame's types are installed for the
// spelling helpers, and nothing from a previous body is left behind.
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

// A lambda's body as its class's method: `self` is C++'s `this`, and the frame is the
// lambda's own.
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

// The class a lambda is: one field per capture and one `operator()` method - the
// language's `invoke`. An explicit struct is what lets a lambda live in the instruction
// list.
fun Emitter.emitClosureClass(unit: *IlUnit, closure: *IlClosure): IlText {
    var text: Str = fmtStr("struct | {\n", closure.symbol)
    var i: Int = 0
    while (i < closure.captures.size()) {
        var fieldType: AstXmlNode = xmlEmptyNode()
        if (i < closure.captureTypes.size()) {
            fieldType = closure.captureTypes[i]
        }
        if (xmlIsEmpty(fieldType)) {
            return IlText(false, "", fmtStr("the capture '|' has no type", closure.captures[i]))
        }
        text.appendStr(fmtStr("    | |;\n", this.type(fieldType), closure.captures[i]))
        i = i + 1
    }
    var params: List<Str> = List<Str>()
    for (*param in closure.params) {
        val paramType: AstXmlNode = ilTypeNode(unit.lambdas[closure.bodyIndex], param.typeIndex)
        if (xmlIsEmpty(paramType)) {
            return IlText(false, "", fmtStr("the lambda parameter '|' has no type", param.name))
        }
        params.append(fmtStr("| |", this.type(paramType), param.name))
    }
    text.appendStr(fmtStr("    auto operator()(|) {\n", cgJoin(params, ", ")))
    val preamble: Str = profPreamble(closure.symbol + "::operator()")
    if (preamble != "") {
        text.appendStr(fmtStr("||\n", cgIndent(2), preamble))
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

// The classes of every lambda this body constructs, emitted once. A definition must
// precede its construction, and "just before the body" is reproducible.
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
// (impl_specs/yield.md). It builds a machine on the stack and returns it by value, so a
// local iterator is a local struct; `&evens(n)` boxes a copy.
fun Emitter.emitYieldable(
    fn: *
    CgFn,
    decl: *
    AstXmlNode,
    className: *Str,
    classType: *Str,
    prototypeOnly: Bool,
    selfK: NameKind,
    selfTypePtr: *
    AstXmlNode,
    facts: *
    SemFacts
): Unit {
    val returnNode: *AstXmlNode = xmlChildPtr(decl, AstNodeKind.ReturnType)
    val elementType: *AstXmlNode = xmlChildPtr(returnNode, AstNodeKind.Inner)
    if (xmlIsEmpty(elementType)) {
        this.fail(decl, "unsupported: '..' without an element type")
        return
    }

    // A machine is emitted once: the prototype pass writes the class and the factory's
    // declaration, the definition pass only fills the factory in.
    if (!this.emittedYieldables.has(className)) {
        this.emittedYieldables.insert(className, true)
        // The linear body first: `yield` is a *lowering* and trades on the control flow
        // being labels and gotos (impl_specs/yield.md).
        var lowered: List<AstXmlNode> =
            linLowerForEmission(xmlChildren(xmlChildPtr(decl, AstNodeKind.Body), AstNodeKind.Stmt))
        val semantics: SemBody = SemBody(
            decl, fn.templateParams, selfTypePtr, xmlEmptyNode(),
            List<Str>(), List<AstXmlNode>(), Dictionary<Str, AstXmlNode>()
        )
        // This map is the machine's methods' frame too: the lowering rewrites the
        // statements in place, so the names the pass proved are the names they carry.
        var inferred: Dictionary<Str, AstXmlNode> = Dictionary<Str, AstXmlNode>()
        lowered = semInferTypes(lowered, facts, semantics, inferred)
        // The machine's method storage is the machine's fields (`this->`). These names
        // are reserved: a local of any of them would alias the machine's own.
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

    val factory: Str = fmtStr("| |", classType, this.qualify(fn.packageName, xmlAttr(decl, AstNodeAttributeKind.Name)))
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
        // machine member (`linear::yldFieldName`), the same rule the body rewrite uses.
        this.line(1, fmtStr("machine.| = |;", yldFieldName(name), name))
    }
    if (!xmlIsEmpty(xmlChildPtr(decl, AstNodeKind.Receiver))) {
        // An extension function's receiver crosses a yield like any other value, so it is
        // a field the factory fills from its own `self` (`yldReceiverField`).
        this.line(1, fmtStr("machine.| = self;", yldReceiverField()))
    }
    this.line(1, "machine.branch = 0;")
    this.line(1, "return machine;")
    this.line(0, "}")
}

// The factory's parameters: the receiver first when there is one (an extension function's
// receiver is an ordinary `T* self`), then the declaration's own.
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
        val paramType: *AstXmlNode = xmlChildPtr(param, AstNodeKind.Type)
        if (xmlIsEmpty(paramType)) {
            this.fail(
                param,
                fmtStr("unsupported: parameter '|' without a type", xmlAttr(param, AstNodeAttributeKind.Name))
            )
            return params
        }
        params.append(fmtStr("| |", this.type(paramType), xmlAttr(param, AstNodeAttributeKind.Name)))
        if (this.failed) {
            return params
        }
    }
    return params
}

// The machine is a class the type pass never saw, so its fields are registered as a data
// class here for the spelling helpers. A receiver field is a *pointer* (`T* self`), so
// `this._sm_self.size()` reaches through it; the frame, not this table, decides names.
fun Emitter.registerMachineType(className: *Str, machine: *Yielded): Unit {
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

// The machine: its fields, then one method per way of advancing it.
fun Emitter.emitMachine(
    fn: *CgFn, decl: *AstXmlNode, className: *Str, elementType: *AstXmlNode,
    machine: *Yielded, facts: *SemFacts, inferred: *Dictionary<Str, AstXmlNode>
): Unit {
    this.sourceComment(decl)
    this.registerMachineType(className, machine)
    // A machine's methods are the lowering's output (linear/Yield.kt), so they have not been
    // optimized: a machine method is a body like any other (`Optimize.kt`), and its own
    // control flow is what the passes are careful never to disturb.
    for (*method in machine.methods) {
        linOptimizeBody(*method.body)
    }
    // A generic function's machine is a class template: its fields are typed with the
    // function's type parameters, so they are declared where used (impl_specs/yield.md).
    val tmpl: Str = this.templateClause(fn.templateParams)
    if (tmpl != "") {
        this.line(0, tmpl)
    }
    this.line(0, fmtStr("struct | {", className))
    for (*field in machine.fields) {
        if (xmlIsEmpty(field.typeNode)) {
            this.fail(decl, fmtStr("yield: the field '|' has no type", field.name))
            return
        }
        this.line(1, fmtStr("| |{};", this.type(field.typeNode), field.name))
        if (this.failed) {
            return
        }
    }
    for (*method in machine.methods) {
        var params: List<Str> = List<Str>()
        for (*param in method.params) {
        params.append(fmtStr("| |", this.type(param.typeNode), param.name))
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
        // A C++ member function: the machine's values are reached through `this`.
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
        // The method's own parameters tell a pointer receiver from a value one.
        for (*param in method.params) {
        if (!xmlIsEmpty(param.typeNode)) {
            this.nameKinds.insert(param.name, this.kindOf(param.typeNode))
            this.localTypes.insert(param.name, param.typeNode)
        }
    }
        // The body goes through the same two paths as any other, with the machine's frame:
        // its fields are read and written through `self`, as a lambda body reads captures.
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

// A machine method as the extractor's body context: no declaration, the method's
// parameters, and - like a lambda - a class reached through `this`. Its fields are not
// captures (the lowering already spelled every field access as `this.x`), so a parameter
// sharing a field's name resolves to the parameter; the class travels as `selfDecl`.
fun Emitter.ilMachineMethod(
    className: *Str, method: *YldMethod, selfDecl: *AstXmlNode, facts: *SemFacts,
    inferred: *Dictionary<Str, AstXmlNode>
): IlFunction {
    var info: IlFunction = IlFunction(
        xmlEmptyNode(), xmlEmptyNode(),
        fmtStr("|::|", className, method.name), Dictionary<Str, Str>(),
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
        val typeNode: *AstXmlNode = xmlChildPtr(entry.decl, AstNodeKind.Type)
        if (!xmlIsEmpty(typeNode)) {
            info.statics.insert(xmlAttr(entry.decl, AstNodeAttributeKind.Name), ilTypeText(typeNode))
        }
    }
    return info
}

// A failure with a position when the frame has a declaration, position-less otherwise (a
// lambda's body, a machine's method).
fun Emitter.failFromInfo(info: *IlFunction, message: *Str): Unit {
    var node: AstXmlNode = xmlEmptyNode()
    if (!xmlIsEmpty(info.decl)) {
        node = copy(info.decl)
    }
    this.fail(node, message)
}

// A body is emitted from its instruction list - the IL is the *only* codegen
// (impl_specs/linear-il.md). One it cannot spell is an extractor bug, so it fails.
fun Emitter.emitBodyAt(info: *IlFunction, body: *List<AstXmlNode>, file: *Str, level: Int, measure: Bool): Unit {
    val unit: IlUnit = ilExtractUnit(info, body, file)
    val emitted: IlText = this.emitIlBodyText(unit, level)
    if (!emitted.ok) {
        this.failFromInfo(
            info, fmtStr("internal: the body of '|' is not expressible in the IL (|)", info.symbol, emitted.reason)
        )
        return
    }
    // A lambda is a closure class, which the text above *constructs* but does not define:
    // the class goes just above the body that builds it.
    var classes: IlText = IlText(true, "", "")
    if (unit.closures.size() > 0) {
        classes = this.emitClosureClasses(unit)
        if (!classes.ok) {
            this.failFromInfo(
                info, fmtStr("internal: a closure class could not be written (|)", classes.reason)
            )
            return
        }
    }
    this.sections.appendText(classes.text)
    // The profiler's timer comes before the body's storage, so no jump can cross into its
    // scope (impl_specs/profiling.md). `measure` is false for a machine's methods.
    if (measure) {
        val preamble: Str = profPreamble(info.symbol)
        if (preamble != "") {
            this.sections.appendLine(cgIndent(level), preamble)
        }
    }
    this.sections.appendText(emitted.text)
}
