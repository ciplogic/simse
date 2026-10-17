package minui

import xml

// The screen loader: XML text in, a `Frame` out - the arena the painter and the hit test walk.
//
// The direction is the whole point (minui.md §7): the runtime model is the arena, and XML is a
// front end that builds it *once*, when a screen is loaded. Nothing walks XML per frame, nothing
// compares strings to hit-test, and behaviour is not in the document at all: the XML carries
// geometry, kinds and **names**, and the app binds behaviour to those names with `frameOn`. A name
// no one bound is inert; a binding to a name that is not in the tree is reported at setup.
//
// The vocabulary, and nothing beyond it:
//
//   <screen name="menu" width="360" height="348">   the root; the name renames the frame's root
//     <screen layout="column" spacing="6" padding="10">   ... or a box: its children are placed
//     <panel  rect="12,16,120,40" palette="plain"/>       by the box, not by their own rects
//     <vbox   rect="..." spacing="6">   a panel whose children stack top to bottom
//     <hbox   rect="..." spacing="6">   a panel whose children line up left to right
//     <label  rect="..." palette="emphasized|accent|dim" align="left|center|right">TEXT</label>
//     <button name="ok" rect="...">OK</button>       a name is optional; a listener needs one
//     <sprite rect="..." index="3"/>                 one atlas cell (uiLoadSheet)
//     <custom name="board" rect="..." payload="0"/>  the app paints it (paintFrame's custom hook)
//     <grid   name="board" rect="8,8,288,288" cell="32"/>   expanded to one cell per square
//     <check  name="opt" rect="..." checked="true">TEXT</check>   a checkbox; the tree toggles it
//     <edit   name="field" rect="..." payload="40">text</edit>    a one-line field; payload is
//                                                    the max length (the tree edits `text`)
//     <slider name="vol" rect="..." value="500"/>    the tree tracks the drag into `value`
//     <progress name="bar" rect="..." value="250"/>  display only: the app writes `value`
//     <text is the element's content, or a text="..." attribute; one or the other, not both>
//
// Geometry is where a screen says which model it is in: outside a layout box an element carries a
// `rect`; inside one it carries `width`/`height` instead, where 0 means "the box decides" (stretch
// on the cross axis, a share of the remainder on the main one). A `grid` keeps its `rect` either
// way - its cell count *is* its size - and a box positions it without resizing it.
//
// Every element takes `visible="false"` and `enabled="false"`; every element takes a `name`.
// Anything else - an unknown element, an unknown attribute, a missing rect, a duplicate name, a
// grid whose size does not divide into its cells - is an error with the line it is on, never a
// silently ignored document. A screen that loads is a screen that says what it means.

// The attribute's value, or "" when it is not there. The first match wins.
fun xmlAttrOf(node: *XmlNode, name: Str): Str {
    val list: List<Attribute> = node.attributes
    var i: Int = 0
    while (i < list.size()) {
        val attribute: Attribute = list[i]
        if (attribute.name == name) {
            return attribute.value
        }
        i = i + 1
    }
    return ""
}

// Every element the reader parsed carries the line it started on (its own `line` attribute).
fun xmlLine(node: *XmlNode): Int {
    val text: Str = xmlAttrOf(node, "line")
    val parsed: Opt<Int> = text.toInt()
    if (!parsed.hasValue()) {
        return 0
    }
    return parsed.value()
}

fun xmlElementName(node: *XmlNode): Str {
    return node.name
}

// Reject an attribute the element has no use for: a typo (`widht`) should be a message, not a
// node that quietly keeps the default. `line` is the reader's own and always allowed.
fun xmlCheckAttrs(node: *XmlNode, allowed: Str): Res<Int> {
    val list: List<Attribute> = node.attributes
    val pattern: Str = " " + allowed + " "
    var i: Int = 0
    while (i < list.size()) {
        val attribute: Attribute = list[i]
        if (attribute.name != "line" && pattern.find(" " + attribute.name + " ") < 0) {
            return xmlFail<Int>(
                xmlLine(node),
                "<" + xmlElementName(node) + "> has no attribute " + attribute.name
            )
        }
        i = i + 1
    }
    return Res<Int>.ok(0)
}

// `<name offset="..." />`: "" (absent) or the value; the caller has checked the name is allowed.
fun xmlAttrOr(node: *XmlNode, name: Str, fallback: Str): Str {
    val value: Str = xmlAttrOf(node, name)
    if (value.size() == 0) {
        return fallback
    }
    return value
}

fun xmlIntAttr(node: *XmlNode, name: Str, fallback: Int): Res<Int> {
    val value: Str = xmlAttrOf(node, name)
    if (value.size() == 0) {
        return Res<Int>.ok(fallback)
    }
    val parsed: Opt<Int> = value.toInt()
    if (!parsed.hasValue()) {
        return xmlFail<Int>(xmlLine(node), name + " wants a number, got \"" + value + "\"")
    }
    return Res<Int>.ok(parsed.value())
}

fun xmlBoolAttr(node: *XmlNode, name: Str, fallback: Bool): Res<Bool> {
    val value: Str = xmlAttrOf(node, name)
    if (value.size() == 0) {
        return Res<Bool>.ok(fallback)
    }
    if (value == "true") {
        return Res<Bool>.ok(true)
    }
    if (value == "false") {
        return Res<Bool>.ok(false)
    }
    return xmlFail<Bool>(xmlLine(node), name + " wants true or false, got \"" + value + "\"")
}

// `rect="x,y,w,h"`, four integers, required by every element except the screen.
fun xmlRectAttr(node: *XmlNode, name: Str): Res<Rect> {
    val line: Int = xmlLine(node)
    val tag: Str = xmlElementName(node)
    val value: Str = xmlAttrOf(node, name)
    if (value.size() == 0) {
        return xmlFail<Rect>(line, "<" + tag + "> needs a " + name + "=\"x,y,w,h\"")
    }
    val parts: List<Str> = value.split(",")
    if (parts.size() != 4) {
        return xmlFail<Rect>(line, name + " wants four numbers, got \"" + value + "\"")
    }
    var numbers: List<Int> = List<Int>(0)
    var i: Int = 0
    while (i < 4) {
        val parsed: Opt<Int> = parts[i].toInt()
        if (!parsed.hasValue()) {
            return xmlFail<Rect>(
                line, name + " wants a number, got \"" + parts[i].trim() + "\""
            )
        }
        numbers.append(parsed.value())
        i = i + 1
    }
    if (numbers[2] <= 0 || numbers[3] <= 0) {
        return xmlFail<Rect>(line, name + " needs a positive width and height")
    }
    return Res<Rect>.ok(Rect(numbers[0], numbers[1], numbers[2], numbers[3]))
}

// The palette names map to the painter's enum (tree.kt); an unknown name is an error.
fun xmlPaletteAttr(node: *XmlNode): Res<Palette> {
    val name: Str = xmlAttrOf(node, "palette")
    if (name.size() == 0 || name == "plain") {
        return Res<Palette>.ok(Palette.Plain)
    }
    if (name == "emphasized") {
        return Res<Palette>.ok(Palette.Emphasized)
    }
    if (name == "accent") {
        return Res<Palette>.ok(Palette.Accent)
    }
    if (name == "dim") {
        return Res<Palette>.ok(Palette.Dim)
    }
    return xmlFail<Palette>(xmlLine(node), "no palette \"" + name + "\"")
}

fun xmlAlignAttr(node: *XmlNode): Res<Align> {
    val name: Str = xmlAttrOf(node, "align")
    if (name.size() == 0 || name == "left") {
        return Res<Align>.ok(Align.Left)
    }
    if (name == "center") {
        return Res<Align>.ok(Align.Center)
    }
    if (name == "right") {
        return Res<Align>.ok(Align.Right)
    }
    return xmlFail<Align>(xmlLine(node), "no align \"" + name + "\"")
}

// `layout="column|row"` on a `<screen>` or a `<panel>`: the box a node's children are arranged
// along (`layoutFrame`, tree.kt). `<vbox>`/`<hbox>` carry the attribute's meaning in their name,
// so they do not take it.
fun xmlLayoutAttr(node: *XmlNode): Res<BoxLayout> {
    val name: Str = xmlAttrOf(node, "layout")
    if (name.size() == 0 || name == "none") {
        return Res<BoxLayout>.ok(BoxLayout.None)
    }
    if (name == "column") {
        return Res<BoxLayout>.ok(BoxLayout.Column)
    }
    if (name == "row") {
        return Res<BoxLayout>.ok(BoxLayout.Row)
    }
    return xmlFail<BoxLayout>(xmlLine(node), "no layout \"" + name + "\"")
}

// `spacing`/`padding` on a box, and `width`/`height` on a box's child: zero or more. A negative
// spacing would overlap the neighbours and a negative size is not a size; and zero on a child is
// meaningful - "the box decides" (stretch on the cross axis, a share of the remainder on the
// main one).
fun xmlNonNegative(node: *XmlNode, name: Str): Res<Int> {
    val parsed: Res<Int> = xmlIntAttr(node, name, 0)
    if (!parsed.isOk()) {
        return parsed
    }
    if (parsed.value() < 0) {
        return xmlFail<Int>(xmlLine(node), name + " wants zero or more, got \"" + parsed.value().toString() + "\"")
    }
    return parsed
}

// What each element is allowed to write. The empty answer is "no such element". The geometry an
// element takes depends on where it sits: `rect` under a parent that is not a box, `width` and
// `height` inside one (a box places its children; their own x/y would be overwritten anyway).
// The one exception is a grid, whose cell count *is* its rect - it keeps its rect everywhere and
// a box positions it without resizing it.
fun xmlAllowedAttrs(element: Str, boxed: Bool): Str {
    var attrs: Str = ""
    if (element == "panel") {
        attrs = "name palette visible enabled layout spacing padding"
    } else if (element == "vbox" || element == "hbox") {
        attrs = "name palette visible enabled spacing padding"
    } else if (element == "label") {
        attrs = "name palette align text visible enabled"
    } else if (element == "button") {
        attrs = "name palette text visible enabled"
    } else if (element == "sprite") {
        attrs = "name index visible enabled"
    } else if (element == "custom") {
        attrs = "name payload visible enabled"
    } else if (element == "grid") {
        attrs = "name cell visible enabled"
    } else if (element == "check") {
        attrs = "name checked text visible enabled"
    } else if (element == "edit") {
        attrs = "name payload text visible enabled"
    } else if (element == "slider") {
        attrs = "name value visible enabled"
    } else if (element == "progress") {
        attrs = "name value visible enabled"
    } else {
        return ""
    }
    if (element == "grid" || !boxed) {
        return attrs + " rect"
    }
    return attrs + " width height"
}

// One element and its children. `Returns` the arena index it built, so the parent can recurse.
// A child of a layout box is placed by the box (`layoutFrame`): only its width and height are
// read, so those are the attributes it takes - a `rect` there is the one geometry mistake the
// loader can name rather than just refuse.
fun xmlMapElement(f: *Frame, parent: Int, node: XmlNode): Res<Int> {
    val element: Str = xmlElementName(*node)
    val line: Int = xmlLine(*node)
    val boxed: Bool = f.nodes[parent].layout != BoxLayout.None.toInt()
    val allowed: Str = xmlAllowedAttrs(element, boxed)
    if (allowed.size() == 0) {
        return xmlFail<Int>(line, "no element <" + element + ">")
    }
    if (element != "grid") {
        if (boxed && xmlAttrOf(*node, "rect").size() > 0) {
            return xmlFail<Int>(
                line, "<" + element + "> is inside a layout box: width/height place it, not rect"
            )
        }
        if (!boxed && xmlAttrOf(*node, "width").size() > 0) {
            return xmlFail<Int>(
                line, "<" + element + "> takes a rect (its parent is not a layout box)"
            )
        }
    }
    val checked: Res<Int> = xmlCheckAttrs(*node, allowed)
    if (!checked.isOk()) {
        return Res<Int>.err(checked.error())
    }

    // The geometry: a rect of its own outside a box, a requested size inside one.
    var x: Int = 0
    var y: Int = 0
    var width: Int = 0
    var height: Int = 0
    if (element == "grid" || !boxed) {
        val rectResult: Res<Rect> = xmlRectAttr(*node, "rect")
        if (!rectResult.isOk()) {
            return Res<Int>.err(rectResult.error())
        }
        val rect: Rect = rectResult.value()
        x = rect.x
        y = rect.y
        width = rect.width
        height = rect.height
    } else {
        val widthResult: Res<Int> = xmlNonNegative(*node, "width")
        if (!widthResult.isOk()) {
            return Res<Int>.err(widthResult.error())
        }
        val heightResult: Res<Int> = xmlNonNegative(*node, "height")
        if (!heightResult.isOk()) {
            return Res<Int>.err(heightResult.error())
        }
        width = widthResult.value()
        height = heightResult.value()
    }

    var index: Int = 0 - 1
    if (element == "grid") {
        // A grid is one named parent plus rows*cols anonymous children (tree.kt `addGrid`): the
        // cell size comes from `cell`, the count from how many of them fit in the rect.
        val cellResult: Res<Int> = xmlIntAttr(*node, "cell", 0 - 1)
        if (!cellResult.isOk()) {
            return Res<Int>.err(cellResult.error())
        }
        val cell: Int = cellResult.value()
        if (cell <= 0) {
            return xmlFail<Int>(line, "<grid> needs a positive cell")
        }
        if (width % cell != 0 || height % cell != 0) {
            return xmlFail<Int>(
                line,
                "<grid> " + width.toString() + "x" + height.toString()
                        + " does not divide into cells of " + cell.toString()
            )
        }
        val name: Str = xmlAttrOf(*node, "name")
        if (name.size() == 0) {
            return xmlFail<Int>(line, "<grid> needs a name - its listener binds to it")
        }
        index = addGrid(f, parent, name, x, y, cell, width / cell, height / cell)
    } else {
        var kind: NodeKind = NodeKind.Panel
        if (element == "label") {
            kind = NodeKind.Label
        }
        if (element == "button") {
            kind = NodeKind.Button
        }
        if (element == "sprite") {
            kind = NodeKind.Sprite
        }
        if (element == "custom") {
            kind = NodeKind.Custom
        }
        if (element == "check") {
            kind = NodeKind.Checkbox
        }
        if (element == "edit") {
            kind = NodeKind.Edit
        }
        if (element == "slider") {
            kind = NodeKind.Slider
        }
        if (element == "progress") {
            kind = NodeKind.Progress
        }
        index = addNode(f, parent, kind, x, y, width, height)
        // A box element is a panel that arranges its children (the element names the layout, so
        // `<vbox>`/`<hbox>` take no `layout` of their own).
        if (element == "vbox") {
            setLayout(f, index, BoxLayout.Column)
        }
        if (element == "hbox") {
            setLayout(f, index, BoxLayout.Row)
        }

        val name: Str = xmlAttrOf(*node, "name")
        if (name.size() > 0) {
            if (!setName(f, index, name)) {
                return xmlFail<Int>(line, "the name \"" + name + "\" is already used")
            }
        }
        if (element == "custom" && name.size() == 0) {
            return xmlFail<Int>(line, "<custom> needs a name - the app paints it by name")
        }
        val palette: Res<Palette> = xmlPaletteAttr(*node)
        if (!palette.isOk()) {
            return Res<Int>.err(palette.error())
        }
        setPalette(f, index, palette.value())
        val align: Res<Align> = xmlAlignAttr(*node)
        if (!align.isOk()) {
            return Res<Int>.err(align.error())
        }
        setAlign(f, index, align.value())
        val visible: Res<Bool> = xmlBoolAttr(*node, "visible", true)
        if (!visible.isOk()) {
            return Res<Int>.err(visible.error())
        }
        setVisible(f, index, visible.value())
        val enabled: Res<Bool> = xmlBoolAttr(*node, "enabled", true)
        if (!enabled.isOk()) {
            return Res<Int>.err(enabled.error())
        }
        setEnabled(f, index, enabled.value())
        val text: Str = xmlAttrOf(*node, "text")
        if (text.size() > 0) {
            setText(f, index, text)
        }
        if ((element == "label" || element == "button") && text.size() == 0) {
            return xmlFail<Int>(line, "<" + element + "> needs content or a text=\"...\"")
        }
        if (element == "sprite") {
            val sprite: Res<Int> = xmlIntAttr(*node, "index", 0 - 1)
            if (!sprite.isOk()) {
                return Res<Int>.err(sprite.error())
            }
            if (sprite.value() < 0) {
                return xmlFail<Int>(line, "<sprite> needs an index")
            }
            setSprite(f, index, sprite.value())
        }
        if (element == "custom" || element == "edit") {
            // A `custom`'s payload is the app's own index; an `edit`'s is its max length (the
            // one payload the framework reads - tree.kt's `uiKey`).
            val payload: Res<Int> = xmlIntAttr(*node, "payload", 0)
            if (!payload.isOk()) {
                return Res<Int>.err(payload.error())
            }
            setPayload(f, index, payload.value())
        }
        if (element == "check") {
            val isChecked: Res<Bool> = xmlBoolAttr(*node, "checked", false)
            if (!isChecked.isOk()) {
                return Res<Int>.err(isChecked.error())
            }
            if (isChecked.value()) {
                setValue(f, index, 1)
            }
        }
        if (element == "slider" || element == "progress") {
            val value: Res<Int> = xmlIntAttr(*node, "value", 0)
            if (!value.isOk()) {
                return Res<Int>.err(value.error())
            }
            if (value.value() < 0 || value.value() > 1000) {
                return xmlFail<Int>(line, "value wants 0..1000, got \"" + value.value().toString() + "\"")
            }
            setValue(f, index, value.value())
        }
        // The box metrics, read before the children so that they can see the box they are in.
        if (element == "panel" || element == "vbox" || element == "hbox") {
            if (element == "panel") {
                val layout: Res<BoxLayout> = xmlLayoutAttr(*node)
                if (!layout.isOk()) {
                    return Res<Int>.err(layout.error())
                }
                setLayout(f, index, layout.value())
            }
            val spacing: Res<Int> = xmlNonNegative(*node, "spacing")
            if (!spacing.isOk()) {
                return Res<Int>.err(spacing.error())
            }
            setSpacing(f, index, spacing.value())
            val padding: Res<Int> = xmlNonNegative(*node, "padding")
            if (!padding.isOk()) {
                return Res<Int>.err(padding.error())
            }
            setPadding(f, index, padding.value())
        }
    }

    val children: Array<XmlNode> = node.Children
    // `count()`, not `size()`: an `Array<T>` is a counted block and its length operation is
    // `count` (specs/built-in-types.md); a `List` is what answers `size()`.
    var i: Int = 0
    while (i < children.count()) {
        val mapped: Res<Int> = xmlMapElement(f, index, children[i])
        if (!mapped.isOk()) {
            return mapped
        }
        i = i + 1
    }
    return Res<Int>.ok(index)
}

// A whole screen: one `<screen width height>` element with everything else inside it. The screen
// itself may be a layout box (`layout="column"`, the usual toolbar-over-content shape); whatever
// boxes the screen names, their arrangement is resolved once, here, before the frame is answered -
// the painter and the hit test only ever see placed rects.
fun loadScreen(text: Str): Res<Frame> {
    val parsed: Res<XmlNode> = parseXmlText(text)
    if (!parsed.isOk()) {
        return Res<Frame>.err(parsed.error())
    }
    val doc: XmlNode = parsed.value()
    val line: Int = xmlLine(*doc)
    val tag: Str = xmlElementName(*doc)
    if (tag != "screen") {
        return xmlFail<Frame>(line, "the root element must be <screen>, not <" + tag + ">")
    }
    val checked: Res<Int> = xmlCheckAttrs(*doc, "name width height layout spacing padding")
    if (!checked.isOk()) {
        return Res<Frame>.err(checked.error())
    }
    val widthResult: Res<Int> = xmlIntAttr(*doc, "width", 0 - 1)
    if (!widthResult.isOk()) {
        return Res<Frame>.err(widthResult.error())
    }
    val heightResult: Res<Int> = xmlIntAttr(*doc, "height", 0 - 1)
    if (!heightResult.isOk()) {
        return Res<Frame>.err(heightResult.error())
    }
    if (widthResult.value() <= 0 || heightResult.value() <= 0) {
        return xmlFail<Frame>(line, "<screen> needs a positive width and height")
    }
    var f: Frame = newFrame(smSize(widthResult.value(), heightResult.value()))
    val name: Str = xmlAttrOf(*doc, "name")
    if (name.size() > 0) {
        if (!nameRoot(*f, name)) {
            return xmlFail<Frame>(line, "the screen name \"" + name + "\" is already used")
        }
    }
    val layout: Res<BoxLayout> = xmlLayoutAttr(*doc)
    if (!layout.isOk()) {
        return Res<Frame>.err(layout.error())
    }
    setLayout(*f, f.root, layout.value())
    val spacing: Res<Int> = xmlNonNegative(*doc, "spacing")
    if (!spacing.isOk()) {
        return Res<Frame>.err(spacing.error())
    }
    setSpacing(*f, f.root, spacing.value())
    val padding: Res<Int> = xmlNonNegative(*doc, "padding")
    if (!padding.isOk()) {
        return Res<Frame>.err(padding.error())
    }
    setPadding(*f, f.root, padding.value())
    var index: Int = 0
    val children: Array<XmlNode> = doc.Children
    while (index < children.count()) {
        val mapped: Res<Int> = xmlMapElement(*f, f.root, children[index])
        if (!mapped.isOk()) {
            return Res<Frame>.err(mapped.error())
        }
        index = index + 1
    }
    layoutFrame(*f)
    return Res<Frame>.ok(f)
}
