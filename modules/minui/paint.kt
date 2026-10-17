package minui

// The painter: one visitor over the arena, and the colours it uses. It draws the built-in kinds and
// hands `NodeKind.Custom` to the screen, which is where a control with painting of its own lives.
//
// Draw order is the tree's own - a parent before its children, children in `children` order, so a
// later sibling is on top - which is exactly the order the hit test reverses. Nothing here stores
// state on a node: hover and press come from the frame, so a button's chrome is a function of the
// frame rather than of the last frame's leftovers.

// A colour: the four channels, each 0..255, in the order the four letters are in. No packing and no
// magic integer: `winArgb` is where the shift happens (the one place `Uint32` exists), and nothing
// above the glue holds a value that has to be taken apart again.
data class Color(var red: Int, var green: Int, var blue: Int, var alpha: Int)

// The builders, like `smRect` for rects: `smColor(58, 90, 138)` is the opaque spelling and
// `smRgba(150, 8, 10, 16)` names its alpha.
fun smColor(red: Int, green: Int, blue: Int): Color {
    return Color(red, green, blue, 255)
}

fun smRgba(red: Int, green: Int, blue: Int, alpha: Int): Color {
    return Color(red, green, blue, alpha)
}

// The colours the built-in kinds use. A theme is one value, so a screen's palette is one thing to
// keep in step (and a second theme is a second value, not a second code path). `Node.palette`
// (`Palette`, tree.kt) says which of them a node takes: a Panel's `Emphasized` draws its edge, a
// Label's is title-bold, and `Accent`/`Dim` are text colours - so the kind owns the meaning and the
// XML's `palette="accent"` has somewhere to land.
data class UiTheme(
    var background: Color,
    var panel: Color,
    var panelEdge: Color,
    var button: Color,
    var buttonHover: Color,
    var buttonPressed: Color,
    var buttonDisabled: Color,
    var title: Color,
    var body: Color,
    var accent: Color,
    var dim: Color
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
        uiRoundFill(ui, rect, 8, theme.panel)
        if (f.nodes[node].palette == Palette.Emphasized.toInt()) {
            uiOutline(ui, rect, theme.panelEdge)
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
        uiSprite(ui, f.nodes[node].sprite, rect)
        return
    }
    if (kind == NodeKind.Custom) {
        custom(node)
        return
    }
    if (kind == NodeKind.Checkbox) {
        paintCheckbox(f, theme, ui, node)
        return
    }
    if (kind == NodeKind.Edit) {
        paintEdit(f, theme, ui, node)
        return
    }
    if (kind == NodeKind.Slider) {
        paintSlider(f, theme, ui, node)
        return
    }
    if (kind == NodeKind.Progress) {
        paintProgress(f, theme, ui, node)
        return
    }
    // `Root` paints nothing: it is the background, and the app clears to the theme's colour.
}

// A checkbox: the three button fills on a 16x16 ticked square (the frame's own hover/press
// state), an accent check when `value` is set, and its text beside the box - left aligned at the
// box's own baseline, the way a form's rows read. The toggle itself is the tree's (`uiEvent`
// flips `value` on the press, before the listener runs).
fun paintCheckbox(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val enabled: Bool = f.nodes[node].enabled
    var face: Color = theme.button
    if (!enabled) {
        face = theme.buttonDisabled
    } else if (f.pressed == node) {
        face = theme.buttonPressed
    } else if (f.hover == node) {
        face = theme.buttonHover
    }
    val boxY: Int = rect.y + (rect.height - 16) / 2
    uiRoundFill(ui, smRect(rect.x, boxY, 16, 16), 3, face)
    if (f.nodes[node].value != 0) {
        uiLine(ui, rect.x + 4, boxY + 8, rect.x + 7, boxY + 11, theme.accent)
        uiLine(ui, rect.x + 7, boxY + 11, rect.x + 12, boxY + 4, theme.accent)
    }
    val text: Str = f.nodes[node].text
    if (text.size() > 0) {
        val baseline: Int = rect.y + rect.height / 2 + ui.fontSize / 3
        uiText(ui, rect.x + 24, baseline, text, theme.body, false)
    }
}

// An edit: a flat field (the panel's fill) with the panel's edge, the accent edge while it holds
// the key focus, its text left aligned at the same baseline a label uses, and - while focused -
// a caret after the text, blinking on SDL's own clock. The editing itself is the tree's
// (`uiKey`): this only draws what `text` already holds.
fun paintEdit(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    var face: Color = theme.panel
    if (!f.nodes[node].enabled) {
        face = theme.buttonDisabled
    }
    uiFill(ui, rect, face)
    var edge: Color = theme.panelEdge
    if (f.keyFocus == node) {
        edge = theme.accent
    }
    uiOutline(ui, rect, edge)
    val text: Str = f.nodes[node].text
    val inset: Int = 8
    val baseline: Int = rect.y + rect.height / 2 + ui.fontSize / 3
    uiText(ui, rect.x + inset, baseline, text, theme.body, false)
    if (f.keyFocus == node && (uiTicks() / 500) % 2 == 0) {
        val textWidth: Int = uiTextWidth(ui, text, false)
        val caretX: Int = rect.x + inset + textWidth + 1
        uiLine(ui, caretX, rect.y + 5, caretX, rect.y + rect.height - 5, theme.accent)
    }
}

// A slider: a 4px track, the accent fill up to `value` (0..1000, which `uiEvent` keeps setting
// while a press is captured), and a thumb in the button's three fills - the frame's own
// hover/press state, like a button's face.
fun paintSlider(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val midY: Int = rect.y + rect.height / 2
    val value: Int = f.nodes[node].value
    uiRoundFill(ui, smRect(rect.x, midY - 2, rect.width, 4), 2, theme.buttonDisabled)
    val fillWidth: Int = rect.width * value / 1000
    if (fillWidth > 0) {
        uiRoundFill(ui, smRect(rect.x, midY - 2, fillWidth, 4), 2, theme.accent)
    }
    var thumb: Color = theme.button
    if (!f.nodes[node].enabled) {
        thumb = theme.buttonDisabled
    } else if (f.pressed == node) {
        thumb = theme.buttonPressed
    } else if (f.hover == node) {
        thumb = theme.buttonHover
    }
    uiCircle(ui, rect.x + fillWidth, midY, 7, thumb)
}

// A progress bar: the slider's look without the thumb and without any input - `value` is only
// ever written by the app (`setValue`), 0..1000.
fun paintProgress(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val midY: Int = rect.y + rect.height / 2
    val value: Int = f.nodes[node].value
    uiRoundFill(ui, smRect(rect.x, midY - 2, rect.width, 4), 2, theme.buttonDisabled)
    val fillWidth: Int = rect.width * value / 1000
    if (fillWidth > 0) {
        uiRoundFill(ui, smRect(rect.x, midY - 2, fillWidth, 4), 2, theme.accent)
    }
}

// A label: body text by default, and the palette picks title-bold, accent or dim. The baseline sits
// the same way `uiTextBox` puts it, so a left-aligned and a centred label share a line height.
fun paintLabel(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val palette: Int = f.nodes[node].palette
    var color: Color = theme.body
    var bold: Bool = false
    if (palette == Palette.Emphasized.toInt()) {
        color = theme.title
        bold = true
    } else if (palette == Palette.Accent.toInt()) {
        color = theme.accent
    } else if (palette == Palette.Dim.toInt()) {
        color = theme.dim
    }
    val text: Str = f.nodes[node].text
    val align: Int = f.nodes[node].align
    if (align == Align.Center.toInt()) {
        uiTextBox(ui, rect, text, color, bold)
        return
    }
    var points: Int = ui.fontSize
    if (bold) {
        points = ui.boldSize
    }
    val textWidth: Int = uiTextWidth(ui, text, bold)
    var x: Int = rect.x
    if (align == Align.Right.toInt()) {
        x = rect.x + rect.width - textWidth
    }
    val baseline: Int = rect.y + rect.height / 2 + points / 3
    uiText(ui, x, baseline, text, color, bold)
}

// A button: three fills from the theme, picked by the frame's own state (hover and press), and its
// text centred. `palette` is not read - a button's look is its state - and a disabled button is
// hit but never hovered or pressed, because `uiEvent` steps over it.
fun paintButton(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int): Unit {
    val rect: Rect = f.nodes[node].rect
    val enabled: Bool = f.nodes[node].enabled
    var face: Color = theme.button
    if (!enabled) {
        face = theme.buttonDisabled
    } else if (f.pressed == node) {
        face = theme.buttonPressed
    } else if (f.hover == node) {
        face = theme.buttonHover
    }
    uiRoundFill(ui, rect, 6, face)
    uiTextBox(ui, rect, f.nodes[node].text, theme.body, false)
}
