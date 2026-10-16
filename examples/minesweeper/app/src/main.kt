package minesweeperapp

import minui
import minesweeper

// Minesweeper, played on the tree.
//
// The rules are the `minesweeper` module's (no UI import, tested headless by `stress/minesweeper`);
// the window, the tree and the painter are minui's. This file is the glue and the look: a counter,
// a timer, a restart button, three difficulty buttons, the board, and a status line that says what
// happened.
//
//   minesweeperdemo.exe                        play (Beginner, seeded, until Escape)
//   minesweeperdemo.exe --difficulty 1         start on Intermediate (2 is Expert)
//   minesweeperdemo.exe --seed 7                a different board
//   minesweeperdemo.exe --frames 3             three frames and leave (a smoke test)
//   minesweeperdemo.exe --screenshot out.bmp   write what the renderer presented
//   minesweeperdemo.exe --script "4,4:L; 0,0:L"  clicks through SDL's own queue, in order
//                                              (:L left, :R right, :M middle/chord)
//
// Playing: left click opens, right click flags, middle (or both buttons held) chords, R restarts,
// 1/2/3 switch difficulty, Escape leaves.

// The action codes this screen answers. minui only ever sees the `Int`s.
enum class Act {
    None = 0,
    Cell = 1,
    Restart = 2,
    Difficulty = 3,
    Quit = 4,
    Background = 5
}

// One difficulty's geometry: the cell size and the window it needs. There is no layout engine in
// minui v1, so a new difficulty is new geometry - a rebuilt tree and a resized window.
data class BoardLayout(var cell: Int, var width: Int, var height: Int)

fun layoutFor(difficulty: Difficulty): BoardLayout {
    val board: Layout = layoutOf(difficulty)
    var cell: Int = 30
    if (difficulty == Difficulty.Expert) {
        cell = 24
    }
    val margin: Int = 12
    val header: Int = 52
    val footer: Int = 60
    return BoardLayout(
        cell, board.width * cell + margin * 2,
        header + board.height * cell + footer
    )
}

// The app's whole state. The frame lives here because a difficulty change rebuilds it: the arena is
// data and a new difficulty is new data (names survive it; indices do not, which is why the
// handlers look their nodes up by name).
data class Game(
    var frame: Frame,
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
    return Game(
        newFrame(1, 1), newGame(difficulty, seed), difficulty, seed, 0, 0,
        "left click opens, right click flags, middle chords", false, 0 - 1
    )
}

// The theme: one value, so the palette is one thing to keep in step.
fun gameTheme(): UiTheme {
    return UiTheme(
        uiRgb(24, 32, 48), uiRgb(30, 38, 52), uiRgb(58, 74, 100),
        uiRgb(58, 90, 138), uiRgb(78, 116, 172), uiRgb(44, 68, 104), uiRgb(48, 56, 70),
        uiRgb(240, 244, 250), uiRgb(214, 224, 236), uiRgb(143, 211, 244), uiRgb(120, 136, 158)
    )
}

// The classic eight number colours, brightened for the dark revealed cell.
fun numberColor(count: Int): Int {
    if (count == 1) {
        return uiRgb(110, 160, 255)
    }
    if (count == 2) {
        return uiRgb(110, 220, 140)
    }
    if (count == 3) {
        return uiRgb(255, 120, 120)
    }
    if (count == 4) {
        return uiRgb(160, 150, 255)
    }
    if (count == 5) {
        return uiRgb(255, 150, 150)
    }
    if (count == 6) {
        return uiRgb(110, 220, 220)
    }
    if (count == 7) {
        return uiRgb(235, 235, 235)
    }
    return uiRgb(170, 180, 200)
}

// The board's tree: mostly a rectangle of named buttons, and the cells (one `Custom` node each,
// `payload` = the cell index, one listener on the grid for all of them).
fun buildGame(g: *Game, ui: *Ui): Bool {
    val size: BoardLayout = layoutFor(g.difficulty)
    val board: Layout = layoutOf(g.difficulty)
    val boardWidth: Int = board.width * size.cell
    val margin: Int = 12
    val footer: Int = 52 + board.height * size.cell

    if (!uiResize(ui, size.width, size.height)) {
        return false
    }
    var f: Frame = newFrame(size.width, size.height)
    var fp: *Frame = *f

    val counter: Int = addNode(fp, fp.root, NodeKind.Label, margin, 10, 90, 32)
    setName(fp, counter, "counter")
    setText(fp, counter, "000")
    setPalette(fp, counter, PaletteEmphasized)

    val timer: Int = addNode(fp, fp.root, NodeKind.Label, size.width - margin - 90, 10, 90, 32)
    setName(fp, timer, "timer")
    setText(fp, timer, "0")
    setPalette(fp, timer, PaletteEmphasized)
    setAlign(fp, timer, Align.Right)

    val restart: Int = addNode(fp, fp.root, NodeKind.Button, (size.width - 168) / 2, 8, 168, 36)
    setName(fp, restart, "restart")
    setText(fp, restart, "NEW GAME  (R)")

    // The board: one named grid, its cells anonymous with `payload` = row-major index.
    addGrid(fp, fp.root, "board", margin, 52, size.cell, board.width, board.height)

    // Three difficulty buttons, the current one disabled (it is the one in play).
    // `g.difficulty.toInt()` would address a member of a pointer (the trap `minui.md` records), so
    // the level is read into a local first.
    val currentDifficulty: Difficulty = g.difficulty
    val currentLevel: Int = currentDifficulty.toInt()
    val buttonWidth: Int = (boardWidth - 16) / 3
    var level: Int = 0
    while (level < 3) {
        val name: Str = "diff" + level.toString()
        val button: Int = addNode(
            fp, fp.root, NodeKind.Button,
            margin + level * (buttonWidth + 8), footer + 8, buttonWidth, 30
        )
        setName(fp, button, name)
        var label: Str = "BEGINNER"
        if (level == 1) {
            label = "MEDIUM"
        }
        if (level == 2) {
            label = "EXPERT"
        }
        setText(fp, button, label)
        setEnabled(fp, button, level != currentLevel)
        level = level + 1
    }

    val status: Int = addNode(fp, fp.root, NodeKind.Label, margin, footer + 42, boardWidth, 18)
    setName(fp, status, "status")
    setText(fp, status, g.status)
    setPalette(fp, status, PaletteDim)

    g.frame = f
    g.boardNode = nodeNamed(*g.frame, "board")
    return bindGame(*g.frame)
}

// The bindings: name -> action, resolved once per build. The Bool each call answers is the check
// that the name is in the tree (a typo shows up here, not as a dead button).
fun bindGame(fp: *Frame): Bool {
    var bound: Bool = true
    if (!frameOn(fp, "board", Act.Cell.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "restart", Act.Restart.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "diff0", Act.Difficulty.toInt(), 0)) {
        bound = false
    }
    if (!frameOn(fp, "diff1", Act.Difficulty.toInt(), 1)) {
        bound = false
    }
    if (!frameOn(fp, "diff2", Act.Difficulty.toInt(), 2)) {
        bound = false
    }
    if (!frameOn(fp, "root", Act.Background.toInt(), 0)) {
        bound = false
    }
    return bound
}

// One cell: the painter's `custom` hook. The arena carries the cell's geometry and identity; the
// rules say what is under it; the pointer's place and the frame's `pressed` say how it looks.
fun paintCell(g: *Game, ui: *Ui, node: Int): Unit {
    if (node == g.boardNode) {
        return // the grid itself draws nothing; its cells do
    }
    val f: *Frame = *g.frame
    val rect: Rect = f.nodes[node].rect
    val index: Int = f.nodes[node].payload
    val cell: Cell = g.board.cells[index]
    val inside: Bool = ui.mouseX >= rect.x && ui.mouseX < rect.x + rect.width
            && ui.mouseY >= rect.y && ui.mouseY < rect.y + rect.height
    val open: Bool = cell.state == CellState.Revealed || cell.state == CellState.Exploded
    if (!open && cell.state != CellState.Flagged && cell.state != CellState.WrongFlag) {
        // covered: the face the pointer reacts to
        var face: Int = uiRgb(58, 90, 138)
        if (inside && f.pressed == g.boardNode) {
            face = uiRgb(44, 68, 104)
        } else if (inside) {
            face = uiRgb(78, 116, 172)
        }
        uiRoundFill(ui, smRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2), 4, face)
        return
    }
    // open (or flagged): the flatter revealed face
    var face: Int = uiRgb(30, 38, 52)
    if (cell.state == CellState.Exploded) {
        face = uiRgb(150, 50, 50)
    }
    uiRoundFill(ui, smRect(rect.x + 1, rect.y + 1, rect.width - 2, rect.height - 2), 4, face)
    if (cell.state == CellState.Flagged) {
        // a flag: a pole and a cloth - the player's mark, kept on a loss and on a win
        uiLine(ui, rect.x + 14, rect.y + 8, rect.x + 14, rect.y + 22, uiRgb(200, 208, 220))
        uiPie(ui, smRect(rect.x + 13, rect.y + 6, 14, 12), 0 - 90, 180, uiRgb(226, 80, 80))
        return
    }
    if (cell.state == CellState.Revealed && cell.mine) {
        // a mine: a body with four spikes
        val center: Int = rect.x + rect.width / 2
        val middle: Int = rect.y + rect.height / 2
        uiLine(ui, center - 9, middle, center + 9, middle, uiRgb(20, 24, 32))
        uiLine(ui, center, middle - 9, center, middle + 9, uiRgb(20, 24, 32))
        uiCircle(ui, center, middle, 7, uiRgb(20, 24, 32))
        uiCircle(ui, center - 2, middle - 2, 2, uiRgb(200, 208, 220))
        return
    }
    if (cell.state == CellState.WrongFlag) {
        uiLine(
            ui, rect.x + 6, rect.y + 6, rect.x + rect.width - 6, rect.y + rect.height - 6,
            uiRgb(226, 80, 80)
        )
        uiLine(
            ui, rect.x + rect.width - 6, rect.y + 6, rect.x + 6, rect.y + rect.height - 6,
            uiRgb(226, 80, 80)
        )
        return
    }
    if (cell.state == CellState.Revealed && cell.neighbors > 0) {
        uiTextBox(ui, rect, (cell.neighbors).toString(), numberColor(cell.neighbors), true)
    }
}

// A new board on the same difficulty, and the tree rebuilt after it.
fun restartGame(g: *Game, ui: *Ui, seed: Int): Unit {
    g.seed = seed
    g.board = newGame(g.difficulty, seed)
    g.startedAt = 0
    g.elapsed = 0
    g.status = "left click opens, right click flags, middle chords"
    buildGame(g, ui)
}

// A different difficulty: a new board, a new window size, a rebuilt tree.
fun switchDifficulty(g: *Game, ui: *Ui, level: Int): Unit {
    var difficulty: Difficulty = Difficulty.Beginner
    if (level == 1) {
        difficulty = Difficulty.Intermediate
    }
    if (level == 2) {
        difficulty = Difficulty.Expert
    }
    g.difficulty = difficulty
    g.board = newGame(difficulty, g.seed)
    g.startedAt = 0
    g.elapsed = 0
    g.status = difficultyName(difficulty) + ": left click opens, right click flags"
    buildGame(g, ui)
}

// Three digits, so the counter does not jump as the width changes.
fun counterText(value: Int): Str {
    if (value < 0) {
        return "-" + (0 - value).toString()
    }
    if (value < 10) {
        return "00" + value.toString()
    }
    if (value < 100) {
        return "0" + value.toString()
    }
    return value.toString()
}

// The scripted clicks: "x,y:L; x,y:R; x,y:M" - left, right, middle (chord). Each one is pushed
// through SDL's own queue, so a script drives the real path: poll, translate, tree, rules.
fun runScript(script: Str): Int {
    var pushed: Int = 0
    if (script.size() == 0) {
        return 0
    }
    val entries: List<Str> = script.split(";")
    var i: Int = 0
    while (i < entries.size()) {
        val entry: Str = entries[i].trim()
        val parts: List<Str> = entry.split(",")
        if (parts.size() == 2) {
            val pointX: Opt<Int> = parts[0].toInt()
            val yAndFlag: Str = parts[1]
            var yText: Str = yAndFlag
            var button: Button = Button.Left
            val colon: Int = yAndFlag.find(":")
            if (colon > 0) {
                yText = yAndFlag.substr(0, colon)
                val flag: Str = yAndFlag.substr(colon + 1, 1)
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
            }
            index = index + 1
        }
        index = index + 1
    }

    var ui: Ui = uiOpen("Minesweeper - minui (SDL2 + Skia)", 400, 400, false, true)
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
    if (level != 0) {
        game.difficulty = Difficulty.Intermediate
        if (level == 2) {
            game.difficulty = Difficulty.Expert
        }
        game.board = newGame(game.difficulty, seed)
        game.status = difficultyName(game.difficulty) + ": left click opens, right click flags"
    }
    var theme: UiTheme = gameTheme()
    val built: Bool = buildGame(gamePtr, uiPtr)
    val startedDifficulty: Difficulty = game.difficulty
    val startedLevel: Int = startedDifficulty.toInt()
    println(
        "minesweeper: difficulty=" + startedLevel.toString()
                + " seed=" + seed.toString() + " built=" + built.toString()
                + " nodes=" + gamePtr.frame.nodes.size().toString()
                + " size=" + gamePtr.frame.nodes[0].rect.width.toString()
                + "x" + gamePtr.frame.nodes[0].rect.height.toString()
    )

    // The screen's answer. The action says *what*, the event says which button, the leaf's payload
    // says which cell - and the rules module does the rest.
    val answer: (Hit, *UiEvent) -> Bool = (h: Hit, e: *UiEvent) -> {
        val g: *Game = gamePtr
        if (h.action == Act.Cell.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                val cellIndex: Int = g.frame.nodes[h.hit].payload
                val x: Int = cellIndex % g.board.width
                val y: Int = cellIndex / g.board.width
                if (e.buttons == MaskLeft + MaskRight || e.button == Button.Middle) {
                    chord(*g.board, x, y)
                    g.status = "chord " + x.toString() + "," + y.toString()
                } else if (e.button == Button.Right) {
                    toggleFlag(*g.board, x, y)
                    g.status = "flag " + x.toString() + "," + y.toString()
                } else {
                    reveal(*g.board, x, y)
                    if (g.startedAt == 0) {
                        g.startedAt = uiTicks()
                    }
                    g.status = "open " + x.toString() + "," + y.toString()
                }
                if (g.board.status == GameStatus.Lost) {
                    g.status = "boom - press NEW GAME (or R)"
                }
                if (g.board.status == GameStatus.Won) {
                    g.status = "you won in " + g.elapsed.toString() + "s"
                }
                setTextNamed(*g.frame, "status", g.status)
                println("  action " + h.action.toString() + " " + g.status)
            }
            return true
        }
        if (h.action == Act.Restart.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                restartGame(g, uiPtr, g.seed)
                println("  action restart")
            }
            return true
        }
        if (h.action == Act.Difficulty.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                switchDifficulty(g, uiPtr, h.payload)
                println("  action difficulty " + h.payload.toString())
            }
            return true
        }
        if (h.action == Act.Quit.toInt()) {
            if (e.kind == UiEventKind.MouseDown) {
                g.quit = true
            }
            return true
        }
        if (h.action == Act.Background.toInt()) {
            return true
        }
        return false
    }
    val custom: (Int) -> Unit = (node: Int) -> {
        paintCell(gamePtr, uiPtr, node)
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
                if (e.key == SDLK_ESCAPE) {
                    running = false
                } else if (e.key == SDLK_r) {
                    restartGame(gamePtr, uiPtr, gamePtr.seed)
                    println("  key restart")
                } else if (e.key == SDLK_1) {
                    switchDifficulty(gamePtr, uiPtr, 0)
                } else if (e.key == SDLK_2) {
                    switchDifficulty(gamePtr, uiPtr, 1)
                } else if (e.key == SDLK_3) {
                    switchDifficulty(gamePtr, uiPtr, 2)
                }
            } else {
                uiEvent(*gamePtr.frame, *e, answer)
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
            setTextNamed(*gamePtr.frame, "counter", counterText(minesRemaining(*gamePtr.board)))
            setTextNamed(*gamePtr.frame, "timer", gamePtr.elapsed.toString())
            uiClear(*uiPtr, theme.background)
            paintFrame(*gamePtr.frame, *theme, uiPtr, custom)
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
