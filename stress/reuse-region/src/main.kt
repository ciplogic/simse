package fixtures

// A repeated pure address computation is one computation wherever control flow still guarantees
// the first one ran (impl_specs/expr-reuse.md, "linear regions"): the scan loop's three reads of
// `n.attributes` per iteration - the condition's, the body's, the `if` arm's - keep the one at the
// loop's top, and the arm's element address keeps the body's across the `spanOfStr` call (a pure
// callee moves no buffer). The `when` chain is the boundary the golden pins the other way: an arm
// is entered after an earlier arm's test failed, and that join skips the earlier arm - so each
// arm's `&n->attributes` stays its own computation, never a read of a slot the surviving path
// never wrote. The loop's back edge also starts the tables over: the one surviving address stays
// *inside* the loop in the golden, never hoisted above it.

data class Attr(var name: Str, var value: Str)

data class Node(var kind: Str, var attributes: List<Attr>)

fun scan(n: *Node): Str {
    var found: Str = ""
    var i: Int = 0
    while (i < n.attributes.size()) {
        if (n.attributes[i].name == "want") {
            found = n.attributes[i].value
        }
        i = i + 1
    }
    return found
}

fun armOf(n: *Node): Str {
    when (n.kind) {
        "one" -> { return n.attributes[0].value }
        "two" -> { return n.attributes[1].value }
        else -> { return n.attributes[2].value }
    }
    return ""
}

fun main(): Int {
    var n: Node = Node("x", List<Attr>(0))
    n.attributes.append(Attr("a", "1"))
    n.attributes.append(Attr("want", "2"))
    println(scan(*n))
    var m: Node = Node("two", List<Attr>(0))
    m.attributes.append(Attr("a", "10"))
    m.attributes.append(Attr("b", "20"))
    m.attributes.append(Attr("c", "30"))
    println(armOf(*m))
    return 0
}
