package minui

// The painter: one visitor over the arena, and the colours it uses. It draws the built-in kinds and
// hands `NodeKind.Custom` to the screen, which is where a control with painting of its own lives.
//
// Draw order is the tree's own - a parent before its children, children in `children` order, so a
// later sibling is on top - which is exactly the order the hit test reverses. Nothing here stores
// state on a node: hover and press come from the frame, so a button's chrome is a function of the
// frame rather than of the last frame's leftovers.

// `Node.palette`: which of its kind's looks a node takes. The labels are read per kind - a Panel's
// `Emphasized` draws its edge, a Label's is title-bold, and `Accent`/`Dim` are text colours - so
// the kind owns the meaning and the XML's `palette="accent"` has somewhere to land.
val PalettePlain: Int = 0
val PaletteEmphasized: Int = 1
val PaletteAccent: Int = 2
val PaletteDim: Int = 3

// The colours the built-in kinds use. A theme is one value, so a screen's palette is one thing to
// keep in step (and a second theme is a second value, not a second code path).
data class UiTheme(
    var background: Int,
    var panel: Int,
    var panelEdge: Int,
    var button: Int,
    var buttonHover: Int,
    var buttonPressed: Int,
    var buttonDisabled: Int,
    var title: Int,
    var body: Int,
    var accent: Int,
    var dim: Int
)

// The visitor. `theme` colours the built-in kinds, and `custom` is called for every visible
// `Custom` node in draw order with its arena index: the app reads the node's rect and payload
// there, and `frame.hover`/`frame.pressed` if it wants interaction feedback.
fun paintFrame(f: *Frame, theme: *UiTheme, ui: *Ui, custom: (Int) -> Unit): Unit {
    paintNode(f, theme, ui, f.root, custom)
}

fun paintNode(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int, custom: (Int) -> Unit): Unit {
    if (!f.nodes[node].visible) {
        return
    }
    paintSelf(f, theme, ui, node, custom)
    var i: Int = 0
    while (i < f.nodes[node].children.size()) {
        paintNode(f, theme, ui, f.nodes[node].children[i], custom)
        i = i + 1
    }
}

// One node's own look. Read through the frame pointer, so nothing is copied to draw it.
fun paintSelf(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int, custom: (Int) -> Unit): Unit {
    val kind: NodeKind = f.nodes[node].kind
    if (kind == NodeKind.Panel) {
        val rect: Rect = f.nodes[node].rect
        uiRoundFill(ui, rect.x, rect.y, rect.width, rect.height, 8, theme.panel)
        if (f.nodes[node].palette == PaletteEmphasized) {
            uiOutline(ui, rect.x, rect.y, rect.width, rect.height, theme.panelEdge)
        }
        return
    }
    if (kind == NodeKind.Label) {
        paintLabel(f, theme, ui, node)
        return
    }
    if (kind == NodeKind.Button) {
        paintButton(f, theme, ui, node)
        return
    }
    if (kind == NodeKind.Sprite) {
        val rect: Rect = f.nodes[node].rect
        uiSprite(ui, f.nodes[node].sprite, rect.x, rect.y, rect.width)
        return
    }
    if (kind == NodeKind.Custom) {
        custom(node)
        return
    }
    // `Root` paints nothing: it is the background, and the app clears to the theme's colour.
}

// A label: body text by default, and the palette picks title-bold, accent or dim. The baseline sits
// the same way `uiTextBox` puts it, so a left-aligned and a centred label share a line height.
fun paintLabel(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val palette: Int = f.nodes[node].palette
    var color: Int = theme.body
    var bold: Bool = false
    if (palette == PaletteEmphasized) {
        color = theme.title
        bold = true
    } else if (palette == PaletteAccent) {
        color = theme.accent
    } else if (palette == PaletteDim) {
        color = theme.dim
    }
    val text: Str = f.nodes[node].text
    val align: Int = f.nodes[node].align
    if (align == Align.Center.toInt()) {
        uiTextBox(ui, rect.x, rect.y, rect.width, rect.height, text, color, bold)
        return
    }
    var size: Int = ui.fontSize
    if (bold) {
        size = ui.boldSize
    }
    val textWidth: Int = uiTextWidth(ui, text, bold)
    var x: Int = rect.x
    if (align == Align.Right.toInt()) {
        x = rect.x + rect.width - textWidth
    }
    val baseline: Int = rect.y + rect.height / 2 + size / 3
    uiText(ui, x, baseline, text, color, bold)
}

// A button: three fills from the theme, picked by the frame's own state (hover and press), and its
// text centred. `palette` is not read - a button's look is its state - and a disabled button is
// hit but never hovered or pressed, because `uiEvent` steps over it.
fun paintButton(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val enabled: Bool = f.nodes[node].enabled
    var face: Int = theme.button
    if (!enabled) {
        face = theme.buttonDisabled
    } else if (f.pressed == node) {
        face = theme.buttonPressed
    } else if (f.hover == node) {
        face = theme.buttonHover
    }
    uiRoundFill(ui, rect.x, rect.y, rect.width, rect.height, 6, face)
    uiTextBox(ui, rect.x, rect.y, rect.width, rect.height, f.nodes[node].text, theme.body, false)
}
