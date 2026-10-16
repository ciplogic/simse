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
//     <panel  rect="12,16,120,40" palette="plain"/>
//     <label  rect="..." palette="emphasized|accent|dim" align="left|center|right">TEXT</label>
//     <button name="ok" rect="...">OK</button>       a name is optional; a listener needs one
//     <sprite rect="..." index="3"/>                 one atlas cell (uiLoadSheet)
//     <custom name="board" rect="..." payload="0"/>  the app paints it (paintFrame's custom hook)
//     <grid   name="board" rect="8,8,288,288" cell="32"/>   expanded to one cell per square
//     <text is the element's content, or a text="..." attribute; one or the other, not both>
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

// What each element is allowed to write. The empty answer is "no such element".
fun xmlAllowedAttrs(element: Str): Str {
    if (element == "panel") {
        return "name rect palette visible enabled"
    }
    if (element == "label") {
        return "name rect palette align text visible enabled"
    }
    if (element == "button") {
        return "name rect palette text visible enabled"
    }
    if (element == "sprite") {
        return "name rect index visible enabled"
    }
    if (element == "custom") {
        return "name rect payload visible enabled"
    }
    if (element == "grid") {
        return "name rect cell visible enabled"
    }
    return ""
}

// One element and its children. `Returns` the arena index it built, so the parent can recurse.
fun xmlMapElement(f: *Frame, parent: Int, node: XmlNode): Res<Int> {
    val element: Str = xmlElementName(*node)
    val line: Int = xmlLine(*node)
    val allowed: Str = xmlAllowedAttrs(element)
    if (allowed.size() == 0) {
        return xmlFail<Int>(line, "no element <" + element + ">")
    }
    val checked: Res<Int> = xmlCheckAttrs(*node, allowed)
    if (!checked.isOk()) {
        return Res<Int>.err(checked.error())
    }
    val rectResult: Res<Rect> = xmlRectAttr(*node, "rect")
    if (!rectResult.isOk()) {
        return Res<Int>.err(rectResult.error())
    }
    val rect: Rect = rectResult.value()

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
        if (rect.width % cell != 0 || rect.height % cell != 0) {
            return xmlFail<Int>(
                line,
                "<grid> " + rect.width.toString() + "x" + rect.height.toString()
                        + " does not divide into cells of " + cell.toString()
            )
        }
        val name: Str = xmlAttrOf(*node, "name")
        if (name.size() == 0) {
            return xmlFail<Int>(line, "<grid> needs a name - its listener binds to it")
        }
        index = addGrid(
            f, parent, name, rect.x, rect.y, cell,
            rect.width / cell, rect.height / cell
        )
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
        index = addNode(f, parent, kind, rect.x, rect.y, rect.width, rect.height)

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
        if (element == "custom") {
            val payload: Res<Int> = xmlIntAttr(*node, "payload", 0)
            if (!payload.isOk()) {
                return Res<Int>.err(payload.error())
            }
            setPayload(f, index, payload.value())
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

// A whole screen: one `<screen width height>` element with everything else inside it.
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
    val checked: Res<Int> = xmlCheckAttrs(*doc, "name width height")
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
    var index: Int = 0
    val children: Array<XmlNode> = doc.Children
    while (index < children.count()) {
        val mapped: Res<Int> = xmlMapElement(*f, f.root, children[index])
        if (!mapped.isOk()) {
            return Res<Frame>.err(mapped.error())
        }
        index = index + 1
    }
    return Res<Frame>.ok(f)
}
