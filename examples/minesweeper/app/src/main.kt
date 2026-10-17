package minesweeperapp

import minui
import minesweeper

// Minesweeper, played on the tree, in two screens on a stack.
//
// The rules are the `minesweeper` module's (no UI import, tested headless by `stress/minesweeper`);
// the window, the tree, the painter, the XML loader and the screen stack are minui's. The screens
// are XML resources (`_res.md`, keys `Screens:Menu` and `Screens:Result`), so they are edited as
// text in a file of their own; the board's cells are painted by the app. This file is the glue, the
// look (colours and geometry, named) and the event answer.
//
//   minesweeperdemo.exe                          the menu, until Escape or the window's X
//   minesweeperdemo.exe --difficulty 1           skip the menu: start on Intermediate (2: Expert)
//   minesweeperdemo.exe --seed 7                 a different board
//   minesweeperdemo.exe --frames 3               three frames and leave (a smoke test)
//   minesweeperdemo.exe --screenshot out.bmp     write what the renderer presented
//   minesweeperdemo.exe --script "180,136:L; r:K"  clicks and keystrokes through SDL's own queue
//                                                (x,y:L left, :R right, :M middle; r:K, esc:K,
//                                                2:K, or SDL's number: 114:K)
//
// Playing: the menu's buttons pick a difficulty (QUIT leaves); on the board a left click opens, a
// right click flags, the middle button chords, R restarts, 1/2/3 switch difficulty, and Escape (or
// MENU) goes back to the menu.

// The action codes a screen answers. minui only ever sees the `Int`s, and each code belongs to one
// screen's bindings: the menu plays and quits, the game opens cells, restarts and backs out. The
// *codes* are shared, the *bindings* are not - each screen's frame carries only its own listeners.
enum class Act {
    None = 0,
    Cell = 1,
    Restart = 2,
    Play = 3,
    Quit = 4,
    Background = 5,
    Back = 6
}

// One difficulty's geometry: the cell size and the window it needs. A board is a grid, not a box,
// so a new difficulty is new geometry - a rebuilt tree and a resized window.
data class BoardLayout(var cell: Int, var window: Size)

// The board's own frame, named: the margins around it and the strip above (counter, clock) and
// below (buttons, status) it. Every geometry below is arithmetic on these.
val Margin: Int = 12
val Header: Int = 52
val Footer: Int = 60

fun layoutFor(difficulty: Difficulty): BoardLayout {
    val board: Layout = layoutOf(difficulty)
    var cell: Int = 30
    if (difficulty == Difficulty.Expert) {
        cell = 24
    }
    return BoardLayout(
        cell, smSize(board.width * cell + Margin * 2, Header + board.height * cell + Footer)
    )
}

// The app's whole state. Both of the app's own screens live here *boxed* (`&Frame`): a difficulty
// change replaces the board's box with a fresh arena, and each push of the result panel gets its
// own (its dialog is centred for the window it is about to cover). The stack holds the same boxes -
// a push shares a screen rather than copying it, which is the point of a layer holding a reference.
data class Game(
    var frame: &Frame,
    var result: &Frame,
    var board: Board,
    var difficulty: Difficulty,
    var seed: Int,
    var startedAt: Int,
    var elapsed: Int,
    var status: Str,
    var quit: Bool,
    var boardNode: Int
)

fun freshGame(difficulty: Difficulty, seed: Int): Game {
    var placeholder: Frame = newFrame(smSize(1, 1))
    var resultPlaceholder: Frame = newFrame(smSize(1, 1))
    return Game(
        & placeholder, & resultPlaceholder, newGame(difficulty, seed), difficulty, seed, 0, 0,
    "left opens, right flags, middle chords", false, 0-1
    )
}

// The level the command line and the menu's buttons speak (0/1/2), as the rules' enum.
fun levelDifficulty(level: Int): Difficulty {
    if (level == 1) {
        return Difficulty.Intermediate
    }
    if (level == 2) {
        return Difficulty.Expert
    }
    return Difficulty.Beginner
}

// The two questions every branch of the answer asks, in one place: which action the walk resolved,
// and whether the event is a press (a release that carries no work is not worth a branch).
fun answers(h: Hit, act: Act): Bool {
    return h.action == act.toInt()
}

fun pressed(e: *UiEvent): Bool {
    return e.kind == UiEventKind.MouseDown
}

// A screen, by name, out of the app's own `_res.md`: the compiler embeds the text and this is the
// whole cost of using it. A key that is not there reads as empty, which the loader reports as
// "line 1: no element" - the same loud failure as any other bad screen.
fun screenXml(name: Str): Str {
    return Resources.get(`Screens:@(name)`).toString()
}

// ---- the look: colours by name -------------------------------------------------------------

// The game's colours, named once. The theme takes the ones the painter draws with; the board's cells
// are the app's own painting, so their colours live here too, beside the code that uses them.
val Background: Color = smColor(24, 32, 48)
val PanelFace: Color = smColor(30, 38, 52)
val PanelEdge: Color = smColor(58, 74, 100)
val CoveredFace: Color = smColor(58, 90, 138)     // a covered cell and a button's idle fill, the same blue by design
val CoveredHoverFace: Color = smColor(78, 116, 172)
val CoveredPressedFace: Color = smColor(44, 68, 104)
val ButtonDisabledFace: Color = smColor(48, 56, 70)
val TitleText: Color = smColor(240, 244, 250)
val BodyText: Color = smColor(214, 224, 236)
val AccentText: Color = smColor(143, 211, 244)
val DimText: Color = smColor(120, 136, 158)
val OpenFace: Color = smColor(30, 38, 52)         // a revealed cell
val ExplodedFace: Color = smColor(150, 50, 50)
val FlagPole: Color = smColor(200, 208, 220)
val FlagCloth: Color = smColor(226, 80, 80)
val MineBody: Color = smColor(20, 24, 32)
val MineShine: Color = smColor(200, 208, 220)
val BadFlag: Color = smColor(226, 80, 80)
val Scrim: Color = smRgba(8, 10, 16, 150)         // the modal's dimming: dark, and see-through

// The eight number colours, brightened for the dark revealed cell: one list, so the count indexes
// it. Built by appending rather than as a literal because a `listOf` holds up to four elements -
// four is `List<T>`'s inline capacity, and a longer literal emits a call the RTL does not have
// (minui.md §11). Index 0 is never drawn (a cell with no neighbours shows nothing).
fun numberColors(): List<Color> {
    var colors: List<Color> = List<Color>(0)
    colors.append(smColor(110, 160, 255))
    colors.append(smColor(110, 220, 140))
    colors.append(smColor(255, 120, 120))
    colors.append(smColor(160, 150, 255))
    colors.append(smColor(255, 150, 150))
    colors.append(smColor(110, 220, 220))
    colors.append(smColor(235, 235, 235))
    colors.append(smColor(170, 180, 200))
    return colors
}

val NumberColors: List<Color> = numberColors()

// The theme: one value, so the palette is one thing to keep in step.
fun gameTheme(): UiTheme {
    return UiTheme(
        Background, PanelFace, PanelEdge,
        CoveredFace, CoveredHoverFace, CoveredPressedFace, ButtonDisabledFace,
        TitleText, BodyText, AccentText, DimText
    )
}

fun numberColor(count: Int): Color {
    if (count >= 1 && count <= 8) {
        return NumberColors[count - 1]
    }
    return BodyText
}

// The board's tree: the two readouts, the bottom row (back to the menu, a fresh board), the status
// line, and the cells (one `Custom` node each, `payload` = the cell index, one listener on the grid
// for all of them). Rebuilt whole - a difficulty is new geometry.
fun buildGameScreen(g: *Game, ui: *Ui): Frame {
    val layout: BoardLayout = layoutFor(g.difficulty)
    val board: Layout = layoutOf(g.difficulty)
    val window: Size = layout.window
    val boardWidth: Int = board.width * layout.cell
    val footer: Int = Header + board.height * layout.cell

    var f: Frame = newFrame(window)
    var fp: *Frame = *f

    val counter: Int = addNode(fp, fp.root, NodeKind.Label, Margin, 10, 72, 32)
    setName(fp, counter, "counter")
    setText(fp, counter, "000")
    setPalette(fp, counter, Palette.Emphasized)

    val timer: Int = addNode(fp, fp.root, NodeKind.Label, window.width - Margin - 72, 10, 72, 32)
    setName(fp, timer, "timer")
    setText(fp, timer, "0")
    setPalette(fp, timer, Palette.Emphasized)
    setAlign(fp, timer, Align.Right)

    // The board: one named grid, its cells anonymous with `payload` = row-major index.
    addGrid(fp, fp.root, "board", Margin, Header, layout.cell, board.width, board.height)

    // The bottom row: back to the menu (the stack pops), or a fresh board on this difficulty.
    val menu: Int = addNode(fp, fp.root, NodeKind.Button, Margin, footer + 8, 100, 30)
    setName(fp, menu, "menu")
    setText(fp, menu, "MENU")
    val restart: Int = addNode(
        fp, fp.root, NodeKind.Button, window.width - Margin - 100, footer + 8, 100, 30
    )
    setName(fp, restart, "restart")
    setText(fp, restart, "NEW GAME")

    val status: Int = addNode(fp, fp.root, NodeKind.Label, Margin, footer + 42, boardWidth, 18)
    setName(fp, status, "status")
    setText(fp, status, g.status)
    setPalette(fp, status, Palette.Dim)

    g.boardNode = nodeNamed(fp, "board")
    return f
}

// The game screen's bindings: name -> action, resolved once per build. The Bool each call answers
// is the check that the name is in the tree (a typo shows up here, not as a dead button).
fun bindGame(fp: *Frame): Bool {
    var bound: Bool = true
    if (!frameOn(fp, "board", Act.Cell.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "restart", Act.Restart.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "menu", Act.Back.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "root", Act.Background.toInt(), 0)) {
        bound = false
    }
    return bound
}

// The menu's bindings. Its frame comes from the loader, which renamed its root from "root" to the
// screen's own name - so a click on the background is bound as "menu" here, not "root".
fun menuBind(fp: *Frame): Bool {
    var bound: Bool = true
    if (!frameOn(fp, "beginner", Act.Play.toInt(), Difficulty.Beginner.toInt())) {
        bound = false
    }
    if (!frameOn(fp, "medium", Act.Play.toInt(), Difficulty.Intermediate.toInt())) {
        bound = false
    }
    if (!frameOn(fp, "expert", Act.Play.toInt(), Difficulty.Expert.toInt())) {
        bound = false
    }
    if (!frameOn(fp, "quit", Act.Quit.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "menu", Act.Background.toInt(), 0)) {
        bound = false
    }
    return bound
}

// One cell: the painter's `custom` hook. The arena carries the cell's geometry and identity; the
// rules say what is under it; the pointer's place and the frame's `pressed` say how it looks.
fun paintCell(g: *Game, f: *Frame, ui: *Ui, node: Int): Unit {
    if (node == g.boardNode) {
        return // the grid itself draws nothing; its cells do
    }
    val rect: Rect = f.nodes[node].rect
    val index: Int = f.nodes[node].payload
    val cell: Cell = g.board.cells[index]
    val inside: Bool = ui.mouseX >= rect.x && ui.mouseX < rect.x + rect.width
            && ui.mouseY >= rect.y && ui.mouseY < rect.y + rect.height
    val open: Bool = cell.state == CellState.Revealed || cell.state == CellState.Exploded
    if (!open && cell.state != CellState.Flagged && cell.state != CellState.WrongFlag) {
        // covered: the face the pointer reacts to
        var face: Color = CoveredFace
        if (inside && f.pressed == g.boardNode) {
            face = CoveredPressedFace
        } else if (inside) {
            face = CoveredHoverFace
        }
        uiRoundFill(ui, smRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2), 4, face)
        return
    }
    // open (or flagged): the flatter revealed face
    var face: Color = OpenFace
    if (cell.state == CellState.Exploded) {
        face = ExplodedFace
    }
    uiRoundFill(ui, smRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2), 4, face)
    if (cell.state == CellState.Flagged) {
        // a flag: a pole and a cloth - the player's mark, kept on a loss and on a win
        uiLine(ui, rect.x + 14, rect.y + 8, rect.x + 14, rect.y + 22, FlagPole)
        uiPie(ui, smRect(rect.x + 13, rect.y + 6, 14, 12), 0 - 90, 180, FlagCloth)
        return
    }
    if (cell.state == CellState.Revealed && cell.mine) {
        // a mine: a body with four spikes
        val center: Int = rect.x + rect.width / 2
        val middle: Int = rect.y + rect.height / 2
        uiLine(ui, center - 9, middle, center + 9, middle, MineBody)
        uiLine(ui, center, middle - 9, center, middle + 9, MineBody)
        uiCircle(ui, center, middle, 7, MineBody)
        uiCircle(ui, center - 2, middle - 2, 2, MineShine)
        return
    }
    if (cell.state == CellState.WrongFlag) {
        uiLine(
            ui, rect.x + 6, rect.y + 6, rect.x + rect.width - 6, rect.y + rect.height - 6,
            BadFlag
        )
        uiLine(
            ui, rect.x + rect.width - 6, rect.y + 6, rect.x + 6, rect.y + rect.height - 6,
            BadFlag
        )
        return
    }
    if (cell.state == CellState.Revealed && cell.neighbors > 0) {
        uiTextBox(ui, rect, (cell.neighbors).toString(), numberColor(cell.neighbors), true)
    }
}

// Push a fresh game screen on top of the menu: a new board for `difficulty`, a rebuilt arena, the
// window sized to it. A restart and a difficulty change are the same operation (both land here),
// and the stack never grows a second game screen: whatever game screen was there is popped first,
// and its arena goes with it. `seed` is kept, so a restart is the same board again.
fun showGame(g: *Game, stack: *ScreenStack, ui: *Ui, difficulty: Difficulty): Unit {
    g.difficulty = difficulty
    g.board = newGame(difficulty, g.seed)
    g.startedAt = 0
    g.elapsed = 0
    val name: Str = difficultyName(difficulty)
    g.status = `@(name): left opens, right flags`
    var frame: Frame = buildGameScreen(g, ui)
    var fp: *Frame = *frame
    bindGame(fp)
    g.frame = & frame
            while (stackSize(stack) > 1) {
                stackPop(stack)
            }
    val box: &Frame = g.frame
    stackPush(stack, box, "game", true)
    stackResizeTop(stack, ui)
}

// The result panel's bindings: play the same board again, or back to the menu. A click on the
// scrim (or anything else on the panel) is consumed as background: a modal that dismissed itself
// on a stray click would not be modal.
fun bindResult(fp: *Frame): Bool {
    var bound: Bool = true
    if (!frameOn(fp, "again", Act.Restart.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "to_menu", Act.Back.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "result", Act.Background.toInt(), 0)) {
        bound = false
    }
    return bound
}

// Push the result panel over the board: a *modal* layer, and the stack needs no flag for that -
// input only ever reaches the top layer, so any top layer is modal. What keeps the board visible
// (and dimmed) is `opaque = false`: the painter starts at the last opaque layer, so the game screen
// below is painted first and the scrim is painted over it.
//
// The panel is loaded fresh for each push: its nominal 260x180 geometry is written in XML, and the
// window it has to fit (`stackResizeTop`'s size, the game frame's root rect) is only known here.
// The scrim is stretched to that window and the dialog subtree is moved to its middle - the same
// `setRect`/`moveNode` pair the tree case pins.
fun showResult(g: *Game, stack: *ScreenStack, ui: *Ui, headline: Str, detail: Str): Unit {
    val loaded: Res<Frame> = loadScreen(screenXml("Result"))
    if (!loaded.isOk()) {
        println("result screen: " + loaded.error())
        return
    }
    val gameBox: &Frame = g.frame
    val gameFrame: *Frame = *gameBox
    val window: Rect = gameFrame.nodes[gameFrame.root].rect
    var frame: Frame = loaded.value()
    var fp: *Frame = *frame
    setRect(fp, fp.root, window)
    setRect(fp, nodeNamed(fp, "scrim"), window)
    val dialog: Int = nodeNamed(fp, "dialog")
    val box: Rect = fp.nodes[dialog].rect
    moveNode(fp, dialog, (window.width - box.width) / 2, (window.height - box.height) / 2)
    setTextNamed(fp, "headline", headline)
    setTextNamed(fp, "detail", detail)
    if (!bindResult(fp)) {
        println("result screen: one of the bound names is not in the tree")
    }
    g.result = & frame
    val resultBox: &Frame = g.result
    stackPush(stack, resultBox, "result", false)
    stackResizeTop(stack, ui)
}

// Three digits, so the counter does not jump as the width changes.
fun counterText(value: Int): Str {
    if (value < 0) {
        val magnitude: Int = 0 - value
        return `-@(magnitude)`
    }
    if (value < 10) {
        return `00@(value)`
    }
    if (value < 100) {
        return `0@(value)`
    }
    return value.toString()
}

// The key a `:K` script entry names: the words this app knows, or SDL's own number. `keysym.sym`
// is the ASCII value for a letter or a digit, so the app's own constants (`SDLK_r`, `SDLK_ESCAPE`)
// are what a script writes down - `r:K` and `114:K` are the same keystroke.
fun scriptKey(name: Str): Opt<Int> {
    if (name == "esc") {
        return Opt<Int>.some(SDLK_ESCAPE)
    }
    if (name == "r") {
        return Opt<Int>.some(SDLK_r)
    }
    if (name == "1") {
        return Opt<Int>.some(SDLK_1)
    }
    if (name == "2") {
        return Opt<Int>.some(SDLK_2)
    }
    if (name == "3") {
        return Opt<Int>.some(SDLK_3)
    }
    return name.toInt()
}

// The scripted input: "x,y:L; x,y:R; x,y:M" for clicks (left, right, middle/chord) and "r:K" for a
// keystroke. Each one is pushed through SDL's own queue, so a script drives the real path: poll,
// translate, tree, rules - and the keys the real path handles are reachable from a script too.
fun runScript(script: Str): Int {
    var pushed: Int = 0
    if (script.size() == 0) {
        return 0
    }
    val entries: List<Str> = script.split(";")
    var i: Int = 0
    while (i < entries.size()) {
        val entry: Str = entries[i].trim()
        val colon: Int = entry.find(":")
        if (colon > 0 && entry.substr(colon + 1, 1) == "K") {
            val key: Opt<Int> = scriptKey(entry.substr(0, colon).trim())
            if (key.hasValue()) {
                uiPushKey(UiEventKind.KeyDown, key.value())
                uiPushKey(UiEventKind.KeyUp, key.value())
                pushed = pushed + 1
            }
            i = i + 1
            continue
        }
        val parts: List<Str> = entry.split(",")
        if (parts.size() == 2) {
            val pointX: Opt<Int> = parts[0].toInt()
            val yAndFlag: Str = parts[1]
            var yText: Str = yAndFlag
            var button: Button = Button.Left
            val click: Int = yAndFlag.find(":")
            if (click > 0) {
                yText = yAndFlag.substr(0, click)
                val flag: Str = yAndFlag.substr(click + 1, 1)
                if (flag == "R") {
                    button = Button.Right
                }
                if (flag == "M") {
                    button = Button.Middle
                }
            }
            val pointY: Opt<Int> = yText.toInt()
            if (pointX.hasValue() && pointY.hasValue()) {
                val x: Int = pointX.value()
                val y: Int = pointY.value()
                uiPushMouse(UiEventKind.MouseMove, x, y, Button.None)
                uiPushMouse(UiEventKind.MouseDown, x, y, button)
                uiPushMouse(UiEventKind.MouseUp, x, y, button)
                pushed = pushed + 1
            }
        }
        i = i + 1
    }
    return pushed
}

fun main(args: List<Str>): Int {
    var maxFrames: Int = 0 - 1
    var screenshotPath: Str = ""
    var script: Str = ""
    var seed: Int = 12345
    var level: Int = 0
    // `--difficulty 0` and "no flag at all" would otherwise be the same thing (0 is Beginner),
    // and they mean different things: the flag wants a board on screen at startup, its absence
    // wants the menu. So the flag is *set*, and the menu is what an empty command line shows.
    var startOnBoard: Bool = false
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
        } else if (args[index] == "--script" && index + 1 < args.size()) {
            script = args[index + 1]
            index = index + 1
        } else if (args[index] == "--seed" && index + 1 < args.size()) {
            val parsed: Opt<Int> = args[index + 1].toInt()
            if (parsed.hasValue()) {
                seed = parsed.value()
            }
            index = index + 1
        } else if (args[index] == "--difficulty" && index + 1 < args.size()) {
            val parsed: Opt<Int> = args[index + 1].toInt()
            if (parsed.hasValue() && parsed.value() >= 0 && parsed.value() <= 2) {
                level = parsed.value()
                startOnBoard = true
            }
            index = index + 1
        }
        index = index + 1
    }

    var ui: Ui = uiOpen("Minesweeper - minui (SDL2 + Skia)", smSize(400, 400), false, true)
    if (!uiIsOpen(*ui)) {
        println("window open failed: " + uiFailed())
        return 1
    }
    var uiPtr: *Ui = *ui
    if (!uiLoadFont(*ui, "Segoe UI", 15, 20)) {
        println("font load failed")
        uiClose(*ui)
        return 1
    }
    var game: Game = freshGame(Difficulty.Beginner, seed)
    var gamePtr: *Game = *game

    // The menu, out of `_res.md` and into the arena. A screen that does not load is a message with a
    // line in it, and nothing else happens: no half-built window, no clickable nothing.
    val menuScreen: Res<Frame> = loadScreen(screenXml("Menu"))
    if (!menuScreen.isOk()) {
        println("menu screen: " + menuScreen.error())
        uiClose(*ui)
        return 1
    }
    var menuValue: Frame = menuScreen.value()
    var menuBox: &Frame = &menuValue
    var menuPtr: *Frame = *menuBox
    if (!menuBind(menuPtr)) {
        println("menu: one of the bound names is not in the tree")
        uiClose(*ui)
        return 1
    }
    var stack: ScreenStack = newStack(menuBox, "menu", true)
    var stackPtr: *ScreenStack = *stack

    // `--difficulty`: skip the menu's own click and push a game screen at startup. The menu stays
    // below it, so Escape from the board still lands on the menu.
    if (startOnBoard) {
        showGame(gamePtr, stackPtr, uiPtr, levelDifficulty(level))
    }
    stackResizeTop(stackPtr, uiPtr)

    var theme: UiTheme = gameTheme()
    var themePtr: *UiTheme = *theme
    val topFrame: *Frame = stackTop(stackPtr)
    val topRect: Rect = topFrame.nodes[topFrame.root].rect
    println(
        "minesweeper: seed=" + seed.toString() + " screen=" + stackTopName(stackPtr)
                + " nodes=" + topFrame.nodes.size().toString()
                + " size=" + topRect.width.toString() + "x" + topRect.height.toString()
    )

    // The screens' answer. The action says *what*, the event says which button, the leaf's payload
    // says which cell - and the rules module does the rest. One closure serves both screens because
    // the codes are distinct per screen: only the game binds Cell/Restart/Back, only the menu binds
    // Play/Quit, and the walk only ever visits the top screen's listeners.
    val answer: (Hit, *UiEvent) -> Bool = (h: Hit, e: *UiEvent) -> {
        val g: *Game = gamePtr
        if (answers(h, Act.Cell)) {
            // The one action that has work on both a press and a release: the click is the press.
            if (pressed(e)) {
                val box: &Frame = g.frame
                val fp: *Frame = *box
                val cellIndex: Int = fp.nodes[h.hit].payload
                val x: Int = cellIndex % g.board.width
                val y: Int = cellIndex / g.board.width
                if (e.buttons == MaskLeft + MaskRight || e.button == Button.Middle) {
                    chord(*g.board, x, y)
                    g.status = `chord @(x),@(y)`
                } else if (e.button == Button.Right) {
                    toggleFlag(*g.board, x, y)
                    g.status = `flag @(x),@(y)`
                } else {
                    reveal(*g.board, x, y)
                    if (g.startedAt == 0) {
                        g.startedAt = uiTicks()
                    }
                    g.status = `open @(x),@(y)`
                }
                val seconds: Int = g.elapsed
                if (g.board.status == GameStatus.Lost) {
                    g.status = "boom - press NEW GAME (or R)"
                }
                if (g.board.status == GameStatus.Won) {
                    g.status = `you won in @(seconds)s`
                }
                setTextNamed(fp, "status", g.status)
                val action: Int = h.action
                val status: Str = g.status
                println(`  action @(action) @(status)`)
                // A finished board gets its result panel - the modal layer - unless one is already
                // up: the panel is the top screen then, and a further click on a dead board has
                // nothing to add (and would push a second copy).
                if (g.board.status == GameStatus.Won || g.board.status == GameStatus.Lost) {
                    if (stackTopName(stackPtr) == "game") {
                        val mines: Int = g.board.mineCount
                        if (g.board.status == GameStatus.Won) {
                            showResult(
                                g, stackPtr, uiPtr, "YOU WON", `@(mines) mines in @(seconds)s`
                            )
                        } else {
                            showResult(g, stackPtr, uiPtr, "BOOM", `you hit a mine, of @(mines)`)
                        }
                    }
                }
            }
            return true
        }
        if (answers(h, Act.Restart) && pressed(e)) {
            val d: Difficulty = g.difficulty
            showGame(g, stackPtr, uiPtr, d)
            println("  action restart")
            return true
        }
        if (answers(h, Act.Play) && pressed(e)) {
            val level: Int = h.payload
            showGame(g, stackPtr, uiPtr, levelDifficulty(level))
            println(`  menu play level=@(level)`)
            return true
        }
        if (answers(h, Act.Back) && pressed(e)) {
            while (stackSize(stackPtr) > 1) {
                stackPop(stackPtr)
            }
            stackResizeTop(stackPtr, uiPtr)
            println("  back to the menu")
            return true
        }
        if (answers(h, Act.Quit) && pressed(e)) {
            g.quit = true
            return true
        }
        if (answers(h, Act.Background)) {
            return true
        }
        return false
    }
    val custom: (Str, Int) -> Unit = (screen: Str, node: Int) -> {
        if (screen == "game") {
            val box: &Frame = gamePtr.frame
            paintCell(gamePtr, *box, uiPtr, node)
        }
        if (screen == "result") {
            // The scrim: dim what is below. This is the one thing only the app can paint (the
            // panel's own chrome is the painter's), and its rect is the window - the loader wrote
            // the node, `showResult` stretched it.
            val box: &Frame = gamePtr.result
            val f: *Frame = *box
            val r: Rect = f.nodes[node].rect
            uiFill(uiPtr, r, Scrim)
        }
    }

    val pushed: Int = runScript(script)
    if (pushed > 0) {
        println("scripted clicks: " + pushed.toString())
    }

    var frames: Int = 0
    var running: Bool = true
    var dirty: Bool = true
    var shownSeconds: Int = 0 - 1
    while (running) {
        var hadEvent: Bool = false
        while (uiPollEvent(*uiPtr)) {
            hadEvent = true
            val e: UiEvent = ui.event
            if (e.kind == UiEventKind.Close) {
                running = false
            } else if (e.kind == UiEventKind.KeyDown) {
                // Keys reach the tree first: a focused edit consumes the printable ones and walks
                // its listener on Return. Whatever comes back unconsumed (`h.hit < 0` - no edit
                // took it) is the app's own shortcut business below.
                val keyHit: Hit = stackEvent(*stackPtr, *e, answer)
                if (keyHit.hit < 0) {
                    if (e.key == SDLK_ESCAPE) {
                        // Back out of a screen; on the bottom layer (the menu) the window closes.
                        if (stackSize(*stackPtr) > 1) {
                            stackPop(*stackPtr)
                            stackResizeTop(*stackPtr, uiPtr)
                            println("  key escape: back to the menu")
                        } else {
                            running = false
                        }
                    } else if (e.key == SDLK_r) {
                        // R restarts the game whether or not its result panel is up (the panel is
                        // a layer over the same game); on the menu there is no game to restart.
                        if (stackTopName(*stackPtr) != "menu") {
                            val d: Difficulty = gamePtr.difficulty
                            showGame(gamePtr, *stackPtr, uiPtr, d)
                            println("  key restart")
                        }
                    } else if (e.key == SDLK_1) {
                        if (stackTopName(*stackPtr) != "menu") {
                            showGame(gamePtr, *stackPtr, uiPtr, Difficulty.Beginner)
                        }
                    } else if (e.key == SDLK_2) {
                        if (stackTopName(*stackPtr) != "menu") {
                            showGame(gamePtr, *stackPtr, uiPtr, Difficulty.Intermediate)
                        }
                    } else if (e.key == SDLK_3) {
                        if (stackTopName(*stackPtr) != "menu") {
                            showGame(gamePtr, *stackPtr, uiPtr, Difficulty.Expert)
                        }
                    }
                }
            } else {
                stackEvent(*stackPtr, *e, answer)
            }
        }
        if (hadEvent) {
            dirty = true
        }
        if (gamePtr.quit) {
            running = false
        }
        // The clock runs only while the game does: a win or a loss freezes the reading.
        if (gamePtr.startedAt > 0 && gamePtr.board.status == GameStatus.Playing) {
            gamePtr.elapsed = (uiTicks() - gamePtr.startedAt) / 1000
        }
        if (gamePtr.elapsed != shownSeconds) {
            shownSeconds = gamePtr.elapsed
            dirty = true
        }
        // A frame is drawn when something changed: an event (a click, the pointer moving over a
        // cell) or the clock moving a second on. `--frames` draws every frame instead, so a
        // screenshot is the state after exactly N frames.
        if (maxFrames >= 0) {
            dirty = true
        }
        if (dirty) {
            // The readouts only exist while the game screen does; `setTextNamed` on a name that is
            // not in a frame is quiet by design, so a menu frame costs nothing here.
            val box: &Frame = gamePtr.frame
            setTextNamed(*box, "counter", counterText(minesRemaining(*gamePtr.board)))
            setTextNamed(*box, "timer", gamePtr.elapsed.toString())
            uiClear(*uiPtr, theme.background)
            stackPaint(*stackPtr, *themePtr, uiPtr, custom)
            uiPresent(*uiPtr)
            frames = frames + 1
            dirty = false
            if (maxFrames >= 0 && frames >= maxFrames) {
                running = false
            }
        } else {
            uiDelay(4)
        }
    }
    println("frames drawn: " + frames.toString())
    if (screenshotPath.size() > 0) {
        println(
            "screenshot " + screenshotPath + ": "
                    + uiScreenshot(*uiPtr, screenshotPath).toString()
        )
    }
    uiClose(*uiPtr)
    return 0
}
