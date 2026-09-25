// MergeLocals.kt
//
// One declaration per type and block, instead of one per local:
//
//   Str a;  Str b;                    Str a;
//   ...  a = x.toString();  ...   ->  ...  a = x.toString();  ...
//   L4:;  b = y.toString();           L4:;  a = y.toString();
//
// `a` and `b` are each written once and read once inside one block, so neither is live while the
// other is, and both are declared at the top of the body and live to its end (`linHoistSlots`).
// Sharing one declaration is one fewer object constructed and destroyed - for a `Str`, a `List`
// or a class, which the C++ compiler does not overlap. A scalar buys no stack either way (it is
// already a register), only a shorter declaration.

package optimizations

import common
import linear
import sema

// A local that may share its storage: declared bare at the top of the body with a type of its
// own, written once and read once, both inside one block.
data class MergeLocal(
    var name: Str,
    var typeKey: Str,
    var write: Int,
    var block: Int
)

// Every name a statement names, counted, and where it stood: a name with one read and one write
// keeps both positions.
fun linMergeCount(
    names: List<Str>, counts: *Dictionary<Str, Int>, at: *Dictionary<Str, Int>, index: Int
): Unit {
    linUseDefCount(names, counts)
    var i: Int = 0
    while (i < names.size()) {
        at.insert(names[i], index)
        i = i + 1
    }
}

// The body's reads, writes and escaped names, then the locals that may share storage.
fun linMergeCandidates(stmts: *List<AstXmlNode>, useDefs: LinUseDefs): List<MergeLocal> {
    var writes: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var reads: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var writeAt: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var readAt: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var unsafe: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        linMergeCount(useDefs.defsAt(i), *writes, *writeAt, i)
        linMergeCount(useDefs.usesAt(i), *reads, *readAt, i)
        linUseDefMarkEscapes(stmt, *unsafe)
        linUseDefMarkCaptures(stmt, *unsafe)
        i = i + 1
    }

    var candidates: List<MergeLocal> = List<MergeLocal>()
    i = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl
            && xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))
        ) {
            val name: Str = xmlAttr(stmt, AstNodeAttributeKind.Name)
            val typeNode: *AstXmlNode = xmlChildPtr(stmt, AstNodeKind.Type)
            if (!xmlIsEmpty(typeNode) && !unsafe.has(name)
                && linUseDefAt(*writes, name, 0) == 1 && linUseDefAt(*reads, name, 0) == 1
            ) {
                val write: Int = linUseDefAt(*writeAt, name, -1)
                val read: Int = linUseDefAt(*readAt, name, -1)
                if (write >= 0 && write < read && useDefs.blockAt(write) == useDefs.blockAt(read)) {
                    var local: MergeLocal = MergeLocal(
                        name, semaTypeText(typeNode), write, useDefs.blockAt(write)
                    )
                    candidates.append(local)
                }
            }
        }
        i = i + 1
    }
    return candidates
}

// The names taken over: one storage per ordinal a type has already reached in this block, so two
// candidates share a name exactly when their blocks differ.
fun linMergeNames(
    candidates: List<MergeLocal>, declAt: *Dictionary<Str, Int>, merge: *Dictionary<Str, Str>,
    drop: *Dictionary<Str, Bool>
): Bool {
    var taken: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var reached: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var changed: Bool = false
    var i: Int = 0
    while (i < candidates.size()) {
        val local: *MergeLocal = *candidates[i]
        val reachedKey: Str = fmtStr("|#|", local.typeKey, local.block.toString())
        val ordinal: Int = linUseDefAt(*reached, reachedKey, 0)
        reached.insert(reachedKey, ordinal + 1)
        val slotKey: Str = fmtStr("|#|", local.typeKey, ordinal.toString())
        val owner: *Str = taken.getPtr(slotKey)
        if (owner == null) {
            taken.insert(slotKey, local.name)
        } else {
            // The name taken over must already stand declared where the use moves to, or the C++
            // reads a slot its declaration has not reached.
            val ownerDecl: Int = linUseDefAt(declAt, *owner, -1)
            if (ownerDecl >= 0 && ownerDecl < local.write) {
                merge.insert(local.name, *owner)
                drop.insert(local.name, true)
                changed = true
            }
        }
        i = i + 1
    }
    return changed
}

// One body's locals sharing a declaration per type and block, with the declarations they leave
// behind dropped. A write keeps its place, so evaluation order and side effects do not move.
fun linMergeLocalsOptimization(stmts: *List<AstXmlNode>): Bool {
    val useDefs: LinUseDefs = linUseDefsOf(stmts)
    val candidates: List<MergeLocal> = linMergeCandidates(stmts, useDefs)
    if (candidates.size() < 2) {
        return false
    }

    var declAt: Dictionary<Str, Int> = Dictionary<Str, Int>()
    var i: Int = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl
            && xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))
        ) {
            declAt.insert(xmlAttr(stmt, AstNodeAttributeKind.Name), i)
        }
        i = i + 1
    }

    var merge: Dictionary<Str, Str> = Dictionary<Str, Str>()
    var drop: Dictionary<Str, Bool> = Dictionary<Str, Bool>()
    if (!linMergeNames(candidates, *declAt, *merge, *drop)) {
        return false
    }

    useDefs.rename(*merge)
    var out: List<AstXmlNode> = List<AstXmlNode>()
    i = 0
    while (i < stmts.size()) {
        val stmt: *AstXmlNode = *stmts[i]
        var keeps: Bool = true
        if (xmlKind(stmt) == AstNodeCategory.StmtVarDecl
            && xmlIsEmpty(xmlChildPtr(stmt, AstNodeKind.Init))
            && drop.has(xmlAttr(stmt, AstNodeAttributeKind.Name))
        ) {
            keeps = false
        }
        if (keeps) {
            out.append(stmt)
        }
        i = i + 1
    }
    stmts.clear()
    for (*stmt in out) {
        stmts.append(stmt)
    }
    return true
}

// Self-registration (`Optimize.kt`).
val linMergeLocalsPass: Bool = registerLinOptPass("mergeLocals", linMergeLocalsOptimization)
