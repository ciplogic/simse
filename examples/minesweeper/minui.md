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
against a 16.7 ms budget. The same canvas can later target a GPU surface (Skia's
backend-render-target entry points over an SDL/GL context) without changing anything above it.

Two traps pinned by the probes, both load-bearing for the wrapper:

- The texture must be `SDL_PIXELFORMAT_ARGB8888` — SDL names a format by its *packed value*, so
  the bytes are B,G,R,A in memory, which is what Skia's `BGRA_8888` writes and a Win32 DIB holds.
  The similarly-named `...BGRA8888` swaps every channel.
- `SDL_MAIN_HANDLED` must be defined: this project links nothing (no `SDL2main.lib`).

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
// `palette` is a slot into the screen's colour list; `sprite` is an atlas cell of the loaded
// sheet. Two different things, two fields.
data class Node(
    var kind: NodeKind,
    var name: Str,            // "" when anonymous
    var rect: Rect,
    var parent: Int,          // arena index, -1 for the frame's root
    var children: List<Int>,  // arena indices, in paint order (later = on top)
    var listener: Int,        // index into Frame.listeners, -1 = none
    var payload: Int,         // the app's payload
    var text: Str,            // labels and counters
    var palette: Int,         // slot into the screen's palette
    var sprite: Int,          // atlas cell of the loaded sheet
    var visible: Bool,
    var enabled: Bool
)

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
fun uiEvent(f: *Frame, e: *UiEvent, answer: (Hit) -> Bool): Hit      // the tree's whole path
```

The framework stops at the listener: the *screen* reads the action code and answers. That split is
the whole point of the model - minui never needs to know what a button does.

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

## 5. Screens: the stack

Heroes-shaped navigation: menu → game → city/battle, each screen pushed, `Escape`/a Back button
popping. The stack is a `List<Layer>` - each layer holds its `&Frame` boxed, so a push shares the
frame rather than copying an arena of 480 nodes - plus a *kind per frame* for dispatch, since
there is no vtable:

```kt
union class Screen(var Menu: MenuScreen, var Game: GameScreen, var Result: ResultScreen)

data class Layer(
    var screen: Screen,
    var frame: &Frame,
    var opaque: Bool,   // true: nothing below this layer is painted (a city screen)
    var modal: Bool     // true: no event reaches below it. An overlay is opaque = false, modal =
                        // true: the screen below is painted (then dimmed by the overlay itself)
                        // and gets no input - a Heroes dialog, a battle result
)

fun push(stack: *List<Layer>, layer: Layer): Unit
fun pop(stack: *List<Layer>): Unit
fun top(stack: *List<Layer>): Layer     // no layers is a bug: the menu is always there
```

Rules, in one place so they are testable:

- **Painting**: from the bottom up, **skipping everything below the last opaque layer**. A modal
  overlay paints a scrim over the frame below and then itself, which is how a Heroes dialog and a
  battle screen differ from the city screen.
- **Input**: only the top layer is dispatched to (`uiDispatch` takes its layer). Popping is
  `Escape` or a `Act.BACK` listener, applied *after* the event is handled so a screen can refuse
  to close.
- Screens are values; a screen that owns a `Board` owns it by value and mutates it by pointer.
  Nothing in the stack is shared between two layers except through `&`.

## 6. Painting: the visitor

Retained mode, one full-frame redraw per input (0.19 ms measured — no damage tracking in v1):

```kt
// The painter visits the arena in draw order (parents before children, siblings in `children`
// order), and for each node either draws it itself (the built-in kinds) or hands it to the
// screen's custom painter. State is passed in, never stored in the node: hover and press come
// from the frame, game state from the screen.
fun paintFrame(f: *Frame, s: *Screen, ui: *Ui): Unit {
    // walk: recursion over `children` by index, so the painter never copies a node
    // for each node:
    //   Panel  -> fill / round-rect from the node's palette slot
    //   Label  -> text, centered or left, measured with uiTextWidth
    //   Button -> chrome for normal / hover / pressed, then its label child
    //   Sprite -> one atlas cell of the loaded sheet
    //   Custom -> screenPaintNode(s, f, node, ui)
}
```

Two hooks keep the framework small and the app free:

- `screenPaintNode(s, f, node, ui)` — the app's own painting for `NodeKind.Custom` (the mine
  grid, a minimap, an animated portrait). This is "controls with custom painting": the control's
  geometry, name and listener live in the arena, its look lives in the app.
- **Palette**: colors are `Int` (ARGB) taken from a per-screen palette (`List<Int>`, indexed by a
  node's `palette` slot) so a theme is one list. `argb(a, r, g, b)` is a tiny C++ helper in
  `_res.md`, because packing 255<<24 in Simse arithmetic is a needless signed-overflow question.

## 7. XML: a loader, not the model

The runtime model is the arena. XML is a **front end** that builds it, parsed once per screen
(`xml` module); the names it carries are what code binds to. The reasons to insist on this
direction: a per-frame walk of an XML tree re-scans attribute lists (the compiler's own profile
was made of exactly that), hit-testing would compare strings, and custom painting cannot be
expressed in XML anyway.

```xml
<!-- One screen, absolute geometry (Heroes 2's own layout model). Loaded once; the arena it
     builds is what the painter and the hit test walk. Names are the author's handles - the
     behaviour that goes with them is registered in code (`frameOn`), never in the XML. -->
<screen name="menu" width="360" height="420">
  <panel rect="16,64,328,120" palette="panel"/>
  <label rect="16,72,328,24" palette="title" align="center">MINESWEEPER</label>
  <button name="beginner" rect="32,200,120,32" palette="primary">BEGINNER</button>
  <button name="expert"   rect="32,240,120,32" palette="primary">EXPERT</button>
  <grid name="board" rect="8,8,288,288" cell="32"/>
</screen>
```

```kt
// A loader answers Res: a bad screen is a message with a line number, never a crash. A duplicate
// name in one screen is an error (the name->index map is built here), and `frameOn` reports a
// name that is not in the tree when the screen binds - both long before a click happens.
fun loadScreen(xmlText: Str): Res<Frame>
```

Deliberately **not** in the XML: behaviour (names are inert on their own), custom painting, layout
beyond absolute rects, and logic. v1 parses: `screen`, `panel`, `label`, `button`, `sprite`,
`grid` (a uniform cell grid, which is what the board is), with `name`, `rect`, `palette`, `align`
and `text` — where `text` may be an attribute or the element's content (content wins, trimmed).
A `grid` node is a *generator*: the loader expands it into N `Custom` cell nodes with `payload` =
cell index, which is how the board gets per-cell hit-testing without 480 XML lines.

## 8. The wrapper API (`import minui`)

Two files of declarations (SDL2, Skia), one of glue, one of types:

```
examples/minesweeper/minui/
  tree.kt      the headless core (LANDED): arena, names, listeners, hit-test, walk, events
  api.kt       the raw @SmGen declarations: SDL2 (window/renderer/texture/events) and sk_* (canvas)
  ui.kt        the window/canvas surface the app actually calls
  _res.md      C++ glue: the SDL_Event union reader, the event queue, argb, the screenshot BMP
```

The typed surface is integer-coordinate (the UI is pixel art) and has no object lifetime to
manage by hand beyond `uiOpen`/`uiClose`:

```kt
fun uiOpen(title: Str, width: Int, height: Int, resizable: Bool): Ui     // window + renderer + texture + surface
fun uiClose(ui: *Ui): Unit
fun uiBegin(ui: *Ui): Unit                       // clear to the background colour
fun uiPresent(ui: *Ui): Unit                     // upload + copy + present (the probed pipeline)
fun uiPollEvent(ui: *Ui): Bool                   // one event into `ui.event`, false when none left
fun uiClear(ui: *Ui, color: Int): Unit
fun uiRect(ui: *Ui, r: Rect, color: Int): Unit
fun uiRound(ui: *Ui, r: Rect, radius: Int, color: Int): Unit
fun uiLine(ui: *Ui, x0: Int, y0: Int, x1: Int, y1: Int, color: Int): Unit
fun uiSprite(ui: *Ui, sprite: Int, r: Rect): Unit        // one atlas cell of the loaded sheet
fun uiText(ui: *Ui, x: Int, y: Int, text: Str, size: Int, color: Int): Unit
fun uiTextWidth(ui: *Ui, text: Str, size: Int): Int      // Skia measures; layout uses it
fun uiScreenshot(ui: *Ui, path: Str): Bool               // the verification path
fun uiTicks(): Int                                       // SDL's clock, for the game timer
```

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
- **Damage tracking** (repaint only what changed). A full redraw is 0.19 ms; revisit when a frame
  costs more than ~2 ms.
- **Keyboard focus navigation, text input, IME.** `keyFocus` is in `Frame` from the start so the
  rule has a home; tabbing and editors are later.
- **Scroll containers, animations/tweens, themes beyond one palette list, i18n, accessibility.**
- **XML for *everything*.** Custom painting stays in code, by design: XML describes structure and
  built-in styling, the app describes what only it can draw.
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
- SDL2 + Skia end to end: window, `direct3d` renderer (accelerated, render targets), streaming
  texture upload, present, and read-back for screenshots: **0.19 ms/frame at 560×300**.
- `SDL_PIXELFORMAT_ARGB8888` is the memory layout Skia writes; `SDL_MAIN_HANDLED` is required.
- The arena's mutation shape compiles and runs: `node.children[i].state = pressed` through a
  `*Node`, and copy-out/copy-back through a `*List<T>`.
- **A member call on a value reached through a pointer needs a local first**: `e.kind.toInt()`
  where `e` is a parameter emits `static_cast<Int>(simse_addressOf(e->kind))` - the field's
  *address* - which cl.exe refuses. The compiler's own sources carry the same workaround with the
  same reason (`src/linear/ReuseExprs.kt`: "The opcode as a local first: `op.kind.toInt()`
  addresses a member of a pointer"). Read the field into a `val` and call the member on that.

## 12. Shots

1. **minui v0**: SDL window + Skia canvas + the event pump + `uiPresent`/`uiScreenshot`, in
   Simse; the probe's frame drawn from Simse. Verified by a screenshot.
2. **The tree, in two halves**:
   - **2a, headless — LANDED** (`examples/minesweeper/minui/tree.kt`, verified by
     `stress/minui-dispatch`): the arena, names, `frameOn` listeners, hit-test, bubbling,
     capture and the event path, with no window at all. The case's `expected.stdout` is the
     contract: it pins each claim in §4 line by line (leaf-first bubbling, the disabled step-over,
     one listener for 25 cells with the leaf naming the cell, capture across a drag, a declining
     listener, an unclaimed click, and the button mask that makes a chord a chord).
   - **2b, painted**: the same tree drawn through minui, with hover/press feedback and three fake
     buttons. Verified by `--script` clicks and screenshots.
3. **The rules**: `examples/minesweeper/game` + `stress/minesweeper` (headless, deterministic).
4. **The game**: the three screens, the board, assets (generated PNG atlas), `run.bat`, README.
5. **XML**: the loader + the menu screen loaded from XML (the rest stays code-built until it
   earns its place).

Each shot is independently verifiable, and shots 2 and 3 are independent of each other.
