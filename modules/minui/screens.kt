package minui

// The screen stack: the Heroes-shaped navigation - a menu pushes a game, the game pushes a city or
// a battle, and backing out pops the way it came. What a layer *is* is small: the screen's frame,
// the name the stack knows it by, and whether it is opaque.
//
// A layer holds a `&Frame` **box**, not a `Frame` value: a push shares the screen rather than
// copying an arena of up to 480 nodes, and an app that names its own frames keeps one owner per
// screen (the box). The stack is a value like everything else in Simse, so it is passed by pointer
// and mutated in place.
//
// The rules, in one place, so they are testable headless (`stress/minui-xml`):
//
//  - **A stack is never empty.** `newStack` takes the first screen (the menu is always there), and
//    `stackPop` refuses to take the last layer away. There is therefore no "no screen" case for any
//    caller to handle, and `stackTop` needs no error arm.
//  - **Painting**: from the bottom up, starting at the *last opaque* layer - everything below it is
//    covered anyway, so a menu under a full-window game screen costs no drawing at all. A
//    translucent overlay is an opaque=false layer: it paints over what is below, and it dims it
//    itself by drawing a scrim.
//  - **Input**: only the top layer is dispatched to. An overlay that wants input (a dialog) is
//    therefore the top layer while it is up, and the screen under it comes back with its own
//    hover/press state (a `Frame`'s own fields, untouched while it is not on top).
//  - **The window follows the top layer**: `stackResizeTop` reads the top frame's root rect, which
//    is where `newFrame`'s width/height went. Screens may legitimately differ in size - the menu
//    and Expert Minesweeper do - and that is the one place that is reconciled.

// One layer of the stack.
data class Layer(var frame: &Frame, var name: Str, var opaque: Bool)

// The stack itself: a list with a first layer, always.
data class ScreenStack(var layers: List<Layer>)

// A stack with its first (bottom) screen in it. Nothing else can create one, so no stack is empty.
fun newStack(first: &Frame, name: Str, opaque: Bool): ScreenStack {
    return ScreenStack(listOf<Layer>(Layer(first, name, opaque)))
}

fun stackPush(stack: *ScreenStack, frame: &Frame, name: Str, opaque: Bool): Unit {
    stack.layers.append(Layer(frame, name, opaque))
}

// Drop the top layer. Refuses (and answers false) when only the first layer is left: a screen
// reaches `stackPop` by backing out of itself, and the bottom screen has nowhere to back out to -
// that is the app's business (minui does not decide when a window closes).
fun stackPop(stack: *ScreenStack): Bool {
    if (stack.layers.size() <= 1) {
        return false
    }
    stack.layers.removeAt(stack.layers.size() - 1)
    return true
}

fun stackSize(stack: *ScreenStack): Int {
    return stack.layers.size()
}

// The top layer's name - what the app switches on ("menu", "game") where the interface would
// otherwise dispatch on a type. The bottom layer's name when only it is left.
fun stackTopName(stack: *ScreenStack): Str {
    return stack.layers[stack.layers.size() - 1].name
}

// The top layer's frame, as the raw pointer every tree function takes. It stays valid while the box
// is in the stack: `stackPop` is what invalidates it, and only for the layer it removes.
fun stackTop(stack: *ScreenStack): *Frame {
    val box: &Frame = stack.layers[stack.layers.size()-1].frame
    return * box
}

// The lowest layer that still needs painting: the last opaque one. A stack of one answers 0.
fun stackPaintBase(stack: *ScreenStack): Int {
    var base: Int = 0
    var level: Int = 0
    while (level < stack.layers.size()) {
        if (stack.layers[level].opaque) {
            base = level
        }
        level++
    }
    return base
}

// Paint the stack bottom-up from the last opaque layer. `custom` is handed the screen's *name* as
// well as the node, because a screen's custom painting is the app's own code and the app switches
// on it - the same string switch the event path does, and for the same reason (no vtable).
fun stackPaint(stack: *ScreenStack, theme: *UiTheme, ui: *Ui, custom: (Str, Int) -> Unit): Unit {
    val base: Int = stackPaintBase(stack)
    var level: Int = base
    while (level < stack.layers.size()) {
        val layer: Layer = stack.layers[level]
        val name: Str = layer.name
        val box: &Frame = layer.frame
        val perNode: (Int) -> Unit = (node: Int) -> {
            custom(name, node)
        }
        paintFrame(*box, *theme, ui, perNode)
        level++
    }
}

// One event, to the top layer only.
fun stackEvent(stack: *ScreenStack, e: *UiEvent, answer: (Hit, *UiEvent) -> Bool): Hit {
    val f: *Frame = stackTop(stack)
    return uiEvent(f, e, answer)
}

// The window follows the top screen: a push or a pop is usually a different window size (the menu
// is 360x348, Expert Minesweeper 744x496). The size is the frame's root rect, which is where
// `newFrame`'s width and height went.
fun stackResizeTop(stack: *ScreenStack, ui: *Ui): Bool {
    val f: *Frame = stackTop(stack)
    val r: Rect = f.nodes[f.root].rect
    return uiResize(ui, smSize(r.width, r.height))
}
