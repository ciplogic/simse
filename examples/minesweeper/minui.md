# minui — a minimal retained-mode UI for Simse

The design of the drawing/input layer the Minesweeper example is built on, and the shape a
Heroes-style UI (screen stack, clickable controls, custom painting, XML-described layout) grows
into. It is written to be **iterated in shots**: every section says what is in the first shot and
what is deliberately left out.

Nothing here is speculative about the platform: the drawing, text, image and present paths were
probed on this machine (Windows arm64, `libSkiaSharp.dll` 2.88.9, `SDL2.dll` 2.33.0) with real
output — see "Verified facts" at the end.

## 1. The stack it stands on

```
window, events, present        SDL2            (portable; decoded mouse/key/window events)
every pixel                    Skia            (AA shapes, text from system fonts, PNG, arcs)
                the bridge     one streaming texture: Skia rasterizes into bytes,
                               SDL_UpdateTexture + RenderCopy + RenderPresent
```

The buffer *is* the API between the two: Skia writes bytes, SDL uploads them. Measured at
560×300: Skia draw 0.095 ms, upload 0.041 ms, clear+copy+present 0.054 ms — **0.19 ms/frame**,
against a 16.7 ms budget. A full frame through the present path alone (upload + clear + copy +
present, no Skia) is 0.16 / 0.30 / 0.37 ms at the game's three window sizes (§11). The same canvas
can later target a GPU surface (Skia's backend-render-target entry points over an SDL/GL context)
without changing anything above it.

Two traps pinned by the probes, both load-bearing for the wrapper:

- The texture must be `SDL_PIXELFORMAT_ARGB8888` — SDL names a format by its *packed value*, so
  the bytes are B,G,R,A in memory, which is what Skia's `BGRA_8888` writes and a Win32 DIB holds.
  The similarly-named `...BGRA8888` swaps every channel.
- `SDL_MAIN_HANDLED` must be defined: this project links nothing (no `SDL2main.lib`).

And one that decided which renderer `uiOpen` asks for: the accelerated (direct3d) renderer on this
machine trades red and blue for *some* window sizes — a full account with the probes is in §11.

## 2. What the language decides for us

Four properties of Simse fix the design; each of them is a decision, not an accident:

| Property | Consequence for minui |
| --- | --- |
| **No virtual dispatch** (extension functions are static; generics reified) | The tree is a **tagged kind** visited by `when`. A heterogeneous stack is a `union class` or an enum-tagged struct. "Subclassing a button" does not exist: a node carries a **name** and code registers a **listener** for it (§4) — XAML's code-behind, with the names resolved to arena indices at bind time. Extending a *built-in* kind edits the framework's `when`; extending a *screen* is a per-screen paint callback. |
| **Values copy; a recursive value type cannot exist** | The tree is an **arena**: `List<Node>` plus indices. Never `List<Node>` inside `Node`. Nodes are reached by `*Node`, and a frame is passed by pointer. |
| **No exceptions; errors are `Opt`/`Res`** | The XML loader answers `Res<Frame>`; a bad screen is a load error with a line number, not a crash. |
| **Strings are byte strings, `Str` owns storage** | Node text is `Str` but nodes are never copied casually: the arena is the only home, and painting reads through pointers. |

## 3. The arena

```kt
// Kinds the *framework* knows how to paint; `Custom` is painted by the screen.
enum class NodeKind { Root = 0, Panel = 1, Label = 2, Button = 3, Sprite = 4, Custom = 5 }

data class Rect(var x: Int, var y: Int, var width: Int, var height: Int)

// One node. POD-ish on purpose: the painter walks these by pointer and a copy must stay cheap.
// `name` is the author's handle (`x:Name`, HTML's `id`) - how code finds the node and how a
// listener is attached; `""` when anonymous. `listener` is the *resolved* registration (-1 =
// inert), so the click path never compares strings. `payload` is the app's own index into what the
// node shows (a cell number, a difficulty, a slot) - the framework never reads it. (`data` is a
// keyword in Simse - `data class` - so the field is `payload`, `setPayload` its setter.)
// `palette` is the paint variant (which of a kind's looks, `paint.kt`); `align` places a label's
// text; `sprite` is an atlas cell of the loaded sheet.
data class Node(
    var kind: NodeKind,
    var name: Str,            // "" when anonymous
    var rect: Rect,
    var parent: Int,          // arena index, -1 for the frame's root
    var children: List<Int>,  // arena indices, in paint order (later = on top)
    var listener: Int,        // index into Frame.listeners, -1 = none
    var payload: Int,         // the app's payload
    var text: Str,            // labels and counters
    var palette: Int,         // which of the kind's looks
    var align: Int,           // Align: Left / Center / Right
    var sprite: Int,          // atlas cell of the loaded sheet
    var visible: Bool,
    var enabled: Bool
)

enum class Align { Left = 0, Center = 1, Right = 2 }

// What a name resolved to, once: the node, the action code the screen wants called for it, and
// the payload the registration carried (a difficulty, a column, 0).
data class Listener(var node: Int, var action: Int, var payload: Int)

// What the walk hands the screen: which listener fired, the node it sits on, the leaf-most node
// that was actually hit - whose `payload` says *which* cell when a grid shares one listener - and
// the registration's own payload.
data class Hit(var action: Int, var node: Int, var hit: Int, var payload: Int)

// A screen's whole UI: the arena, the registrations, and the interaction state the framework
// owns. The app's own state is *not* here - it lives in the app's screen struct, which is why one
// screen can be a board and another a menu without minui knowing either.
data class Frame(
    var nodes: List<Node>,
    var listeners: List<Listener>,
    var names: Dictionary<Str, Int>,   // name -> arena index, built once; a duplicate is an error
    var root: Int,
    var hover: Int,     // node under the cursor, -1 when none
    var pressed: Int,   // node that took the press and has not been released, -1 when none
    var keyFocus: Int   // node that takes keys, -1 when none
)
```

Why an arena rather than a tree of values: a parent owns its children as *indices*, so the whole
tree is one contiguous list, a click is integer arithmetic, and a node's identity is stable for
as long as the frame lives. It is also what makes the XML loader cheap: it appends nodes in
document order and fixes up indices.

The alternative (first-child/next-sibling links instead of a `children` list) is noted and
rejected for v1: `List<Int>` with an inline capacity of 4 costs no allocation for the 2–5
children a node usually has, and reverse iteration for z-order is trivial.

The builder API that is landed (`tree.kt`), geometry absolute and no layout:

```kt
fun newFrame(width: Int, height: Int): Frame            // one Root node, already named "root"
fun addNode(f: *Frame, parent: Int, kind: NodeKind, x: Int, y: Int, width: Int, height: Int): Int
fun addGrid(f: *Frame, parent: Int, name: Str, x: Int, y: Int, cell: Int, cols: Int, rows: Int): Int
fun setName(f: *Frame, node: Int, name: Str): Bool      // false on a duplicate or an empty name
fun setText / setPayload / setPalette / setSprite / setVisible / setEnabled
fun nodeNamed(f: *Frame, name: Str): Int                // -1 when no node has that name
fun setTextNamed(f: *Frame, name: Str, text: Str): Bool // the live readouts: counter, clock, status
fun frameOn(f: *Frame, name: Str, action: Int, payload: Int): Bool   // the listener binding
```

## 4. Interaction: names, listeners, bubbling

This is the part that has to be right, because Simse cannot hand "the button that was clicked" a
callback to call: there is no vtable to dispatch on. The model is therefore the XAML/HTML one,
with the strings resolved away:

- **a node may carry a name** - the author's handle (`x:Name`, HTML's `id`): `"beginner"`,
  `"board"`, `"restart"`.
- **the screen registers listeners by name, once**, when it builds the frame:
  `frameOn(f, "beginner", Act.DIFFICULTY, 0)` (this is XAML's code-behind, without reflection).
  The registration resolves the name to the arena index there and then, so the click path
  compares **indices**, never strings.
- **a click walks leaf → root**: the leaf-most node under the cursor goes first, then its parent,
  then its parent's parent, up to the root. The first listener that *consumes* the event ends the
  walk (`true`); when none does, the event is unclaimed and the screen may read it as a click on
  the background.
- **hover and press are the framework's**, kept by arena index: `f.hover`, `f.pressed`.

Why names *and* indices: the name is what the author writes and what code and XML agree on; the
index is what hover, press and capture compare, thousands of times per frame. A name no listener
registered is harmless (the click bubbles past it); a listener on a name that is not in the tree
is a real error and `frameOn` reports it at setup, where it is cheap to see.

```kt
// The whole input path, one event at a time (`uiEvent` in tree.kt, which is landed; the layer
// entry `uiDispatch` above it comes with the window):
//
//  1. hit-test: the top-most node whose rect contains the point (children before parents, later
//     siblings above earlier ones - the painter's order, reversed),
//  2. bubble: from that leaf up to the root; the first listener to consume wins,
//  3. capture: a press goes to the node a click would reach, and that node's listener gets the
//     release even when the cursor has left it - without this, dragging off a control misfires.
//     A move is delivered only while a press is captured (so a control can track a drag);
//     otherwise a move only updates `hover` and the app reads that.
//
// `visible = false` is not hit at all; `enabled = false` *is* hit and still bubbles, but its own
// listener is skipped, so a control can paint itself greyed out while an ancestor still sees the
// click. Key events do not hit-test: they go to the top layer (and to `keyFocus` once keyboard
// navigation exists).
fun uiHitTest(f: *Frame, x: Int, y: Int): Int                        // arena index, -1 when nothing
fun uiEvent(f: *Frame, e: *UiEvent, answer: (Hit, *UiEvent) -> Bool): Hit   // the tree's whole path
```

The framework stops at the listener: the *screen* reads the action code and answers. That split is
the whole point of the model - minui never needs to know what a button does. The walk hands the
*event* along with the hit, because which button was pressed - and whether this is the down or the
up - is the app's business and not something the hit can carry.

The screen answers it:

```kt
// One function per screen, and a `when` over the action code - the analogue of a message map.
// `h.hit` is the leaf-most node, so a grid can share one listener: the leaf says *which* cell
// (`f.nodes[h.hit].payload`), `h.node` says which grid, `h.payload` is what the registration
// carried.
fun gameAction(g: *GameScreen, ui: *Ui, h: Hit, e: *UiEvent): Bool {
    if (h.action == Act.CELL.toInt()) {
        val index: Int = g.frame.nodes[h.hit].payload
        val x: Int = index % g.board.width
        val y: Int = index / g.board.width
        if (e.button == Button.Right) {
            toggleFlag(*g.board, x, y)
        } else if (e.buttons == MaskLeft + MaskRight) {
            chord(*g.board, x, y)          // both held: the chord gesture
        } else {
            reveal(*g.board, x, y)
        }
        return true
    }
    if (h.action == Act.RESTART.toInt()) {
        g.board = newGame(g.difficulty, g.seed)
        return true
    }
    return false
}
```

Hover and press are **framework state**, not app state, so every control gets the three states
for free while painting: `f.hover == node` (the cursor is on it), `f.pressed == node` (it is
held). A control that wants a richer state machine keeps its own (`payload` is its index).

Where listeners come from: the app registers them once, by name, when it builds the frame (the
next section's `frameOn`). XML carries *names*; behaviour lives in code.

```kt
// The action codes a screen answers, and the registration call that binds a name to one. Codes
// are an enum (Simse has no hex literals, and a name beats a magic number); the *name* is what
// the XML and this call share, and `frameOn` resolves it once - a name that is not in the tree is
// reported here, at setup, not silently ignored at click time.
enum class Act { CELL = 1, RESTART = 2, DIFFICULTY = 3, BACK = 4, AGAIN = 5 }

fun frameOn(f: *Frame, name: Str, action: Int, payload: Int): Bool

// Minesweeper's binding functions, one per screen:
fun menuBind(f: *Frame): Unit {
    frameOn(f, "beginner", Act.DIFFICULTY.toInt(), Difficulty.Beginner.toInt())
    frameOn(f, "expert", Act.DIFFICULTY.toInt(), Difficulty.Expert.toInt())
}
fun gameBind(f: *Frame): Unit {
    frameOn(f, "board", Act.CELL.toInt(), 0)      // one listener for all 81..480 cells
    frameOn(f, "restart", Act.RESTART.toInt(), 0)
}
```

## 5. Screens: the stack - LANDED (`screens.kt`)

Heroes-shaped navigation: menu → game → city/battle, each screen pushed, `Escape`/a Back button
popping. The stack is a `List<Layer>` - each layer holds its `&Frame` **boxed**, so a push shares
the frame rather than copying an arena of 480 nodes - and the layer's *name* is what the app
switches on where an interface would dispatch on a type (there is no vtable):

```kt
data class Layer(var frame: &Frame, var name: Str, var opaque: Bool)
data class ScreenStack(var layers: List<Layer>)

fun newStack(first: &Frame, name: Str, opaque: Bool): ScreenStack   // the menu is always there
fun stackPush(stack: *ScreenStack, frame: &Frame, name: Str, opaque: Bool): Unit
fun stackPop(stack: *ScreenStack): Bool                // false at the bottom layer: it stays
fun stackSize(stack: *ScreenStack): Int
fun stackTopName(stack: *ScreenStack): Str             // "menu", "game", ...
fun stackTop(stack: *ScreenStack): *Frame              // valid while the box is in the stack
fun stackPaintBase(stack: *ScreenStack): Int           // the last opaque layer
fun stackPaint(stack: *ScreenStack, theme: *UiTheme, ui: *Ui, custom: (Str, Int) -> Unit): Unit
fun stackEvent(stack: *ScreenStack, e: *UiEvent, answer: (Hit, *UiEvent) -> Bool): Hit
fun stackResizeTop(stack: *ScreenStack, ui: *Ui): Bool // the window follows the top screen
```

The rules, in one place, so they are testable headless (`stress/minui-xml` pins each one):

- **A stack is never empty.** `newStack` takes the first screen, and `stackPop` refuses to take the
  last layer away - so there is no "no screen" case for any caller to handle, and `stackTop` needs
  no error arm. An app that wants Escape to close the window asks `stackSize` and decides itself;
  minui does not decide when a window closes.
- **Painting**: from the bottom up, **starting at the last opaque layer** (`stackPaintBase`) -
  everything below it is covered anyway, so a menu under a full-window game screen costs no drawing
  at all. A translucent overlay is an `opaque=false` layer: it paints over what is below it and
  dims it itself, by drawing a scrim.
- **Input**: only the top layer is dispatched to. Hover and press state is each frame's own fields,
  so a screen that is not on top keeps whatever it had and comes back as it was.
- **The window follows the top layer** (`stackResizeTop`): the size is the frame's root rect, which
  is where `newFrame`'s width and height went. The menu (360x348) and Expert's board (744x496) are
  one stack because of it.
- **A push shares, never copies**: the layer holds the caller's `&Frame`, and the app keeps the
  same box (`game.frame`, in the example) - a write through `stackTop` is visible through the app's
  own handle, which is what the stress case's last section proves.

The app's per-screen *data* stays the app's: the example needs one `Game` and nothing for the menu,
and switches on `stackTopName` where a class hierarchy would have virtuals. A family of screens
that carry different state each is where a `union class Screen` (the sketch this section started
with) earns its place - v1 has not needed one.

## 6. Painting: the visitor - LANDED (`paint.kt`)

Retained mode, one full-frame redraw per input (0.19 ms measured - no damage tracking in v1):

```kt
// The painter visits the arena in draw order: a parent before its children, children in
// `children` order, so a later sibling is on top - the same order the hit test reverses, and the
// same order the XML expands in. Nothing is stored on a node: hover and press come from the frame,
// which is what makes a button's chrome a function of *this* frame rather than of last frame's
// leftovers.
fun paintFrame(f: *Frame, theme: *UiTheme, ui: *Ui, custom: (Int) -> Unit): Unit
fun paintNode(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int, custom: (Int) -> Unit): Unit
fun paintSelf(f: *Frame, theme: *UiTheme, ui: *Ui, node: Int, custom: (Int) -> Unit): Unit
```

The look of the built-in kinds, in one place:

- **Panel**: a rounded fill in `theme.panel`; `palette = PaletteEmphasized` draws its edge too.
- **Label**: body text by default; `palette` picks title-bold (`Emphasized`), `Accent` or `Dim`,
  and `align` places it (`Center` goes through `uiTextBox`, which measures it).
- **Button**: the three fills from the theme, picked by the frame's own state -
  `f.pressed == node`, else `f.hover == node`, else idle, and `theme.buttonDisabled` when the
  node is disabled (it is hit, but `uiEvent` steps over it, so nothing hovers it). Its own `text`
  is centred; a button does not need a label child.
- **Sprite**: one atlas cell of the loaded sheet at the node's rect.
- **Custom**: handed to `custom(node)` - the app's own painting.

The two hooks that keep the framework small and the app free:

- `custom(node)` - the app's painting for `NodeKind.Custom` (the mine grid, a minimap, an animated
  portrait). This is "controls with custom painting": the control's geometry, name and listener
  live in the arena, its look lives in the app. The callback is the screen's own closure, so no
  vtable is needed to reach it, and it gets the node's index - `f.nodes[node]` is the geometry and
  `payload`.
- **`UiTheme`** - one value holding every colour the built-ins use. `argb(a, r, g, b)` (the `win*`
  glue) packs them, because `255 << 24` in Simse arithmetic is a needless signed-overflow question.

## 7. XML: a loader, not the model - LANDED (`xmlread.kt` + `xml.kt`)

The runtime model is the arena. XML is a **front end** that builds it, parsed once per screen; the
names it carries are what code binds to. The reasons to insist on this direction: a per-frame walk
of an XML tree re-scans attribute lists (the compiler's own profile was made of exactly that),
hit-testing would compare strings, and custom painting cannot be expressed in XML anyway.

Two files, because the two halves fail differently:

- **`xmlread.kt`** reads XML *text* into the `xml` module's own tree (`XmlNode`, `Attribute`) -
  the module supplies the types but not a reader. Elements with quoted attributes, self-closing
  tags, text content (it becomes the element's `text` attribute: `XmlNode` has no text slot),
  comments, and the five predefined entities; no namespaces, CDATA, processing instructions or
  DOCTYPE, and an unknown entity is an error rather than silence. Every element carries a `line`
  attribute, first in its list - XML text has no line numbers, and the loader's messages are worth
  little without them - and a document that writes `line` itself is refused. Every failure is a
  `Res` error with the line whose problem it is: `line 3: </screen> closes <panel>`.
- **`xml.kt`** maps that tree onto a `Frame` and rejects what it cannot use: an unknown element, an
  attribute the element has no use for (`widht`), a missing rect, a duplicate name, a grid whose
  size does not divide into its cells. A screen that loads is a screen that says what it means.

```kt
// A bad screen is a message with a line number, never a crash and never a half-built tree: the
// whole document is parsed and mapped before anything is returned, and a failed load leaves
// nothing behind to click on. A duplicate name is an error here (the name -> index map is built
// here), and `frameOn` reports a name that is not in the tree when the screen binds - both long
// before a click happens.
fun loadScreen(text: Str): Res<Frame>
fun parseXmlText(text: Str): Res<XmlNode>     // the reader on its own, for a tool that wants it
```

The vocabulary, and nothing beyond it:

```xml
<!-- The menu, as the example ships it: absolute geometry, Heroes 2's own layout model. Loaded
     once; the arena it builds is what the painter and the hit test walk. Names are the author's
     handles - the behaviour that goes with them is registered in code (`frameOn`). -->
<screen name="menu" width="360" height="348">
  <panel rect="12,16,336,84" palette="plain"/>
  <label rect="12,36,336,30" palette="emphasized" align="center">MINESWEEPER</label>
  <label rect="12,72,336,18" palette="dim" align="center">minui - SDL2 + Skia</label>
  <button name="beginner" rect="80,120,200,32">BEGINNER</button>
  <grid name="board" rect="12,52,270,270" cell="30"/>
</screen>
```

| element | adds | becomes |
| --- | --- | --- |
| `screen` | `name` (renames the root), `width`, `height` | the `Frame` itself |
| `panel` | - | `NodeKind.Panel` |
| `label` | `text` (content or attribute), `align` | `NodeKind.Label` |
| `button` | `text` | `NodeKind.Button` |
| `sprite` | `index` (the atlas cell) | `NodeKind.Sprite` |
| `custom` | `name` (required), `payload` | `NodeKind.Custom` - the app paints it |
| `grid` | `name` (required), `cell` | one named node plus `rows*cols` anonymous cells, `payload` = row-major index |
| every element | `name`, `rect`, `palette`, `visible`, `enabled` | the node's fields |

The `grid` line is the important one: it is a *generator*, so the board gets one listener and
per-cell hit-testing without 480 XML lines - the loader calls the same `addGrid` the app would.

Deliberately **not** in the XML: behaviour (names are inert on their own), custom painting,
layout beyond absolute rects, and logic. And deliberately not in the *reader*: entity
*declarations*, CDATA, namespaces, a DOCTYPE, attribute values without quotes - a screen is a
small hand-written document, and one that needs more than the reader has should say so loudly,
which it does.

## 8. The wrapper API (`import minui`)

Two files of declarations (SDL2, Skia), one of glue, one of types:

```
examples/minesweeper/minui/
  tree.kt      the headless core (landed, shot 2a): arena, names, listeners, hit-test, walk, events
  paint.kt     the painter (landed, shot 2b): UiTheme, the palette values, the visitor
  api.kt       the raw @SmGen declarations (landed): SDL2, Skia's C API, the `win*` glue helpers,
               every constant the two libraries need (in decimal, printed from their own headers)
  ui.kt        the typed surface the app calls (landed): window, canvas, text, sprites, input
  _res.md      the C++ glue (landed): the SDL_Event union readers, the two buffers, the BMP writer,
               `argb`, and the synthetic mouse event the scripted modes push through SDL
app/src/       `main.kt` - the game itself, shot 3 + 4 (see the README)
```

The typed surface is integer-coordinate (the UI is pixel art) and has no object lifetime to
manage by hand beyond `uiOpen`/`uiClose`. This is the landed surface (`ui.kt`):

```kt
fun uiOpen(title: Str, width: Int, height: Int, resizable: Bool, vsync: Bool): Ui
fun uiClose(ui: *Ui): Unit
fun uiIsOpen(ui: *Ui): Bool
fun uiFailed(): Str                              // SDL_GetError(), for the app's own message
fun uiResize(ui: *Ui, width: Int, height: Int): Bool   // new window/texture/buffer/surface size
fun uiPresent(ui: *Ui): Unit                     // upload + copy + present (0.16-0.37 ms, see §11)
fun uiPollEvent(ui: *Ui): Bool                   // one event into `ui.event`, false when none left
fun uiPushMouse(kind: UiEventKind, x: Int, y: Int, button: Button): Bool   // scripted input
fun uiPushKey(kind: UiEventKind, key: Int): Bool    // ... and a scripted keystroke (`:K`)
fun uiButtons(ui: *Ui): Int                      // the mask held right now (chording)
fun uiColor(alpha: Int, red: Int, green: Int, blue: Int): Int
fun uiRgb(red: Int, green: Int, blue: Int): Int
fun uiClear(ui: *Ui, color: Int): Unit
fun uiFill(ui: *Ui, rect: Rect, color: Int): Unit
fun uiRoundFill(ui: *Ui, rect: Rect, radius: Int, color: Int): Unit
fun uiOutline(ui: *Ui, rect: Rect, color: Int): Unit     // 1px, stroke
fun uiLine(ui: *Ui, x0: Int, y0: Int, x1: Int, y1: Int, color: Int): Unit
fun uiCircle(ui: *Ui, centerX: Int, centerY: Int, radius: Int, color: Int): Unit
fun uiPie(ui: *Ui, rect: Rect, startDegrees: Int, sweepDegrees: Int, color: Int): Unit
fun uiLoadFont(ui: *Ui, family: Str, size: Int, boldSize: Int): Bool   // system fonts, no .ttf
fun uiText(ui: *Ui, x: Int, baselineY: Int, text: Str, color: Int, bold: Bool): Unit
fun uiTextBox(ui: *Ui, rect: Rect, text: Str, color: Int, bold: Bool): Unit
fun uiTextWidth(ui: *Ui, text: Str, bold: Bool): Int      // Skia measures; centred text uses it
fun uiLoadSheet(ui: *Ui, path: Str, cell: Int): Bool      // a PNG atlas, square cells, row-major
fun uiSprite(ui: *Ui, index: Int, rect: Rect): Unit       // nearest sampling
fun uiScreenshot(ui: *Ui, path: Str): Bool               // the verification path
fun uiTicks(): Int                                       // SDL's clock, for the game timer
fun uiDelay(milliseconds: Int): Unit
```

Every rect in that surface is the tree's own (`Rect`, `Int` fields; `smRect(x, y, w, h)` builds
one). Skia wants four *floats*, so the widening happens in the glue, once:
`winRect(left, top, right, bottom)` widens four integers into the library's own memory and hands
back its address, which the `sk_*` call takes as an opaque pointer. **No float coordinate and no
Skia-shaped type exists above the glue** - which is what keeps a control's code down to arithmetic
on the rect it already has.

`ui.event` is a `UiEvent` field on `Ui`, filled by `uiPollEvent` — no allocation, no `Opt`
copy, and the app's loop reads the fields it needs:

```kt
enum class UiEventKind {
    None = 0, MouseMove = 1, MouseDown = 2, MouseUp = 3, Wheel = 4,
    KeyDown = 5, KeyUp = 6, Close = 7, Resize = 8, Tick = 9
}

// SDL's own numbering (left/middle/right = 1/2/3), named so no call site holds a magic number.
// A *chord* is read from the event's mask instead: `buttons` holds SDL's `SDL_BUTTON_*MASK` bits
// (`tree.kt` names them `MaskLeft`/`MaskMiddle`/`MaskRight`), so Left+Right is
// `MaskLeft + MaskRight` - which is how a chord is told apart from a plain click.
enum class Button { None = 0, Left = 1, Middle = 2, Right = 3 }

data class UiEvent(
    var kind: UiEventKind,
    var x: Int,
    var y: Int,
    var button: Button,   // which button this event is about
    var buttons: Int,     // the mask of buttons held: MaskLeft | MaskMiddle | MaskRight
    var key: Int,         // SDL's keycode; the app compares against the few it names itself
    var wheel: Int
)
```

## 9. Minesweeper, mapped onto all of it

The example is the first *user* of minui, and it is chosen to exercise every mechanism above:

| Layer | Screen | What it proves |
| --- | --- | --- |
| 0 | `MenuScreen` — title, three difficulty buttons, a best-time label | XML-loaded frame, names + `frameOn` listeners, hover/press painting |
| 1 | `GameScreen` — mine counter, timer, smiley restart, board | custom painting (one `Custom` node per cell), one listener for the whole grid, left/right/chord clicks |
| 2 | `ResultScreen` — translucent scrim + panel + `Play again` / `Menu` | a modal layer: paints below dimmed, blocks input, pops back |

The board is a `grid` node expanded at load into `width × height` cell nodes. The grid carries the
name and the listener; a cell carries the `payload` index. The click path is
`hit-test → leaf cell → bubble to the grid's listener → Act.CELL → h.hit's payload → (x, y) →
reveal`, which is exactly the "visit the tree, find the node that is named, call back for it"
mechanism — with one listener serving 81..480 cells.

Verification, none of which needs a human at the keyboard:

- `--frames N` — render N frames and exit (a smoke test),
- `--screenshot out.bmp` — write what SDL actually presented (written by the glue through
  `SDL_RenderReadPixels`, so it covers the whole pipeline, not just Skia's buffer),
- `--script "3,4:L; 5,6:R; 4,4:M"` — synthesize real mouse events into the loop, one per frame,
  so click handling is exercised end to end,
- `stress/minesweeper` — the *rules* module tested headless with no UI at all (it has no import
  of minui; the harness builds it with `--module examples/minesweeper/game`).

## 10. Not in v1 (and why)

- **Layout engine** (rows, columns, anchors, stretch). v1 is absolute rects plus the `grid`
  generator, which is exactly Heroes 2's model. A box model is v2, and the arena is where it will
  write.
- **Damage tracking** (repaint only what changed). A full redraw is ~0.2 ms and the loop draws
  only when something changed; per-rect damage is for when a frame costs more than ~2 ms.
- **Keyboard focus navigation, text input, IME.** `keyFocus` is in `Frame` from the start so the
  rule has a home; tabbing and editors are later.
- **A modal layer that dims what is below it.** The stack has everything it needs - `opaque=false`
  paints the layer below, and input already stops at the top layer - but no screen has needed the
  scrim yet, so the dimming itself is the first overlay's to write (three lines of `uiFill` with an
  alpha). The example's stack is two opaque screens.
- **Scroll containers, animations/tweens, themes beyond one palette list, i18n, accessibility.**
- **XML for *everything*.** Custom painting stays in code, by design: XML describes structure and
  built-in styling, the app describes what only it can draw. Screens are *loaded* once, never
  walked per frame.
- **A second window.** One window, one renderer; layers are screens, not OS windows.

## 11. Verified facts this rests on

Probes in `build/` (git-ignored, not part of the deliverable), run on Windows arm64:

- Skia over a plain buffer: AA shapes, rounded rects, arcs, PNG decode + scaled blit with crisp
  nearest scaling (`filter quality NONE`), and **text from system fonts with no font file** —
  the font-manager trio (`sk_fontmgr_create_default` → `sk_fontmgr_match_family_style` →
  `sk_font_new_with_values`), with `sk_font_measure_text` for layout. `sk_typeface_create_default`
  is still exported but crashed two runs out of two: do not call it.
- `sk_canvas_draw_image_rect` takes **six** arguments (sampling options before the paint).
- `sk_imageinfo_t` is `{ colorspace, width, height, colorType, alphaType }` — colorspace first —
  with `BGRA_8888 = 6` and `PREMUL = 2`.
- SDL2 + Skia end to end: window, streaming texture upload, present, and read-back for
  screenshots: **0.16 / 0.30 / 0.37 ms per frame** at 294x382 / 504x592 / 744x496 through SDL's
  **software** renderer (upload + clear + copy + present; `build/colorprobe5.cpp`).
- **The accelerated (direct3d) renderer trades red and blue for *some* window sizes** - in the
  window itself, not only in a read-back. Identical code, a fresh window per size: 504x592 and
  500x500 come back as `33 22 11` where `11 22 33` went in, while 512x512, 800x600, 294x382 and
  1024x768 come back faithful (`build/colorprobe2.cpp`). Declaring the texture
  `SDL_PIXELFORMAT_ABGR8888` moves the bug rather than fixing it (504x592 becomes right, 512x512
  wrong - `build/colorprobe4.cpp`), so it is the backend, not the format we hand it. The software
  renderer is exact at every size tested (`build/colorprobe3.cpp`); Skia having already drawn
  every pixel, all it is asked to do is blit - which is why `uiOpen` asks for it.
- `SDL_PIXELFORMAT_ARGB8888` is the memory layout Skia writes; `SDL_MAIN_HANDLED` is required.
- The arena's mutation shape compiles and runs: `node.children[i].state = pressed` through a
  `*Node`, and copy-out/copy-back through a `*List<T>`.
- **A class *construction* is braced initialization** in the emitted C++ (`SkRect{x, y, ...}`), so a
  runtime `Int` in a `Float32` field is a narrowing *error* - while a literal (`1.0`) is fine, and a
  call argument or a field write converts freely. The wrapper does not lean on any of that: rects
  cross as four integers and the widening lives in the glue (`winRect`), so the surface above it
  carries no float coordinate at all.
- **A rect scratch, not a rect type**: Skia reads four floats from memory, and a call can need two
  rects at once (`sk_canvas_draw_image_rect` reads a source and a destination), so the glue keeps a
  four-slot pool and hands out one address per call.
- **A `native` declaration may take a pointer to a class** (`*SkRect`, `*SkImageInfo`): the thunk
  spells it `void*` and the argument crosses as the address, which is what a mirrored
  `native class` is for. The generator refused it before (`stress/native-struct-pointer` is the
  case, and `GetSystemTime` filling a `SYSTEMTIME` in place is the proof) - without it no Skia call
  that takes a struct could be declared at all.
- **SDL's constants are data**: `SDL_PIXELFORMAT_ARGB8888` is 372645892 and
  `SDL_TEXTUREACCESS_STREAMING` is 1 (both printed from the kit's headers by
  `build/sdlconst.cpp`; a guessed value had been wrong in each direction).
- **Scripted input can go through SDL's own queue**: the glue builds the bytes SDL would have
  written (`simse_ui_mouseEvent`, `simse_ui_keyEvent`) and the app hands them to `SDL_PushEvent`,
  so a screenshot proves the whole input path - the button fills measure 58,90,138 idle, 78,116,172
  hovered and 44,68,104 pressed, and a `:K` entry reaches the same handler a real keystroke does.
- **A member call on a value reached through a pointer needs a local first**: `e.kind.toInt()`
  where `e` is a parameter emits `static_cast<Int>(simse_addressOf(e->kind))` - the field's
  *address* - which cl.exe refuses. The compiler's own sources carry the same workaround with the
  same reason (`src/linear/ReuseExprs.kt`: "The opcode as a local first: `op.kind.toInt()`
  addresses a member of a pointer"). Read the field into a `val` and call the member on that.
- **`Res<Unit>` transpiles and then does not compile**: the payload becomes a `void` field
  (`Res<void>`, `SmResStorage<true, void>`), and a `Res<Int>` whose payload is always 0 is what the
  reader's and loader's internal "it worked" results are spelled as instead. The public surface is
  `Res<Frame>`/`Res<XmlNode>` either way.
- **Two address-of-a-plain-`Int` locals before a lambda literal break the parser.** `val a: *Int =
  *x` once is fine (and a pointer to a struct, a `List` or a class is fine any number of times,
  which is why the rest of minui never hits it); twice - or a `*List` plus a `*Int` - before a
  lambda is either `expected ')'` at the lambda's parameter list or `unsupported: assignment
  target` at its `=`. The stress case keeps one `*LastClick` for its three results, which is both
  the workaround and the clearer shape.
- **`Array<T>` answers `count()`, not `size()`** (a `List` answers `size()`): the length operation
  on the counted block, used by the loader to walk an `XmlNode`'s children.

## 12. Shots

1. **minui v0 - LANDED** (`examples/minesweeper/minui/{api,ui}.kt`, `_res.md`,
   `app/src/main.kt`): the SDL window, the Skia canvas over a byte buffer, `uiPresent`,
   `uiScreenshot`, the event pump, system-font text, PNG sprites and the scripted-input push, all
   from Simse. Verified by screenshots whose pixels are then measured (idle/hovered/pressed fills),
   and by `--frames` smoke runs; the present path is SDL's software blitter (§11). The example's
   README has the commands.
2. **The tree, in two halves**:
   - **2a, headless — LANDED** (`examples/minesweeper/minui/tree.kt`, verified by
     `stress/minui-dispatch`): the arena, names, `frameOn` listeners, hit-test, bubbling,
     capture and the event path, with no window at all. The case's `expected.stdout` is the
     contract: it pins each claim in §4 line by line (leaf-first bubbling, the disabled step-over,
     one listener for 25 cells with the leaf naming the cell, capture across a drag, a declining
     listener, an unclaimed click, and the button mask that makes a chord a chord).
   - **2b, painted - LANDED** (`paint.kt`, `app/src/main.kt`): the same tree drawn through minui,
     three fake buttons with names and listeners, a board of custom cells, a status line the
     handlers rewrite. Verified by scripted runs whose output *and* pixels say the same thing:
     `--script "210,115:L"` printed `action new_game count=1` and the button measured hovered
     (78,116,172), a press-only script measured pressed (44,68,104), `--script "162,344:L"` printed
     `action cell 26` and the cell measured revealed (30,38,52), and `:R` printed the flag with the
     flag's own red at the cell's centre. (Those drills were the demo's own buttons; the app that
     replaced it keeps the same prefix-free dispatch, and shot 4's list has its commands.)
3. **The rules - LANDED** (`examples/minesweeper/game/api.kt`, verified by `stress/minesweeper`):
   a UI-free module - deterministic (Schrage PRNG), first click always safe (a 3x3 kept clear),
   flood reveal, flag/unflag, chord only when the number is satisfied, win/lose transitions. The
   case pins each: the PRNG's first value, the three layouts' sizes, the reveal count after a
   first click, re-reveal a no-op, flags on every mine on a win, clicks after a loss dead.
4. **The game - LANDED** (`examples/minesweeper/app/src/main.kt`): one screen - named counter,
   clock, status line, a NEW GAME button, three difficulty buttons (the current one disabled) and
   the board as one grid of `Custom` cells. Left opens, right flags, middle chords; R restarts,
   1/2/3 switch difficulty; a difficulty switch rebuilds the tree and resizes the window. The
   frame is drawn when something changed (an event, or the clock's second), so an idle window
   costs no CPU. Verified end to end: a 72-click `--script` wins the seeded board (counter `000`,
   every mine flagged, `you won in 0s`), a click on a mine reddens it and X's a wrong flag, a
   chord after one flag opens the rest, and every measured colour survives the resize.
5. **Screens and XML - LANDED** (`screens.kt`, `xmlread.kt`, `xml.kt`, verified by
   `stress/minui-xml`): the stack of §5 and the loader of §7, with the menu that the example now
   opens on. The case is headless and pins every line: a document that loads (kinds, geometry,
   names, the renamed root, text with an entity decoded, the grid expansion, a click through the
   loaded tree naming the cell), twenty documents that do not (each with its line: unterminated
   tags and comments, an unknown entity, a duplicate attribute, the reserved `line`, a mismatched
   close tag, an unknown element, a missing rect, a duplicate name, a grid that does not divide),
   and the stack's own rules (top/name/size, the paint base under and over overlays, the bottom
   layer that cannot be popped, and a write through the stack's top read back through the app's
   own boxed handle). The app's screenshots then show the same thing painted: the menu, BEGINNER
   pushing a 294x382 board, EXPERT pushing 744x496, MENU popping back to the menu - and the keys,
   which needed `uiPushKey` (a synthetic keystroke in the glue, `:K` in a script) to be testable
   headless at all: `r:K` prints `key restart` and leaves a fresh board, `2:K` resizes to
   Intermediate's 504x592, `esc:K` pops to the menu.

Each shot is independently verifiable, and shots 2 and 3 are independent of each other.
