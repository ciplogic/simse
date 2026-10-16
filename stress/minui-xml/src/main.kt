package app

import minui

// Shot 5, headless: the XML reader, the screen loader and the layer stack - the parts of a screen
// stack that need no window at all. The app's screenshots are what show the result painted; this
// pins every claim `minui.md` makes about the path from text to a clickable arena:
//
//  - the reader is strict: it counts lines, decodes the five entities, and refuses a document it
//    cannot parse *with the line it is on* - a message, not a crash and not silence,
//  - the loader maps the vocabulary onto the tree: kinds, geometry, names, text, palette, align,
//    the grid expansion - and an attribute a name does not have is an error,
//  - a loaded screen *is* a tree: one listener on a named grid serves its 16 cells, the leaf says
//    which cell was clicked, and a click on the background is unclaimed,
//  - the stack: push/pop/top/names, the paint base (everything below the last opaque layer is
//    skipped), the bottom layer cannot be popped away, and a layer shares the frame it was pushed
//    with - a write through the stack's top is the write the app's own handle sees.

// The fixture: every element and every attribute the loader knows, a comment and an entity.
val fixture: Str = `
<!-- everything the loader knows, in one screen -->
<screen name="fixture" width="200" height="160">
<panel rect="0,0,200,60" palette="plain"/>
<label rect="0,4,200,20" palette="emphasized" align="center">HELLO &amp; GOODBYE</label>
<button name="go" rect="10,70,80,24">GO</button>
<button name="off" rect="100,70,80,24" text="OFF" enabled="false"/>
<sprite name="icon" rect="10,100,16,16" index="3"/>
<custom name="view" rect="10,120,40,40" payload="7"/>
<grid name="cells" rect="60,100,64,64" cell="16"/>
</screen>
`

fun nameOf(f: *Frame, node: Int): Str {
    if (node < 0) {
        return "none"
    }
    val name: Str = f.nodes[node].name
    if (name.size() == 0) {
        return "(anonymous)"
    }
    return name
}

// A click, pushed through the tree the way the app's loop does it.
fun click(f: *Frame, x: Int, y: Int, answer: (Hit, *UiEvent) -> Bool): Hit {
    uiEvent(f, *mouseMove(x, y), answer)
    uiEvent(f, *mouseDown(x, y, Button.Left, 1), answer)
    return uiEvent(f, *mouseUp(x, y, Button.Left, 0), answer)
}

fun hitText(f: *Frame, hit: Hit): Str {
    return "action=" + hit.action.toString() + " node=" + nameOf(f, hit.node) + " leaf=" + nameOf(
        f,
        hit.hit
    ) + " payload=" + hit.payload.toString()
}

// What the last click resolved to. One struct behind one raw pointer, not three `*Int`s: two
// address-of-a-plain-Int locals before a lambda literal break the parser (`expected ')'` at the
// lambda's parameter list), which is minui.md §11's own note - one pointer to one struct is both
// the workaround and the clearer shape.
data class LastClick(var action: Int, var payload: Int, var leaf: Int)

fun main(): Int {
    // ---- a document that loads ---------------------------------------------
    println("--- a document that loads")
    val parsed: Res<Frame> = loadScreen(fixture)
    if (!parsed.isOk()) {
        println("fixture failed: " + parsed.error())
        return 1
    }
    var f: Frame = parsed.value()
    val fp: *Frame = *f
    println("nodes=" + fp.nodes.size().toString())
    println(
        "screen: name=" + nameOf(fp, fp.root)
                + " rect=" + fp.nodes[0].rect.width.toString() + "x"
                + fp.nodes[0].rect.height.toString()
                + " name \"root\" is gone=" + (nodeNamed(fp, "root") < 0).toString()
    )

    val go: Int = nodeNamed(fp, "go")
    val off: Int = nodeNamed(fp, "off")
    val sprite: Int = nodeNamed(fp, "icon")
    val view: Int = nodeNamed(fp, "view")
    val cells: Int = nodeNamed(fp, "cells")
    println(
        "named: go=" + go.toString() + " off=" + off.toString() + " icon=" + sprite.toString()
                + " view=" + view.toString() + " cells=" + cells.toString()
    )
    // The kinds as locals first: `f.nodes[go].kind.toInt()` addresses the field instead of
    // reading it (minui.md §11's own trap), and cl.exe refuses the emitted cast.
    val goKind: NodeKind = f.nodes[go].kind
    val offKind: NodeKind = f.nodes[off].kind
    val cellsKind: NodeKind = f.nodes[cells].kind
    println(
        "kinds: go=" + goKind.toInt().toString()
                + " off=" + offKind.toInt().toString()
                + " cells=" + cellsKind.toInt().toString()
    )
    println(
        "button go: text=" + fp.nodes[go].text
                + " rect=" + fp.nodes[go].rect.x.toString() + "," + fp.nodes[go].rect.y.toString()
    )
    println(
        "button off: enabled=" + fp.nodes[off].enabled.toString()
                + " text=" + fp.nodes[off].text
    )
    val label: Int = f.nodes[0].children[1]
    println(
        "label: name=" + nameOf(fp, label) + " text=" + fp.nodes[label].text
                + " palette=" + fp.nodes[label].palette.toString()
                + " align=" + fp.nodes[label].align.toString()
    )
    println(
        "sprite: index=" + fp.nodes[sprite].sprite.toString()
                + " custom: payload=" + fp.nodes[view].payload.toString()
    )
    println(
        "grid: rect=" + fp.nodes[cells].rect.width.toString() + "x"
                + fp.nodes[cells].rect.height.toString()
                + " children=" + fp.nodes[cells].children.size().toString()
    )
    val second: Int = fp.nodes[cells].children[1]
    println(
        "cell[1]: rect=" + fp.nodes[second].rect.x.toString() + ","
                + fp.nodes[second].rect.y.toString()
                + " payload=" + fp.nodes[second].payload.toString()
    )

    // ---- the loaded screen is a tree ---------------------------------------
    println("--- clicks on the loaded screen")
    var last: LastClick = LastClick(0 - 1, 0 - 1, 0 - 1)
    val lastPtr: *LastClick = *last
    val answer: (Hit, *UiEvent) -> Bool = (h: Hit, e: *UiEvent) -> {
        if (h.action < 0) {
            return false
        }
        lastPtr.action = h.action
        lastPtr.payload = h.payload
        lastPtr.leaf = h.hit
        return true
    }
    var bound: Bool = frameOn(fp, "cells", 1, 0)
    bound = frameOn(fp, "go", 2, 0) && bound
    println("bound=" + bound.toString())
    click(fp, 50, 82, answer)
    println(
        "click(50,82) on GO: action=" + last.action.toString()
                + " payload=" + last.payload.toString()
                + " leaf=" + nameOf(fp, last.leaf)
    )
    click(fp, 100, 124, answer)
    // The listener's own payload stays 0 (it is the registration's data); the *cell* is what the
    // leaf carries, and that is what the app reads to know which square was clicked.
    println(
        "click(100,124) cell row1 col2: action=" + last.action.toString()
                + " leaf payload=" + fp.nodes[last.leaf].payload.toString()
    )
    println("click(150,5) on the panel: " + hitText(fp, click(fp, 150, 5, answer)))
    println("click(250,150) outside: " + hitText(fp, click(fp, 250, 150, answer)))

    // ---- what neither the reader nor the loader will take -------------------
    println("--- documents that do not load")
    println(loadScreen(`<screen width="10" height="10">`).error())
    println(loadScreen(`<window width="10" height="10"/>`).error())
    println(loadScreen("").error())
    println(loadScreen(`<!-- oops`).error())
    println(loadScreen(`<screen width="10" height="10" name=menu/>`).error())
    println(loadScreen("<screen width=\"10\" height=\"10\" name=\"a&nope;b\"/>").error())
    println(loadScreen("<screen width=\"10\" height=\"10\">\n  <panel rect=\"0,0,1,1\" rect=\"2,2,1,1\"/>\n</screen>").error())
    println(loadScreen("<screen width=\"10\" height=\"10\">\n  <panel line=\"7\" rect=\"0,0,1,1\"/>\n</screen>").error())
    println(loadScreen("<screen width=\"10\" height=\"10\">\n  <panel rect=\"0,0,1,1\">\n</screen>").error())
    println(loadScreen(`<screen width="10" height="10"/>` + "x").error())
    println(loadScreen(`<screen width="10" height="10"><blink rect="0,0,1,1"/></screen>`).error())
    println(loadScreen(`<screen width="10" height="10"><panel rect="0,0,1,1" colour="red"/></screen>`).error())
    println(loadScreen(`<screen width="10" height="10"><panel palette="plain"/></screen>`).error())
    println(loadScreen(`<screen width="10" height="10"><panel rect="0,0,1,1" palette="loud"/></screen>`).error())
    println(loadScreen(`<screen width="10" height="10"><label rect="0,0,9,9"/></screen>`).error())
    println(loadScreen(`<screen width="10" height="10"><grid name="g" rect="0,0,30,30" cell="16"/></screen>`).error())
    println(loadScreen(`<screen width="10" height="10"><grid rect="8,8,32,32" cell="16"/></screen>`).error())
    println(
        loadScreen(
            `<screen width="10" height="10"><panel name="x" rect="0,0,1,1"/><panel name="x" rect="2,2,1,1"/></screen>`
        ).error()
    )
    println(loadScreen(`<screen height="10"/>`).error())
    println(loadScreen(`<screen width="10" height="10"><custom rect="0,0,1,1"/></screen>`).error())

    // ---- the stack ----------------------------------------------------------
    println("--- the stack")
    var lower: Frame = newFrame(smSize(100, 80))
    var upper: Frame = newFrame(smSize(200, 120))
    val tick: Int = addNode(*upper, upper.root, NodeKind.Label, 0, 0, 40, 12)
    setName(*upper, tick, "tick")
    setText(*upper, tick, "-")
    val lowerBox: &Frame = &lower
    val upperBox: &Frame = &upper
    var stack: ScreenStack = newStack(lowerBox, "menu", true)
    println("size=" + stackSize(*stack).toString() + " top=" + stackTopName(*stack))
    stackPush(*stack, upperBox, "game", true)
    println(
        "push a game screen: size=" + stackSize(*stack).toString()
                + " top=" + stackTopName(*stack)
                + " paintBase=" + stackPaintBase(*stack).toString()
    )
    val top: *Frame = stackTop(*stack)
    val topSize: Rect = top.nodes[top.root].rect
    println(
        "the top screen's window: " + topSize.width.toString() + "x"
                + topSize.height.toString()
    )
    setTextNamed(top, "tick", "12")
    val mine: *Frame = *upperBox
    println(
        "a write through the stack's top, read through the app's handle: "
                + mine.nodes[nodeNamed(mine, "tick")].text
    )
    println(
        "pop -> " + stackPop(*stack).toString()
                + " size=" + stackSize(*stack).toString()
                + " top=" + stackTopName(*stack)
    )
    println(
        "pop the last -> " + stackPop(*stack).toString()
                + " size=" + stackSize(*stack).toString()
    )
    stackPush(*stack, upperBox, "overlay", false)
    println("a translucent overlay on the menu: paintBase=" + stackPaintBase(*stack).toString())
    stackPush(*stack, upperBox, "dialog", true)
    println("an opaque dialog on top: paintBase=" + stackPaintBase(*stack).toString())
    stackPop(*stack)
    println("dialog closed: paintBase=" + stackPaintBase(*stack).toString())
    return 0
}
