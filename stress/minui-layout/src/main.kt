package app

import minui

// The box model and the controls, headless: what `layoutFrame` places, what the loader takes for
// a screen that is (or holds) a layout box, and the input behaviour the tree owns for the built-in
// controls - the parts of a usable mouse UI that need no window to be pinned:
//
//  - a column stacks its children top to bottom, `spacing` apart inside `padding`; a child whose
//    main-axis size is 0 shares the remainder, a child whose cross-axis size is 0 stretches,
//  - a row is the same along the other axis, and a box inside a box is laid out against the rect
//    its parent just gave it,
//  - XML says which model an element is in: `rect` outside a box, `width`/`height` (0 = the box
//    decides) inside one; a grid keeps its rect everywhere and a box only positions it,
//  - a checkbox toggles on the press, before its listener runs; a disabled one does not toggle,
//  - a slider follows the cursor while its press is captured, clamped to 0..1000, listener or not,
//    and a listener sees the value the press already wrote,
//  - an edit takes the key focus on its press, gives it up on a press anywhere else, appends
//    printable keys up to its `payload` limit, drops a character on Backspace, walks its listener
//    on Return, and never consumes Escape.

// The action codes this test's controls answer to. minui itself only ever sees `Int`s.
enum class Act {
    None = 0,
    Opt = 3,
    Off = 4,
    Gain = 5,
    Commit = 6
}

// What the last delivered event resolved to, and the value the node carried when the walk ran
// (a checkbox's listener must see the toggle; a slider's, the drag). One struct behind one raw
// pointer, not several `*Int`s before a lambda literal (minui.md §11's parser trap).
data class Taken(var action: Int, var node: Int, var hit: Int, var value: Int)

fun nameOf(f: *Frame, node: Int): Str {
    if (node < 0) {
        return "none"
    }
    val name: Str = f.nodes[node].name
    if (name.size() > 0) {
        return name + "#" + node.toString()
    }
    return "anon#" + node.toString()
}

fun rectText(f: *Frame, node: Int): Str {
    val r: Rect = f.nodes[node].rect
    return nameOf(f, node) + "=" + r.x.toString() + "," + r.y.toString()
    +"," + r.width.toString() + "," + r.height.toString()
}

// A click, pushed through the tree the way the app's loop does it.
fun click(f: *Frame, x: Int, y: Int, answer: (Hit, *UiEvent) -> Bool): Hit {
    uiEvent(f, *mouseMove(x, y), answer)
    uiEvent(f, *mouseDown(x, y, Button.Left, 1), answer)
    return uiEvent(f, *mouseUp(x, y, Button.Left, 0), answer)
}

fun valueOf(f: *Frame, name: Str): Int {
    val node: Int = nodeNamed(f, name)
    return f.nodes[node].value
}

fun textOf(f: *Frame, name: Str): Str {
    val node: Int = nodeNamed(f, name)
    return f.nodes[node].text
}

// ---- the box model, built in code ----------------------------------------------------------

fun partColumn(): Unit {
    println("--- a column: fixed, fill, and the cross-axis stretch")
    var f: Frame = newFrame(smSize(220, 320))
    val fp: *Frame = *f
    setLayout(fp, fp.root, BoxLayout.Column)
    setPadding(fp, fp.root, 10)
    setSpacing(fp, fp.root, 5)
    val one: Int = addNode(fp, fp.root, NodeKind.Button, 0, 0, 90, 30)
    setName(fp, one, "one")
    val fill: Int = addNode(fp, fp.root, NodeKind.Panel, 0, 0, 0, 0)
    setName(fp, fill, "fill")
    val two: Int = addNode(fp, fp.root, NodeKind.Button, 0, 0, 0, 30)
    setName(fp, two, "two")
    layoutFrame(fp)
    println(rectText(fp, one))
    println(rectText(fp, fill))
    println(rectText(fp, two))
}

fun partRow(): Unit {
    println("--- a row with a box inside it")
    var f: Frame = newFrame(smSize(200, 100))
    val fp: *Frame = *f
    setLayout(fp, fp.root, BoxLayout.Row)
    setSpacing(fp, fp.root, 4)
    val panel: Int = addNode(fp, fp.root, NodeKind.Panel, 0, 0, 50, 0)
    setName(fp, panel, "panel")
    setLayout(fp, panel, BoxLayout.Column)
    val inner: Int = addNode(fp, panel, NodeKind.Button, 0, 0, 0, 24)
    setName(fp, inner, "inner")
    val rest: Int = addNode(fp, fp.root, NodeKind.Button, 0, 0, 0, 20)
    setName(fp, rest, "rest")
    layoutFrame(fp)
    println(rectText(fp, panel))
    println(rectText(fp, inner))
    println(rectText(fp, rest))
}

// ---- the box model, described in XML --------------------------------------------------------

val formXml: Str = `
<screen name="form" width="200" height="300" layout="column" spacing="5" padding="10">
<button name="top" width="0" height="30">TOP</button>
<panel name="mid" width="0" height="0" layout="row" spacing="4">
<button name="a" width="50" height="20">A</button>
<button name="b" width="0" height="20">B</button>
</panel>
<button name="bot" width="0" height="30">BOT</button>
</screen>
`

val gridBoxXml: Str = `
<screen name="gridbox" width="120" height="90" layout="column" spacing="6">
<label name="cap" width="0" height="18" palette="dim" text="GRID"/>
<grid name="cells" rect="0,0,60,30" cell="10"/>
</screen>
`

fun partXmlBoxes(): Unit {
    println("--- a form out of the loader")
    val loaded: Res<Frame> = loadScreen(formXml)
    if (!loaded.isOk()) {
        println("form failed: " + loaded.error())
        return
    }
    var f: Frame = loaded.value()
    val fp: *Frame = *f
    println(rectText(fp, nodeNamed(fp, "top")))
    println(rectText(fp, nodeNamed(fp, "mid")))
    println(rectText(fp, nodeNamed(fp, "a")))
    println(rectText(fp, nodeNamed(fp, "b")))
    println(rectText(fp, nodeNamed(fp, "bot")))

    println("--- a grid a box positions but does not resize")
    val loadedGrid: Res<Frame> = loadScreen(gridBoxXml)
    if (!loadedGrid.isOk()) {
        println("gridbox failed: " + loadedGrid.error())
        return
    }
    var g: Frame = loadedGrid.value()
    val gp: *Frame = *g
    val cells: Int = nodeNamed(gp, "cells")
    println(rectText(gp, nodeNamed(gp, "cap")))
    println(rectText(gp, cells))
    println("cell 0: " + rectText(gp, gp.nodes[cells].children[0]))
}

fun partXmlErrors(): Unit {
    println("--- documents that do not load")
    println(loadScreen(`<screen width="100" height="100" layout="column"><button rect="0,0,10,10" text="X"/></screen>`).error())
    println(loadScreen(`<screen width="100" height="100"><button width="10" height="10" text="X"/></screen>`).error())
    println(loadScreen(`<screen width="100" height="100" spacing="-2"/>`).error())
    println(loadScreen(`<screen width="100" height="100" layout="diag"/>`).error())
    println(loadScreen(`<screen width="100" height="100" layout="column"><button width="-5" height="10" text="X"/></screen>`).error())
    println(loadScreen(`<screen width="100" height="100"><slider name="s" rect="0,0,50,10" value="2000"/></screen>`).error())
}

// ---- the controls: toggle, track, focus -----------------------------------------------------

fun partControls(): Unit {
    println("--- the controls")
    var f: Frame = newFrame(smSize(220, 180))
    val fp: *Frame = *f

    val opt: Int = addNode(fp, fp.root, NodeKind.Checkbox, 10, 10, 140, 20)
    setName(fp, opt, "opt")
    setText(fp, opt, "Enable")
    val off: Int = addNode(fp, fp.root, NodeKind.Checkbox, 10, 40, 140, 20)
    setName(fp, off, "off")
    setText(fp, off, "Locked")
    setEnabled(fp, off, false)
    val vol: Int = addNode(fp, fp.root, NodeKind.Slider, 10, 70, 200, 20)
    setName(fp, vol, "vol")
    val gain: Int = addNode(fp, fp.root, NodeKind.Slider, 10, 100, 100, 20)
    setName(fp, gain, "gain")
    val field: Int = addNode(fp, fp.root, NodeKind.Edit, 10, 130, 180, 26)
    setName(fp, field, "field")
    setPayload(fp, field, 4)

    var taken: Taken = Taken(0 - 1, 0 - 1, 0 - 1, 0 - 1)
    val takenPtr: *Taken = *taken
    val answer: (Hit, *UiEvent) -> Bool = (h: Hit, e: *UiEvent) -> {
        takenPtr.action = h.action
        takenPtr.node = h.node
        takenPtr.hit = h.hit
        if (h.node >= 0) {
            val carried: Int = fp.nodes[h.node].value
            takenPtr.value = carried
        } else {
            takenPtr.value = 0 - 1
        }
        return true
    }
    var bound: Bool = frameOn(fp, "opt", Act.Opt.toInt(), 0)
    bound = frameOn(fp, "off", Act.Off.toInt(), 0) && bound
    bound = frameOn(fp, "gain", Act.Gain.toInt(), 0) && bound
    bound = frameOn(fp, "field", Act.Commit.toInt(), 0) && bound
    println("bound=" + bound.toString())

    // A key with nothing focused is nobody's: unconsumed, and nothing edits.
    val stray: Hit = uiEvent(fp, *keyDown(120), answer)
    println("key with no focus: hit=" + stray.hit.toString() + " text=\"" + textOf(fp, "field") + "\"")

    // The checkbox toggles on the press, and its listener sees the new value.
    click(fp, 50, 20, answer)
    println("click on opt: action=" + taken.action.toString() + " value=" + valueOf(fp, "opt").toString()
    + " carried=" + taken.value.toString())
    click(fp, 50, 20, answer)
    println("again: value=" + valueOf(fp, "opt").toString())
    // A disabled checkbox is stepped over: no toggle, no listener. The recorder is reset first, so
    // what it prints is about *this* click and not the last delivered one.
    takenPtr.action = 0 - 1
    takenPtr.value = 0 - 1
    click(fp, 50, 50, answer)
    println("click on off: action=" + taken.action.toString() + " value=" + valueOf(fp, "off").toString())

    // The slider follows a captured drag whether or not anyone listens.
    uiEvent(fp, *mouseMove(120, 80), answer)
    uiEvent(fp, *mouseDown(120, 80, Button.Left, 1), answer)
    println("down on vol: pressed=" + nameOf(fp, fp.pressed) + " value=" + valueOf(fp, "vol").toString())
    uiEvent(fp, *mouseMove(70, 80), answer)
    println("drag to 70: value=" + valueOf(fp, "vol").toString())
    uiEvent(fp, *mouseMove(400, 80), answer)
    println("drag past the end: value=" + valueOf(fp, "vol").toString())
    uiEvent(fp, *mouseMove(5, 80), answer)
    println("drag before the start: value=" + valueOf(fp, "vol").toString())
    uiEvent(fp, *mouseUp(5, 80, Button.Left, 0), answer)

    // A slider with a listener is told, and the value it is told about is the press's own.
    uiEvent(fp, *mouseDown(60, 110, Button.Left, 1), answer)
    println("down on gain: action=" + taken.action.toString() + " carried=" + taken.value.toString())
    uiEvent(fp, *mouseUp(60, 110, Button.Left, 0), answer)

    // The edit: focus, type, backspace, the payload limit, and the keys it never takes.
    uiEvent(fp, *mouseDown(50, 143, Button.Left, 1), answer)
    uiEvent(fp, *mouseUp(50, 143, Button.Left, 0), answer)
    println("press on field: focus=" + nameOf(fp, fp.keyFocus))
    uiEvent(fp, *keyDown(104), answer)
    uiEvent(fp, *keyDown(105), answer)
    println("typed \"hi\": text=\"" + textOf(fp, "field") + "\" hit=" + taken.hit.toString())
    uiEvent(fp, *keyDown(SDLK_BACKSPACE), answer)
    println("backspace: text=\"" + textOf(fp, "field") + "\"")
    uiEvent(fp, *keyDown(97), answer)
    uiEvent(fp, *keyDown(98), answer)
    uiEvent(fp, *keyDown(99), answer)
    println("typed \"abc\" (limit 4): text=\"" + textOf(fp, "field") + "\"")
    uiEvent(fp, *keyDown(100), answer)
    println("a fifth char: text=\"" + textOf(fp, "field") + "\"")
    uiEvent(fp, *mouseDown(215, 175, Button.Left, 1), answer)
    uiEvent(fp, *mouseUp(215, 175, Button.Left, 0), answer)
    println("press on the background: focus=" + nameOf(fp, fp.keyFocus))
    val refused: Hit = uiEvent(fp, *keyDown(120), answer)
    println("key with no focus again: hit=" + refused.hit.toString() + " text=\"" + textOf(fp, "field") + "\"")
    uiEvent(fp, *mouseDown(50, 143, Button.Left, 1), answer)
    uiEvent(fp, *mouseUp(50, 143, Button.Left, 0), answer)
    takenPtr.action = 0 - 1
    uiEvent(fp, *keyDown(SDLK_RETURN), answer)
    println("return: action=" + taken.action.toString() + " text=\"" + textOf(fp, "field") + "\"")
    val escape: Hit = uiEvent(fp, *keyDown(SDLK_ESCAPE), answer)
    println("escape while focused: hit=" + escape.hit.toString() + " text=\"" + textOf(fp, "field") + "\"")
    return
}

fun main(): Int {
    partColumn()
    partRow()
    partXmlBoxes()
    partXmlErrors()
    partControls()
    return 0
}
