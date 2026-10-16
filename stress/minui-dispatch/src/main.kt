package app

import minui

// Shot 2a, headless: the tree, names, listeners, hit-testing, bubbling and capture, with no
// window and no pixels. Every line printed here is a claim `minui.md` makes:
//
//  - a click finds the *leaf-most* node, then bubbles to the first ancestor whose listener
//    consumes it ("BEGINNER" is a label inside the button; the button is what gets the click),
//  - a disabled node is still hit, but the walk steps over it to its parent,
//  - one listener serves a whole grid; the leaf says which cell,
//  - a release is delivered to the node the press captured, even when the cursor has left it,
//  - a listener that answers false lets the walk continue (that is how the root observes a click
//    nobody else wanted, without consuming it).

// The action codes this test's "screens" answer to. minui itself only ever sees `Int`s.
enum class Act {
    None = 0,
    Cell = 1,
    Panel = 2,
    Button = 3,
    Root = 4,
    Declines = 5
}

fun nameOf(f: *Frame, node: Int): Str {
    if (node < 0) {
        return "none"
    }
    if (f.nodes[node].name.size() > 0) {
        return f.nodes[node].name + "#" + node.toString()
    }
    if (f.nodes[node].kind == NodeKind.Label) {
        return "label#" + node.toString()
    }
    return "cell#" + node.toString()
}

fun hitText(f: *Frame, h: Hit): Str {
    return "action=" + h.action.toString() + " node=" + nameOf(f, h.node)
    +" hit=" + nameOf(f, h.hit) + " payload=" + h.payload.toString()
}

fun eventText(e: UiEvent): Str {
    // The kind and the button as locals first: `e.kind.toInt()` addresses a member of a pointer,
    // which is bad C++ (the compiler's own sources name the same trap in
    // src/linear/ReuseExprs.kt). Scalars reached through a pointer are read into a local.
    val kind: UiEventKind = e.kind
    var buttons: Str = ""
    if (kind == UiEventKind.MouseDown || kind == UiEventKind.MouseUp) {
        // The mask is what tells a plain click from a chord: Left+Right held is 5.
        val button: Button = e.button
        buttons = " button=" + button.toInt().toString() + " mask=" + e.buttons.toString()
    }
    if (kind == UiEventKind.MouseMove) {
        return "move(" + e.x.toString() + "," + e.y.toString() + ")"
    }
    if (kind == UiEventKind.MouseDown) {
        return "down(" + e.x.toString() + "," + e.y.toString() + ")" + buttons
    }
    if (kind == UiEventKind.MouseUp) {
        return "up(" + e.x.toString() + "," + e.y.toString() + ")" + buttons
    }
    return "event(" + kind.toInt().toString() + ")"
}

// Feed one event, printing the chain the walk visited and what consumed it.
fun feed(f: *Frame, e: *UiEvent, trace: *List<Str>, answer: (Hit, *UiEvent) -> Bool): Unit {
    trace.clear()
    val h: Hit = uiEvent(f, e, answer)
    println(eventText(*e) + " -> consumed " + hitText(f, h))
    var i: Int = 0
    while (i < trace.size()) {
        println(trace[i])
        i = i + 1
    }
}

fun main(): Int {
    var f: Frame = newFrame(320, 240)
    var fp: *Frame = *f

    // A panel with three buttons; the first has a label inside it, which is what a click really
    // lands on.
    val panel: Int = addNode(fp, fp.root, NodeKind.Panel, 10, 10, 140, 110)
    setName(fp, panel, "panel")
    val beginner: Int = addNode(fp, panel, NodeKind.Button, 20, 20, 120, 30)
    setName(fp, beginner, "beginner")
    setPayload(fp, beginner, 0)
    val beginnerLabel: Int = addNode(fp, beginner, NodeKind.Label, 25, 25, 110, 20)
    setText(fp, beginnerLabel, "BEGINNER")
    val expert: Int = addNode(fp, panel, NodeKind.Button, 20, 60, 120, 30)
    setName(fp, expert, "expert")
    setPayload(fp, expert, 2)
    // A button whose listener declines, so the panel behind it is what consumes the click.
    val declining: Int = addNode(fp, panel, NodeKind.Button, 20, 100, 120, 30)
    setName(fp, declining, "declining")
    // The board: one named node, 25 anonymous cells.
    val board: Int = addGrid(fp, fp.root, "board", 170, 10, 24, 5, 5)
    // A node nobody can click, because it is not visible.
    val ghost: Int = addNode(fp, fp.root, NodeKind.Button, 250, 150, 50, 50)
    setName(fp, ghost, "ghost")
    setVisible(fp, ghost, false)

    val bound: Bool =
        frameOn(fp, "root", Act.Root.toInt(), 0)
                && frameOn(fp, "panel", Act.Panel.toInt(), 0)
                && frameOn(fp, "beginner", Act.Button.toInt(), 0)
                && frameOn(fp, "expert", Act.Button.toInt(), 2)
                && frameOn(fp, "declining", Act.Declines.toInt(), 0)
                && frameOn(fp, "board", Act.Cell.toInt(), 0)
    println("bound=" + bound.toString())
    // Naming rules: a duplicate and the empty name are refused, so the map cannot drift.
    println(
        "dup=" + setName(fp, beginner, "expert").toString()
                + " empty=" + setName(fp, beginner, "").toString()
                + " missing=" + frameOn(fp, "nobody", Act.None.toInt(), 0).toString()
    )
    println(
        "nodes=" + fp.nodes.size().toString() + " listeners=" + fp.listeners.size().toString()
                + " board=" + board.toString() + " cells=" + (fp.nodes[board].children.size()).toString()
    )

    // The trace records every candidate the walk asks; the answer consumes everything except the
    // root and the declining button, so their parents get their turn.
    var trace: List<Str> = List<Str>(0, "")
    var tracePtr: *List<Str> = *trace
    val answer: (Hit, *UiEvent) -> Bool = (h: Hit, e: *UiEvent) -> {
        tracePtr.append("    candidate " + hitText(fp, h))
        return h.action != Act.Root.toInt() && h.action != Act.Declines.toInt()
    }

    // ---- the pure lookups, before any event ---------------------------------
    println("--- hit tests")
    println("hit(30,30)=" + nameOf(fp, uiHitTest(fp, 30, 30)))
    println("hit(223,82)=" + nameOf(fp, uiHitTest(fp, 223, 82)))
    println("hit(30,110)=" + nameOf(fp, uiHitTest(fp, 30, 110)))
    println("hit(260,170)=" + nameOf(fp, uiHitTest(fp, 260, 170)))
    println("hit(300,200)=" + nameOf(fp, uiHitTest(fp, 300, 200)))
    println("bubble(label)=" + nameOf(fp, uiBubbleTarget(fp, beginnerLabel)))
    println("bubble(cell)=" + nameOf(fp, uiBubbleTarget(fp, fp.nodes[board].children[0])))
    println("bubble(ghost)=" + nameOf(fp, uiBubbleTarget(fp, ghost)))

    // ---- a click on a button whose leaf is a label --------------------------
    println("--- click on the label inside the button")
    feed(fp, *mouseMove(30, 30), tracePtr, answer)
    println("hover=" + nameOf(fp, fp.hover))
    feed(fp, *mouseDown(30, 30, Button.Left, 1), tracePtr, answer)
    println("pressed=" + nameOf(fp, fp.pressed))
    feed(fp, *mouseUp(30, 30, Button.Left, 0), tracePtr, answer)
    println("pressed after up=" + nameOf(fp, fp.pressed))

    // ---- capture: press on the button, release outside ----------------------
    println("--- capture: press on the button, release far away")
    feed(fp, *mouseDown(30, 30, Button.Left, 1), tracePtr, answer)
    feed(fp, *mouseMove(300, 200), tracePtr, answer)
    feed(fp, *mouseUp(300, 200, Button.Left, 0), tracePtr, answer)

    // ---- the grid: one listener, the leaf says which cell -------------------
    println("--- a cell click (one listener for 25 cells)")
    val cellDown: Hit = uiEvent(fp, *mouseDown(223, 82, Button.Left, 1), answer)
    println("down(223,82) -> consumed " + hitText(fp, cellDown))
    // The listener's payload stayed 0 (it is the registration's own data); the *cell* is what
    // the leaf carries, and that is what the app reads to know which square was clicked.
    val leafPayload: Int = fp.nodes[cellDown.hit].payload
    println("leaf payload=" + leafPayload.toString())
    feed(fp, *mouseUp(223, 82, Button.Left, 0), tracePtr, answer)

    // ---- a listener that declines: the parent takes it ----------------------
    println("--- the declining button: the panel behind it consumes")
    feed(fp, *mouseDown(30, 110, Button.Left, 1), tracePtr, answer)
    feed(fp, *mouseUp(30, 110, Button.Left, 0), tracePtr, answer)

    // ---- disabled: still hit, stepped over ---------------------------------
    println("--- disabled button: the walk steps over it")
    setEnabled(fp, expert, false)
    feed(fp, *mouseMove(30, 70), tracePtr, answer)
    println("hover=" + nameOf(fp, fp.hover))
    feed(fp, *mouseDown(30, 70, Button.Left, 1), tracePtr, answer)
    feed(fp, *mouseUp(30, 70, Button.Left, 0), tracePtr, answer)
    setEnabled(fp, expert, true)

    // ---- a click nobody wanted ---------------------------------------------
    println("--- background click: the root sees it and declines")
    feed(fp, *mouseDown(300, 200, Button.Left, 1), tracePtr, answer)
    feed(fp, *mouseUp(300, 200, Button.Left, 0), tracePtr, answer)

    // ---- right click, chord mask, and the kinds that do not hit-test --------
    println("--- buttons and non-mouse kinds")
    feed(fp, *mouseDown(223, 82, Button.Right, 4), tracePtr, answer)
    feed(fp, *mouseUp(223, 82, Button.Right, 0), tracePtr, answer)
    feed(fp, *mouseDown(223, 82, Button.Left, 5), tracePtr, answer)   // both held: a chord
    feed(fp, *mouseUp(223, 82, Button.Left, 4), tracePtr, answer)
    println("wheel -> " + hitText(fp, uiEvent(fp, *UiEvent(UiEventKind.Wheel, 0, 0, Button.None, 0, 0, 1), answer)))
    println("tick -> " + hitText(fp, uiEvent(fp, *UiEvent(UiEventKind.Tick, 0, 0, Button.None, 0, 0, 0), answer)))
    return 0
}
