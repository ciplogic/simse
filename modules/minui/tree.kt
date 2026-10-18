package minui

// The headless half of minui: the retained tree (an arena), names, listeners, hit-testing and the
// leaf-to-root walk. No window, no Skia, no SDL - everything here is arithmetic over a `Frame`, so
// it is testable in `stress/` without a screen (the drawing surface lives in `ui.kt` later).
//
// The shape is forced by two Simse facts, both pinned in `minui.md`:
//
//  - **No virtual dispatch.** A node cannot be a polymorphic object, so the tree is one `Node`
//    struct plus a `NodeKind` tag, and behaviour is attached by *name*: the author names a node
//    (`"beginner"`, `"board"`) and code registers a listener for that name once (`frameOn`),
//    which resolves it to the arena index there and then. Nothing on the click path compares
//    strings, and minui never knows what a button does.
//  - **No recursive value type.** `List<Node>` can never live inside `Node` (an incomplete C++
//    type), so children are *indices*: one flat `List<Node>`, and a node's identity is its index.
//    That is also why hit-testing is integer arithmetic and why a grid can be one named node with
//    one listener while each cell is an anonymous node whose `payload` says which cell it is.

enum class NodeKind {
    Root = 0,
    Panel = 1,
    Label = 2,
    Button = 3,
    Sprite = 4,
    Custom = 5,
    Checkbox = 6,
    Edit = 7,
    Slider = 8,
    Progress = 9
}

// How a node places its children: `None` is v1's absolute rects (the child's own `rect` is where
// it is), `Column` stacks them top to bottom and `Row` lines them up left to right (`layoutFrame`).
// A box keeps painting and hit-testing exactly as any node does - layout is only where the rects
// come from.
enum class BoxLayout {
    None = 0,
    Column = 1,
    Row = 2
}

// Which of a kind's looks a node takes (paint.kt reads it). An enum, like `Align`, so a call site
// says `Palette.Emphasized` rather than carrying an index of its own.
enum class Palette {
    Plain = 0,
    Emphasized = 1,
    Accent = 2,
    Dim = 3
}

data class Rect(var x: Int, var y: Int, var width: Int, var height: Int)

// The rect builder the app and the painter spell: `smRect(8, 8, 100, 24)`. It is the same value the
// constructor builds (the language has no default arguments yet), named so a call site reads as the
// Simse rect it is - there is no other rect type above the glue, and no float in it.
fun smRect(x: Int, y: Int, width: Int, height: Int): Rect {
    return Rect(x, y, width, height)
}

// A width and a height that travel together: a window's, a frame's, a cell's. `Rect` is the four
// numbers a *place* needs; most sizes are not places, and `smSize(294, 382)` says which is which.
data class Size(var width: Int, var height: Int)

fun smSize(width: Int, height: Int): Size {
    return Size(width, height)
}

// The kinds of input the loop feeds the tree. `Tick` is the frame clock (a timer), not a device.
enum class UiEventKind {
    None = 0,
    MouseMove = 1,
    MouseDown = 2,
    MouseUp = 3,
    Wheel = 4,
    KeyDown = 5,
    KeyUp = 6,
    Close = 7,
    Resize = 8,
    Tick = 9
}

// SDL's own numbering (left/middle/right = 1/2/3), named so no call site holds a magic number.
enum class Button {
    None = 0,
    Left = 1,
    Middle = 2,
    Right = 3
}

// The *mask* bits (`SDL_BUTTON_LMASK` and friends), which is what `UiEvent.buttons` holds - a
// different thing from `Button`, which says which button an event is about. A chord (both
// buttons held) is `MaskLeft + MaskRight`.
val MaskLeft: Int = 1
val MaskMiddle: Int = 2
val MaskRight: Int = 4

// `buttons` is the *mask* of buttons held when the event happened - SDL's `SDL_BUTTON_*MASK`
// values, so `Left` is 1, `Middle` 2 and `Right` 4. That is how a chord (both buttons, or the
// middle one) is told apart from a plain click: a Left+Right chord has `buttons == 5`.
data class UiEvent(
    var kind: UiEventKind,
    var x: Int,
    var y: Int,
    var button: Button,
    var buttons: Int,
    var key: Int,
    var wheel: Int
)

// The three constructors a caller actually writes (the SDL wrapper fills the same struct from the
// platform's event, so nothing else builds one field by field).
fun mouseMove(x: Int, y: Int): UiEvent {
    return UiEvent(UiEventKind.MouseMove, x, y, Button.None, 0, 0, 0)
}

fun mouseDown(x: Int, y: Int, button: Button, buttons: Int): UiEvent {
    return UiEvent(UiEventKind.MouseDown, x, y, button, buttons, 0, 0)
}

fun mouseUp(x: Int, y: Int, button: Button, buttons: Int): UiEvent {
    return UiEvent(UiEventKind.MouseUp, x, y, button, buttons, 0, 0)
}

// A keystroke, for the headless tests of the key path (a script's `:K` entries reach the app's
// loop as these, through SDL's own queue).
fun keyDown(key: Int): UiEvent {
    return UiEvent(UiEventKind.KeyDown, 0, 0, Button.None, 0, key, 0)
}

// One node. POD-ish on purpose: the walk reads these by pointer and never copies one.
// `name` is the author's handle (`x:Name`, HTML's `id`), `""` when anonymous; `listener` is the
// *resolved* registration (-1 = none); `payload` is the app's own index into what the node shows
// (a cell, a difficulty, a slot) and the framework never reads it - with one exception, an
// `Edit`'s max length; `palette` is the paint variant (which of the kind's looks, see
// `paint.kt`); `align` places a `Label`'s text (`Align`). (`data` is a keyword here, so the field
// is `payload` - `setPayload` is the setter.) The last four are the box model and the controls:
// `layout`/`spacing`/`padding` say how a *box* arranges its children (`BoxLayout`, `layoutFrame`)
// and `value` is the framework-owned state of a control (a Checkbox's 0/1, a Slider's and a
// Progress's 0..1000) - hover and press are the frame's, but a control's own setting has to live
// on the node.
data class Node(
    var kind: NodeKind,
    var name: Str,
    var rect: Rect,
    var parent: Int,
    var children: List<Int>,
    var listener: Int,
    var payload: Int,
    var text: Str,
    var palette: Int,
    var align: Int,
    var sprite: Int,
    var visible: Bool,
    var enabled: Bool,
    var layout: Int,
    var spacing: Int,
    var padding: Int,
    var value: Int
)

// What a name resolved to, once: the node, the action code the screen wants called for it, and
// the payload the registration carried.
data class Listener(var node: Int, var action: Int, var payload: Int)

// What the walk hands the screen. `node` is the node the listener sits on, `hit` the leaf-most
// node the event is *about* - a grid shares one listener, so the leaf is what says which cell.
// `action == -1` is "nobody consumed it".
data class Hit(var action: Int, var node: Int, var hit: Int, var payload: Int)

// Text placement for a `Label` (and the painter's centring of a `Button`'s label).
enum class Align {
    Left = 0,
    Center = 1,
    Right = 2
}

// A screen's whole UI. `names` is built as the tree is built (a duplicate is refused by `setName`),
// `hover`/`pressed` are the framework's own interaction state, kept by arena index. `hover` is the
// node a click *would reach* - the bubble target, not the raw leaf - which is what control chrome
// wants; a grid that wants per-cell feedback reads the event's coordinates itself.
data class Frame(
    var nodes: List<Node>,
    var listeners: List<Listener>,
    var names: Dictionary<Str, Int>,
    var root: Int,
    var hover: Int,
    var pressed: Int,
    var keyFocus: Int
)

// A frame with one `Root` node covering the whole size, already named `"root"` so code can
// bind a listener to the background (a screen that wants to see clicks nobody else took).
fun newFrame(size: Size): Frame {
    val root: Node = Node(
        NodeKind.Root, "root", Rect(0, 0, size.width, size.height), 0 - 1,
        List<Int>(0, 0), 0 - 1, 0, "", 0, 0, 0, true, true, 0, 0, 0, 0
    )
    var nodes: List<Node> = List<Node>(0, root)
    nodes.append(root)
    var names: Dictionary<Str, Int> = dictionaryOf<Str, Int>()
    names.insert("root", 0)
    return Frame(nodes, List<Listener>(0, Listener(0, 0, 0)), names, 0, 0 - 1, 0 - 1, 0 - 1)
}

// Append a child of `parent` and answer its arena index - the node's identity, which is what
// hover, press and the walk compare. Geometry is absolute unless the parent is a layout box, in
// which case `layoutFrame` places the child and only its width/height are read (0 = stretch or
// fill, `layoutFrame` below).
// Append a child of `parent` and answer its arena index - the node's identity, which is what
// hover, press and the walk compare. Geometry is absolute unless the parent is a layout box, in
// which case `layoutFrame` places the child and only its width/height are read (0 = stretch or
// fill, `layoutFrame` below).
fun addNode(f: *Frame, parent: Int, kind: NodeKind, x: Int, y: Int, width: Int, height: Int): Int {
    val node: Node = Node(
        kind, "", Rect(x, y, width, height), parent, List<Int>(0, 0),
        0 - 1, 0, "", 0, 0, 0, true, true, 0, 0, 0, 0
    )
    val index: Int = f.nodes.size()
    f.nodes.append(node)
    var kids: List<Int> = f.nodes[parent].children
    kids.append(index)
    f.nodes[parent].children = kids
    return index
}

// A grid: one named parent plus `cols * rows` anonymous children laid out on a cell size, each
// with `payload` = its own index (row-major). This is the board: one listener for all of it, and
// the leaf still says which cell was hit.
fun addGrid(f: *Frame, parent: Int, name: Str, x: Int, y: Int, cell: Int, cols: Int, rows: Int): Int {
    val grid: Int = addNode(f, parent, NodeKind.Custom, x, y, cell * cols, cell * rows)
    setName(f, grid, name)
    var row: Int = 0
    while (row < rows) {
        var col: Int = 0
        while (col < cols) {
            val index: Int = addNode(
                f, grid, NodeKind.Custom,
                x + col * cell, y + row * cell, cell, cell
            )
            setPayload(f, index, row * cols + col)
            col = col + 1
        }
        row = row + 1
    }
    return grid
}

// The node a name resolved to, or -1. A handler uses this when the tree may have been rebuilt under
// it (a new difficulty, a new screen): names survive a rebuild, indices do not.
fun nodeNamed(f: *Frame, name: Str): Int {
    val found: *Int = f.names.getPtr(name)
    if (found == null) {
        return 0 - 1
    }
    return * found
}

// Write a named node's text, if the name is in the tree. Quiet on a missing name (a bug the
// binding's `frameOn` already reported at setup) so a rebuild cannot crash a frame.
fun setTextNamed(f: *Frame, name: Str, text: Str): Unit {
    val node: Int = nodeNamed(f, name)
    if (node >= 0) {
        setText(f, node, text)
    }
}

// Name a node. A duplicate - or the empty name, which means "anonymous" - is refused, so the
// name -> index map never disagrees with the tree.
fun setName(f: *Frame, node: Int, name: Str): Bool {
    if (name.size() == 0 || f.names.has(name)) {
        return false
    }
    f.names.insert(name, node)
    f.nodes[node].name = name
    return true
}

// Rename a frame's root, which starts as "root" (the name a background listener binds to before a
// screen has a name of its own). A general rename would have to move the old entry out of the map;
// this is the one case that needs it - the XML loader names its root from `<screen name="...">` -
// and doing it here keeps the map honest instead of writing a second entry for the same node.
fun nameRoot(f: *Frame, name: Str): Bool {
    val old: Str = f.nodes[f.root].name
    if (name.size() == 0) {
        return false
    }
    if (name == old) {
        return true
    }
    if (f.names.has(name)) {
        return false
    }
    f.names.remove(old)
    f.nodes[f.root].name = name
    f.names.insert(name, f.root)
    return true
}

fun setText(f: *Frame, node: Int, text: Str): Unit {
    f.nodes[node].text = text
}

fun setPayload(f: *Frame, node: Int, payload: Int): Unit {
    f.nodes[node].payload = payload
}

fun setPalette(f: *Frame, node: Int, palette: Palette): Unit {
    f.nodes[node].palette = palette.toInt()
}

// Which of the kind's looks a node takes (see `paint.kt`), and where a label's text sits.
fun setAlign(f: *Frame, node: Int, align: Align): Unit {
    f.nodes[node].align = align.toInt()
}

fun setSprite(f: *Frame, node: Int, sprite: Int): Unit {
    f.nodes[node].sprite = sprite
}

fun setVisible(f: *Frame, node: Int, visible: Bool): Unit {
    f.nodes[node].visible = visible
}

fun setEnabled(f: *Frame, node: Int, enabled: Bool): Unit {
    f.nodes[node].enabled = enabled
}

// Make a node a layout box (`vbox`/`hbox` in XML): its children are placed by `layoutFrame`
// instead of carrying their own x/y. `spacing` is the gap between children, `padding` the gap
// around them, and a control's `value` is its own state (a Checkbox's 0/1, a Slider's and a
// Progress's 0..1000) - the framework keeps hover and press, the app reads and writes this.
fun setLayout(f: *Frame, node: Int, layout: BoxLayout): Unit {
    f.nodes[node].layout = layout.toInt()
}

fun setSpacing(f: *Frame, node: Int, spacing: Int): Unit {
    f.nodes[node].spacing = spacing
}

fun setPadding(f: *Frame, node: Int, padding: Int): Unit {
    f.nodes[node].padding = padding
}

fun setValue(f: *Frame, node: Int, value: Int): Unit {
    f.nodes[node].value = value
}

// ---- layout: the box model ---------------------------------------------------
//
// A box (any node with `layout != None`, the root included) places its children along one axis:
// a `Column` stacks them top to bottom, a `Row` lines them up left to right, `spacing` apart
// inside `padding`. A child's own size is what it asks for:
//
//   - main axis (the column's height, the row's width): the child's own value, or - when it is 0
//     - an equal share of the space the other children left over (a board under a toolbar, a
//     spring; several such children split it equally),
//   - cross axis: the child's own value, or - when it is 0 - the box's inner width/height, so a
//     button in a column is a full-width button and a row's children share one height.
//
// Layout is **one shot per build**: `layoutFrame` writes the resolved sizes over the requested
// ones (a stretched child really is as wide as the box afterwards), so a frame that needs to
// re-flow is rebuilt - the same idiom a difficulty change already uses. Children of a moved
// child move with it (`moveNode`): a grid or a hand-placed panel inside a box keeps its inside.

// Lay out every box in the frame, parents before children (an inner box is sized by its own
// parent before it sizes its children). `loadScreen` runs this before answering; a frame built
// in code runs it once, after the last node is added.
fun layoutFrame(f: *Frame): Unit {
    layoutNode(f, f.root)
}

fun layoutNode(f: *Frame, node: Int): Unit {
    val layout: Int = f.nodes[node].layout
    if (layout != BoxLayout.None.toInt()) {
        layoutChildren(f, node, layout)
    }
    val kids: List<Int> = f.nodes[node].children
    var i: Int = 0
    while (i < kids.size()) {
        layoutNode(f, kids[i])
        i = i + 1
    }
}

// One box's children, along `layout`. All children are laid out whether or not they are visible:
// hiding a row is not re-flowing the screen (rebuild the frame for that), it is taking it out of
// the paint and the hit test only.
fun layoutChildren(f: *Frame, box: Int, layout: Int): Unit {
    val kids: List<Int> = f.nodes[box].children
    val count: Int = kids.size()
    if (count == 0) {
        return
    }
    val rect: Rect = f.nodes[box].rect
    val pad: Int = f.nodes[box].padding
    val gap: Int = f.nodes[box].spacing
    val innerX: Int = rect.x + pad
    val innerY: Int = rect.y + pad
    val innerWidth: Int = rect.width - pad * 2
    val innerHeight: Int = rect.height - pad * 2
    val column: Bool = layout == BoxLayout.Column.toInt()

    // The measure pass: what the fixed children take, and how many want the remainder.
    var fixed: Int = 0
    var fills: Int = 0
    var i: Int = 0
    while (i < count) {
        val child: Int = kids[i]
        if (column) {
            if (f.nodes[child].rect.height > 0) {
                fixed = fixed + f.nodes[child].rect.height
            } else {
                fills = fills + 1
            }
        } else {
            if (f.nodes[child].rect.width > 0) {
                fixed = fixed + f.nodes[child].rect.width
            } else {
                fills = fills + 1
            }
        }
        i = i + 1
    }
    var each: Int = 0
    if (fills > 0) {
        var remain: Int = 0
        if (column) {
            remain = innerHeight - gap * (count - 1) - fixed
        } else {
            remain = innerWidth - gap * (count - 1) - fixed
        }
        if (remain < 0) {
            remain = 0
        }
        each = remain / fills
    }

    // The place pass: position the subtree (children of a child move with it), then write the
    // resolved main size over the requested one.
    var cursor: Int = innerY
    if (!column) {
        cursor = innerX
    }
    i = 0
    while (i < count) {
        val child: Int = kids[i]
        val old: Rect = f.nodes[child].rect
        var x: Int = innerX
        var y: Int = innerY
        var width: Int = old.width
        var height: Int = old.height
        if (column) {
            height = old.height
            if (old.height <= 0) {
                height = each
            }
            if (old.width <= 0) {
                width = innerWidth
            }
            y = cursor
        } else {
            width = old.width
            if (old.width <= 0) {
                width = each
            }
            if (old.height <= 0) {
                height = innerHeight
            }
            x = cursor
        }
        val dx: Int = x - old.x
        val dy: Int = y - old.y
        if (dx != 0 || dy != 0) {
            moveNode(f, child, dx, dy)
        }
        f.nodes[child].rect.width = width
        f.nodes[child].rect.height = height
        if (column) {
            cursor = y + height + gap
        } else {
            cursor = x + width + gap
        }
        i = i + 1
    }
}

// Write a whole rect. The two things v1 needs it for are both "the whole screen": a frame's own
// size, which `stackResizeTop` reads from the root, and a modal overlay's scrim, which has to
// cover a window the XML cannot know the size of.
fun setRect(f: *Frame, node: Int, rect: Rect): Unit {
    f.nodes[node].rect = rect
}

// Shift a node and everything under it by (dx, dy). This is the one geometry transform v1 has, and
// it is what a dialog needs to sit in the middle of a window (the whole subtree moves, because the
// rects are absolute). Moving is one-way and two calls add up; a caller that wants a clean offset
// loads a fresh screen for each push, which is what the example's result panel does.
fun moveNode(f: *Frame, node: Int, dx: Int, dy: Int): Unit {
    f.nodes[node].rect.x = f.nodes[node].rect.x + dx
    f.nodes[node].rect.y = f.nodes[node].rect.y + dy
    val kids: List<Int> = f.nodes[node].children
    var i: Int = 0
    while (i < kids.size()) {
        moveNode(f, kids[i], dx, dy)
        i = i + 1
    }
}

// Bind behaviour to a name, once, when the screen is built: XAML's code-behind without
// reflection. The name is resolved here, so the click path compares indices; a listener on a name
// that is not in the tree answers false, which is a bug in the caller's binding (the caller can
// check it at setup, where it is cheap to see).
fun frameOn(f: *Frame, name: Str, action: Int, payload: Int): Bool {
    val found: *Int = f.names.getPtr(name)
    if (found == null) {
        return false
    }
    val node: Int = *found
    val index: Int = f.listeners.size()
    f.listeners.append(Listener(node, action, payload))
    f.nodes[node].listener = index
    return true
}

// The leaf-most visible node whose rect contains the point: children before parents, later
// siblings above earlier ones - the painter's order, reversed. -1 when nothing contains it.
fun uiHitTest(f: *Frame, x: Int, y: Int): Int {
    return uiHitTestNode(f, f.root, x, y)
}

fun uiHitTestNode(f: *Frame, node: Int, x: Int, y: Int): Int {
    if (!f.nodes[node].visible) {
        return 0 - 1
    }
    var i: Int = f.nodes[node].children.size() - 1
    while (i >= 0) {
        val found: Int = uiHitTestNode(f, f.nodes[node].children[i], x, y)
        if (found >= 0) {
            return found
        }
        i = i - 1
    }
    if (x >= f.nodes[node].rect.x && x < f.nodes[node].rect.x + f.nodes[node].rect.width
        && y >= f.nodes[node].rect.y && y < f.nodes[node].rect.y + f.nodes[node].rect.height
    ) {
        return node
    }
    return 0 - 1
}

// The node a click on `leaf` reaches: the first ancestor (leaf included) that is enabled and has
// a listener - or is an enabled built-in control, which owns its own state and chrome even when
// no listener is bound (a Checkbox toggles, a Slider tracks, whether or not the app asked to be
// told). A disabled node is still *hit* (and still paints itself greyed out) but it does not take
// the click - the walk steps over it to the parent, which is what `hover` shows.
fun uiBubbleTarget(f: *Frame, leaf: Int): Int {
    var node: Int = leaf
    while (node >= 0) {
        val kind: NodeKind = f.nodes[node].kind
        val control: Bool = kind == NodeKind.Checkbox || kind == NodeKind.Edit
                || kind == NodeKind.Slider
        if (f.nodes[node].enabled && (f.nodes[node].listener >= 0 || control)) {
            return node
        }
        node = f.nodes[node].parent
    }
    return 0 - 1
}

// The leaf -> root walk: each enabled listener on the way is asked in turn, and the first one that
// answers true consumes the event. `from` is where the walk starts (the leaf, or the captured node
// on a release), `reported` is the leaf the event is about. The answer *is* the screen - minui
// holds no callback of its own and needs no vtable to reach one - and it is handed the event too,
// because which button was pressed (and whether this is the down or the up) is the app's business.
fun uiWalk(
    f: *Frame, e: *UiEvent, from: Int, reported: Int,
    answer: (Hit, *UiEvent) -> Bool
): Hit {
    var node: Int = from
    while (node >= 0) {
        val listener: Int = f.nodes[node].listener
        if (listener >= 0 && f.nodes[node].enabled) {
            val h: Hit = Hit(f.listeners[listener].action, node, reported, f.listeners[listener].payload)
            if (answer(h, e)) {
                return h
            }
        }
        node = f.nodes[node].parent
    }
    return Hit(0 - 1, 0 - 1, reported, 0)
}

// The framework's whole input path for one event:
//
//   move  -> `hover` becomes the node a click would reach; while a press is held, the move is
//            delivered to the captured node (so a control can track a drag) and a captured
//            Slider follows the cursor (its `value` is updated before the walk, so a listener
//            reads the position live).
//   down  -> the walk runs from the leaf; the node a click would reach is captured. Whether the
//            app *acts* on the press or on the release is the app's business: Minesweeper's cells
//            open on the press, a menu button fires on the release, and both get their event.
//            A press also settles the two states a click owns outright: an `Edit` under the
//            cursor takes the key focus (a press anywhere else gives it up), and a `Checkbox`
//            toggles its `value` - before the walk, so its listener reads the new setting.
//   up    -> delivered to the captured node (and its ancestors), wherever the cursor is now; the
//            capture is released. `h.hit` is the leaf under the cursor at release, so a control
//            can implement drag-off-cancel by comparing `h.hit` with `h.node`.
//   key   -> a KeyDown goes to `keyFocus` when that is an `Edit` (printable keys append,
//            Backspace removes, Return walks the Edit's listener - the commit hook); anything
//            else, and every key with no Edit focused, is handed back unconsumed for the app.
//
// Wheel, close and tick do not hit-test; a caller sends those straight to the layer, and `Tick`
// is what drives a redraw when nothing else happens.
fun uiEvent(f: *Frame, e: *UiEvent, answer: (Hit, *UiEvent) -> Bool): Hit {
    if (e.kind == UiEventKind.MouseMove) {
        val leaf: Int = uiHitTest(f, e.x, e.y)
        f.hover = uiBubbleTarget(f, leaf)
        if (f.pressed >= 0) {
            sliderTrack(f, f.pressed, e.x)
            return uiWalk(f, e, f.pressed, leaf, answer)
        }
        return Hit(0 - 1, 0 - 1, leaf, 0)
    }
    if (e.kind == UiEventKind.MouseDown) {
        val leaf: Int = uiHitTest(f, e.x, e.y)
        f.hover = uiBubbleTarget(f, leaf)
        f.keyFocus = uiEditAt(f, leaf)
        if (leaf >= 0 && e.button != Button.Right) {
            val leafKind: NodeKind = f.nodes[leaf].kind
            if (leafKind == NodeKind.Checkbox && f.nodes[leaf].enabled) {
                if (f.nodes[leaf].value == 0) {
                    f.nodes[leaf].value = 1
                } else {
                    f.nodes[leaf].value = 0
                }
            }
        }
        f.pressed = f.hover
        sliderTrack(f, f.hover, e.x)
        return uiWalk(f, e, leaf, leaf, answer)
    }
    if (e.kind == UiEventKind.MouseUp) {
        val leaf: Int = uiHitTest(f, e.x, e.y)
        f.hover = uiBubbleTarget(f, leaf)
        val captured: Int = f.pressed
        f.pressed = 0 - 1
        if (captured >= 0) {
            return uiWalk(f, e, captured, leaf, answer)
        }
        return Hit(0 - 1, 0 - 1, leaf, 0)
    }
    if (e.kind == UiEventKind.KeyDown) {
        return uiKey(f, e, answer)
    }
    return Hit(0 - 1, 0 - 1, 0 - 1, 0)
}

// The Edit a press on `leaf` focuses: the leaf itself when it is an enabled Edit, otherwise -1 -
// a click anywhere else means the Edit gives the keys up. The *leaf* rather than the bubble
// target, so an Edit whose listener sits on an ancestor still takes the focus.
fun uiEditAt(f: *Frame, leaf: Int): Int {
    if (leaf < 0 || f.nodes[leaf].kind != NodeKind.Edit || !f.nodes[leaf].enabled) {
        return 0 - 1
    }
    return leaf
}

// A Slider's value from an x: where the cursor is along the track, as 0..1000. No-op for any
// other node (or a disabled slider), so the event path can call it unconditionally.
fun sliderTrack(f: *Frame, node: Int, x: Int): Unit {
    if (node < 0 || f.nodes[node].kind != NodeKind.Slider || !f.nodes[node].enabled) {
        return
    }
    val rect: Rect = f.nodes[node].rect
    if (rect.width <= 0) {
        return
    }
    var value: Int = (x - rect.x) * 1000 / rect.width
    if (value < 0) {
        value = 0
    }
    if (value > 1000) {
        value = 1000
    }
    f.nodes[node].value = value
}

// The 95 printable ASCII characters, in code order: SDL's keycode for a letter or a digit *is*
// its ASCII value, so the character a key means is this table's entry `key - 32`. (There is no
// Int-to-Char conversion in the language, and this table is smaller than one.)
val EditPrintable: Str = " !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~"

// An Edit's default maximum length, when its `payload` does not name one. `payload` is otherwise
// the app's own index and the framework never reads it - an Edit's max length is the one
// documented exception (the loader's `payload="40"` is this and nothing else).
val EditDefaultLimit: Int = 120

// One keystroke for the focused Edit. Printable ASCII appends (to the end - there is no caret
// to move, no selection, no IME in this UI), Backspace drops the last character, Return walks
// the Edit's own listener so the app can commit on it. Escape is *not* consumed: what Escape
// does stays the app's decision (the example pops a screen with it).
fun uiKey(f: *Frame, e: *UiEvent, answer: (Hit, *UiEvent) -> Bool): Hit {
    val focus: Int = f.keyFocus
    if (focus < 0) {
        return Hit(0 - 1, 0 - 1, 0 - 1, 0)
    }
    val kind: NodeKind = f.nodes[focus].kind
    if (kind != NodeKind.Edit || !f.nodes[focus].enabled) {
        return Hit(0 - 1, 0 - 1, 0 - 1, 0)
    }
    val key: Int = e.key
    if (key == SDLK_BACKSPACE) {
        val text: Str = f.nodes[focus].text
        if (text.size() > 0) {
            f.nodes[focus].text = text.substr(0, text.size() - 1)
        }
        return Hit(0 - 1, 0 - 1, focus, 0)
    }
    if (key == SDLK_RETURN) {
        return uiWalk(f, e, focus, focus, answer)
    }
    if (key >= 32 && key <= 126) {
        var limit: Int = f.nodes[focus].payload
        if (limit <= 0) {
            limit = EditDefaultLimit
        }
        var text: Str = f.nodes[focus].text
        if (text.size() < limit) {
            text.append(EditPrintable[key - 32])
            f.nodes[focus].text = text
        }
        return Hit(0 - 1, 0 - 1, focus, 0)
    }
    return Hit(0 - 1, 0 - 1, 0 - 1, 0)
}
