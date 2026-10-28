package escapesignature

// The write-signature fixpoint (`impl_specs/escape-analysis.md`): a declaration whose
// writes attribute to parameter *positions* carries those positions instead of a blanket
// refusal, and a call site that built the storage at every written position still proves,
// so the caller's *other* parameters borrow. The shapes this case pins:
//
// - `graft` writes only parameter 0 (the node's field), the `xmlAddChild` shape: `wrap`
//   builds its node fresh, so the call imposes nothing and `label` borrows; `launder`
//   passes its own parameter at the written position, so it is refused and keeps its
//   value signature - and the caller's tree is not mutated through the by-value boundary.
// - `Cursor.step` writes only its receiver (position 0): the method borrows its `mark`
//   parameter, and `walk` - which builds its own cursor - proves clean through the
//   signature, so `word` borrows.
// - the propagation: `wrap` calls `graft` (signature {0}) on its fresh node, and `wrap`
//   itself then proves clean - the signature grows the trusted set through calls.

data class Leaf(var text: Str, var kids: Array<Leaf>)

fun graft(node: *Leaf, kid: Leaf): Unit {
    var all: List<Leaf> = node.kids.toList()
    all.append(kid)
    node.kids = all.toArray()
}

fun wrap(label: Str, kid: Leaf): Leaf {
    var holder: Leaf = Leaf(label, Array<Leaf>())
    graft(holder, kid)
    return holder
}

fun launder(via: *Leaf, kid: Leaf): Unit {
    graft(via, kid)
}

data class Cursor(var at: Int, var marks: List<Str>)

fun Cursor.step(mark: Str): Unit {
    this.marks.append(mark)
    this.at = this.at + 1
}

fun walk(word: Str): Int {
    var cursor: Cursor = Cursor(0, List<Str>())
    cursor.step(word)
    return cursor.at
}

fun main(): Int {
    var mine: Leaf = Leaf("root", Array<Leaf>())
    launder(mine, Leaf("x", Array<Leaf>()))
    println(mine.kids.count())
    println(wrap("branch", Leaf("y", Array<Leaf>())).kids.count())
    println(wrap("trunk", Leaf("z", Array<Leaf>())).kids.count())
    println(walk("m"))
    println(walk("nn"))
    return 0
}
