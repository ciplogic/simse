package minesweeperapp

import minui

// minui v0.5 - shot 2b of `../minui.md`: the tree draws itself.
//
// The app is a *client* of minui now instead of a bag of drawing calls. It builds a frame (names,
// listeners, geometry), routes the platform events through `uiEvent` and paints through
// `paintFrame`. What a screenshot proves is therefore the dispatch and not the drawing: the status
// line can only say "cell 27" if a click travelled hit test -> bubble -> the grid's listener ->
// the action, and the cell's `payload` reached the handler.
//
//   minesweeperdemo.exe                        run until Escape (or the window's close button)
//   minesweeperdemo.exe --frames 3             draw three frames and leave (a smoke test)
//   minesweeperdemo.exe --screenshot out.bmp   write what the renderer presented
//   minesweeperdemo.exe --sheet tiles.png      decode an atlas (16px cells) for the cells
//   minesweeperdemo.exe --click 210,115        a scripted click through SDL's own queue
//   minesweeperdemo.exe --press 210,115        ... with the button still held

// The action codes this screen answers. minui only ever sees the `Int`s.
enum class Act {
    None = 0,
    NewGame = 1,
    Difficulty = 2,
    Cell = 3,
    Quit = 4,
    Background = 5
}

// The app's own state: what the handlers mutate. (The frame and the theme are minui's; the rules
// module replaces `revealed`/`flags` with a real board in shot 3.)
data class Demo(
    var status: Str,
    var clicks: Int,
    var difficulty: Int,
    var revealed: List<Int>,
    var flags: List<Int>,
    var quit: Bool
)

fun difficultyName(level: Int): Str {
    if (level == 0) {
        return "BEGINNER"
    }
    if (level == 1) {
        return "INTERMEDIATE"
    }
    return "EXPERT"
}

// One theme value: the palette is one thing to keep in step, and a second theme is a second value
// rather than a second code path.
fun demoTheme(): UiTheme {
    return UiTheme(
        uiRgb(24, 32, 48), uiRgb(32, 44, 64), uiRgb(58, 74, 100),
        uiRgb(58, 90, 138), uiRgb(78, 116, 172), uiRgb(44, 68, 104), uiRgb(48, 56, 70),
        uiRgb(232, 240, 248), uiRgb(214, 224, 236), uiRgb(143, 211, 244), uiRgb(110, 126, 148)
    )
}

// A value's place in a small list. (The demo's stand-in for a board; shot 3 gives it rules.)
fun listHas(items: *List<Int>, value: Int): Bool {
    var i: Int = 0
    while (i < items.size()) {
        if (items[i] == value) {
            return true
        }
        i = i + 1
    }
    return false
}

fun listToggle(items: *List<Int>, value: Int): Unit {
    var found: Int = 0 - 1
    var i: Int = 0
    while (i < items.size()) {
        if (items[i] == value) {
            found = i
        }
        i = i + 1
    }
    if (found >= 0) {
        items.removeAt(found)
        return
    }
    items.append(value)
}

// The screen: a title, three named buttons, an 8x8 board of custom cells, and a status line. Every
// control that answers a click is *named* here and bound by name in `bindDemo` - nothing looks a
// node up by index once this function returns.
fun buildDemo(width: Int, height: Int): Frame {
    var f: Frame = newFrame(width, height)
    var fp: *Frame = *f

    val title: Int = addNode(fp, fp.root, NodeKind.Label, 0, 12, width, 34)
    setText(fp, title, "MINESWEEPER")
    setPalette(fp, title, PaletteEmphasized)
    setAlign(fp, title, Align.Center)

    val subtitle: Int = addNode(fp, fp.root, NodeKind.Label, 0, 46, width, 22)
    setText(fp, subtitle, "minui draws this tree")
    setPalette(fp, subtitle, PaletteAccent)
    setAlign(fp, subtitle, Align.Center)

    val panel: Int = addNode(fp, fp.root, NodeKind.Panel, 24, 76, width - 48, 160)
    setPalette(fp, panel, PaletteEmphasized)

    val newGame: Int = addNode(fp, panel, NodeKind.Button, 44, 96, width - 88, 38)
    setName(fp, newGame, "new")
    setText(fp, newGame, "NEW GAME")

    val difficulty: Int = addNode(fp, panel, NodeKind.Button, 44, 142, width - 88, 38)
    setName(fp, difficulty, "difficulty")
    setText(fp, difficulty, "DIFFICULTY: BEGINNER")

    val quit: Int = addNode(fp, panel, NodeKind.Button, 44, 188, width - 88, 38)
    setName(fp, quit, "quit")
    setText(fp, quit, "QUIT")

    // The board: 8x8 cells of 32, centred. One named node with one listener; each cell is an
    // anonymous `Custom` node whose `payload` is its own index (row-major).
    val boardSize: Int = 256
    addGrid(fp, fp.root, "board", (width - boardSize) / 2, 248, 32, 8, 8)

    val status: Int = addNode(fp, fp.root, NodeKind.Label, 24, height - 32, width - 48, 22)
    setName(fp, status, "status")
    setText(fp, status, "click a button, or a cell")
    setAlign(fp, status, Align.Center)
    setPalette(fp, status, PaletteAccent)
    return f
}

// The bindings: name -> action, resolved once. The Bool each call answers is the check that the
// name is in the tree - a typo shows up here, at startup, and not as a button that does nothing.
fun bindDemo(fp: *Frame): Bool {
    var bound: Bool = true
    if (!frameOn(fp, "new", Act.NewGame.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "difficulty", Act.Difficulty.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "quit", Act.Quit.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "board", Act.Cell.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "root", Act.Background.toInt(), 0)) {
        bound = false
    }
    return bound
}

// One cell of the board: the painter's `custom` hook. The arena carries the cell's geometry and
// identity; the look comes from the demo's state and from the pointer's place. `hover`/`pressed`
// are the *grid* (the node the click reaches), so a cell's own feedback is the pointer's cell -
// which is exactly the split `minui.md` describes.
fun paintCell(f: *Frame, demo: *Demo, board: Int, ui: *Ui, node: Int, sheetLoaded: Bool): Unit {
    if (node == board) {
        return // the grid itself draws nothing; its cells do
    }
    val rect: Rect = f.nodes[node].rect
    val index: Int = f.nodes[node].payload
    val revealed: Bool = listHas(*demo.revealed, index)
    val flagged: Bool = listHas(*demo.flags, index)
    val inside: Bool = ui.mouseX >= rect.x && ui.mouseX < rect.x + rect.width
            && ui.mouseY >= rect.y && ui.mouseY < rect.y + rect.height
    if (!revealed && sheetLoaded) {
        uiSprite(ui, 0, rect.x + 1, rect.y + 1, rect.width - 2)
        return
    }
    var face: Int = uiRgb(58, 90, 138)
    if (revealed) {
        face = uiRgb(30, 38, 52)
    } else if (inside && f.pressed == board) {
        face = uiRgb(44, 68, 104)
    } else if (inside) {
        face = uiRgb(78, 116, 172)
    }
    uiRoundFill(ui, rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2, 4, face)
    if (flagged) {
        uiPie(ui, rect.x + 9, rect.y + 7, 14, 16, 0 - 60, 300, uiRgb(232, 193, 90))
        return
    }
    if (revealed) {
        uiPie(
            ui, rect.x + 11, rect.y + 11, 10, 10, 0 - 90, 90 + (index % 5) * 60,
            uiRgb(143, 211, 244)
        )
    }
}

fun main(args: List<Str>): Int {
    var maxFrames: Int = 0 - 1
    var screenshotPath: Str = ""
    var sheetPath: Str = ""
    var clickX: Int = 0 - 1
    var clickY: Int = 0 - 1
    var holdClick: Bool = false
    var clickButton: Button = Button.Left
    var index: Int = 0
    while (index < args.size()) {
        if (args[index] == "--frames" && index + 1 < args.size()) {
            val parsed: Opt<Int> = args[index + 1].toInt()
            if (parsed.hasValue()) {
                maxFrames = parsed.value()
            }
            index = index + 1
        } else if (args[index] == "--screenshot" && index + 1 < args.size()) {
            screenshotPath = args[index + 1]
            index = index + 1
        } else if (args[index] == "--sheet" && index + 1 < args.size()) {
            sheetPath = args[index + 1]
            index = index + 1
        } else if ((args[index] == "--click" || args[index] == "--press" || args[index] == "--rclick")
            && index + 1 < args.size()
        ) {
            holdClick = args[index] == "--press"
            if (args[index] == "--rclick") {
                clickButton = Button.Right
            }
            val parts: List<Str> = args[index + 1].split(",")
            if (parts.size() == 2) {
                val pointX: Opt<Int> = parts[0].toInt()
                val pointY: Opt<Int> = parts[1].toInt()
                if (pointX.hasValue() && pointY.hasValue()) {
                    clickX = pointX.value()
                    clickY = pointY.value()
                }
            }
            index = index + 1
        }
        index = index + 1
    }

    var ui: Ui = uiOpen("minui v0.5 - the tree draws itself", 420, 540, false, true)
    if (!uiIsOpen(*ui)) {
        println("window open failed: " + uiFailed())
        return 1
    }
    var uiPtr: *Ui = *ui
    val fontLoaded: Bool = uiLoadFont(*ui, "Segoe UI", 16, 30)
    var sheetLoaded: Bool = false
    if (sheetPath.size() > 0) {
        sheetLoaded = uiLoadSheet(*ui, sheetPath, 16)
    }

    var frame: Frame = buildDemo(420, 540)
    var framePtr: *Frame = *frame
    var theme: UiTheme = demoTheme()
    var demo: Demo = Demo("click a button, or a cell", 0, 0, List<Int>(0, 0), List<Int>(0, 0), false)
    var demoPtr: *Demo = *demo
    val bound: Bool = bindDemo(framePtr)
    // The two nodes the handlers write to, found by name - the same way a listener finds its node.
    var statusNode: Int = 0 - 1
    var difficultyNode: Int = 0 - 1
    var boardNode: Int = 0 - 1
    val statusFound: *Int = framePtr.names.getPtr("status")
    if (statusFound != null) {
        statusNode = * statusFound
    }
    val difficultyFound: *Int = framePtr.names.getPtr("difficulty")
    if (difficultyFound != null) {
        difficultyNode = * difficultyFound
    }
    val boardFound: *Int = framePtr.names.getPtr("board")
    if (boardFound != null) {
        boardNode = * boardFound
    }
    println(
        "minui v0.5: font=" + fontLoaded.toString() + " sheet=" + sheetLoaded.toString()
                + " bound=" + bound.toString() + " nodes=" + framePtr.nodes.size().toString()
    )

    // The screen's answer: the action code says *what*, `h.hit`'s payload says *which*, and the
    // event says which button - the walk hands it in for exactly this reason. Answering true
    // consumes the event; false would let the walk continue to the parent.
    val answer: (Hit, *UiEvent) -> Bool = (h: Hit, e: *UiEvent) -> {
        val app: *Demo = demoPtr
        if (h.action == Act.NewGame.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                app.revealed.clear()
                app.flags.clear()
                app.clicks = app.clicks + 1
                app.status = "new game (" + app.clicks.toString() + ")"
                setText(framePtr, statusNode, app.status)
                println("  action new_game count=" + app.clicks.toString())
            }
            return true
        }
        if (h.action == Act.Difficulty.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                app.difficulty = (app.difficulty + 1) % 3
                setText(framePtr, difficultyNode, "DIFFICULTY: " + difficultyName(app.difficulty))
                println("  action difficulty level=" + app.difficulty.toString())
            }
            return true
        }
        if (h.action == Act.Quit.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                app.quit = true
                println("  action quit")
            }
            return true
        }
        if (h.action == Act.Cell.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                val cell: Int = framePtr.nodes[h.hit].payload
                if (e.button == Button.Right) {
                    listToggle(*app.flags, cell)
                    app.status = "flag " + cell.toString()
                } else if (e.buttons == MaskLeft + MaskRight) {
                    app.status = "chord " + cell.toString()
                } else {
                    listToggle(*app.revealed, cell)
                    app.status = "cell " + cell.toString()
                }
                setText(framePtr, statusNode, app.status)
                println("  action cell " + app.status)
            }
            return true
        }
        if (h.action == Act.Background.toInt()) {
            println("  action background (nobody else wanted it)")
            return true
        }
        return false
    }
    val custom: (Int) -> Unit = (node: Int) -> {
        paintCell(framePtr, demoPtr, boardNode, uiPtr, node, sheetLoaded)
    }

    // The scripted pointer, if any: through SDL's own queue, so what it exercises is the real path
    // (poll -> translate -> tree), not a shortcut into the handlers.
    if (clickX >= 0) {
        uiPushMouse(UiEventKind.MouseMove, clickX, clickY, Button.None)
        uiPushMouse(UiEventKind.MouseDown, clickX, clickY, clickButton)
        if (!holdClick) {
            uiPushMouse(UiEventKind.MouseUp, clickX, clickY, clickButton)
        }
    }

    var frames: Int = 0
    var running: Bool = true
    while (running) {
        while (uiPollEvent(*uiPtr)) {
            val e: UiEvent = ui.event
            if (e.kind == UiEventKind.Close) {
                running = false
            } else if (e.kind == UiEventKind.KeyDown && e.key == SDLK_ESCAPE) {
                running = false
            } else {
                uiEvent(framePtr, *e, answer)
            }
        }
        if (demoPtr.quit) {
            running = false
        }
        uiClear(*uiPtr, theme.background)
        paintFrame(framePtr, *theme, uiPtr, custom)
        uiPresent(*uiPtr)
        frames = frames + 1
        if (maxFrames >= 0 && frames >= maxFrames) {
            running = false
        }
    }
    println("frames drawn: " + frames.toString())
    if (screenshotPath.size() > 0) {
        println("screenshot " + screenshotPath + ": " + uiScreenshot(*uiPtr, screenshotPath).toString())
    }
    uiClose(*uiPtr)
    return 0
}
