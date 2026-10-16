package app

import minesweeper

// The rules, headless: no window, no drawing, no clock. What this pins is the whole behaviour of
// `examples/minesweeper/game` - the seeded layout (rendered as text, so a change to the generator
// cannot pass unnoticed), the first click that cannot lose, the flood, flags and the counter, a
// chord, a loss and a win.
//
// It also prints the click sequence that *wins* a Beginner board at seed 12345 after the first
// click at (4,4): the app replays it (`--script`) to verify the game end to end through real
// events, and the sequence is only knowable from here because the rules are deterministic.

fun render(b: *Board): Str {
    var out: Str
    out.reserve(b.width * b.height + b.height)
    var y: Int = 0
    while (y < b.height) {
        var x: Int = 0
        while (x < b.width) {
            val cell: Cell = b.cells[y * b.width + x]
            if (cell.state == CellState.Hidden) {
                out.append('#')
            } else if (cell.state == CellState.Flagged) {
                out.append('F')
            } else if (cell.state == CellState.Exploded) {
                out.append('X')
            } else if (cell.state == CellState.WrongFlag) {
                out.append('!')
            } else if (cell.mine) {
                out.append('*')
            } else if (cell.neighbors == 0) {
                out.append('.')
            } else {
                out.append("12345678".charAt(cell.neighbors - 1))
            }
            x = x + 1
        }
        out.append('\n')
        y = y + 1
    }
    return out
}

// The clicks that open every safe cell of a board whose mines are already placed - the sequence the
// app replays to win. Row-major, and a click on an already-open cell is a no-op, so it is safe to
// include them all.
fun winScript(b: *Board): Str {
    var out: Str
    out.reserve(b.width * b.height * 7)
    var y: Int = 0
    while (y < b.height) {
        var x: Int = 0
        while (x < b.width) {
            if (!b.cells[y * b.width + x].mine) {
                if (out.size() > 0) {
                    out.appendStr("; ")
                }
                out = out + x.toString() + "," + y.toString() + ":L"
            }
            x = x + 1
        }
        y = y + 1
    }
    return out
}

fun main(): Int {
    // The generator: deterministic, and every step inside its own range.
    var state: Int = seedState(12345)
    var step: Int = 0
    var low: Int = state
    var high: Int = state
    while (step < 5) {
        state = nextRandom(state)
        if (step == 0) {
            println("rng first: " + state.toString())
        }
        if (state < low) {
            low = state
        }
        if (state > high) {
            high = state
        }
        step = step + 1
    }
    println("rng range ok: " + (low > 0 && high < 2147483647).toString())

    // A new board: sized, unseeded, and playing.
    var board: Board = newGame(Difficulty.Beginner, 12345)
    println(
        "layout: " + board.width.toString() + "x" + board.height.toString()
                + " mines=" + board.mineCount.toString() + " cells=" + board.cells.size().toString()
    )
    println(
        "status playing: " + (board.status == GameStatus.Playing).toString()
                + " seeded: " + board.seeded.toString()
    )

    // The first click cannot lose, and it opens a hole (the 3x3 stays clear, so the flood runs).
    reveal(*board, 4, 4)
    val statusAfterFirst: GameStatus = board.status
    println(
        "after first click: status=" + statusAfterFirst.toInt().toString()
                + " revealed=" + board.revealedCount.toString()
                + " seeded=" + board.seeded.toString()
    )
    println("--- board after (4,4)")
    println(render(*board))

    // A click on an open cell changes nothing, and the counter shows ten mines.
    val before: Int = board.revealedCount
    reveal(*board, 4, 4)
    println(
        "re-reveal is a no-op: " + (board.revealedCount == before).toString()
                + " counter=" + minesRemaining(*board).toString()
    )

    // Flags: hidden -> flagged -> hidden, and the counter follows. (7,2) is still hidden after the
    // flood above, which the render just showed.
    toggleFlag(*board, 7, 2)
    val flaggedState: CellState = board.cells[cellIndex(*board, 7, 2)].state
    println(
        "after flag: counter=" + minesRemaining(*board).toString()
                + " state=" + flaggedState.toInt().toString()
    )
    reveal(*board, 7, 2)
    println(
        "reveal on a flag does nothing: "
                + (board.cells[cellIndex(*board, 7, 2)].state == CellState.Flagged).toString()
    )
    toggleFlag(*board, 7, 2)
    println("after unflag: counter=" + minesRemaining(*board).toString())

    // The chord: find an open cell whose number is 1, then chord it with no flags (nothing happens).
    var chordX: Int = 0 - 1
    var chordY: Int = 0 - 1
    var y: Int = 0
    while (y < board.height) {
        var x: Int = 0
        while (x < board.width) {
            val index: Int = y * board.width + x
            if (board.cells[index].state == CellState.Revealed && board.cells[index].neighbors == 1) {
                chordX = x
                chordY = y
            }
            x = x + 1
        }
        y = y + 1
    }
    println("chord at: " + chordX.toString() + "," + chordY.toString())
    val beforeChord: Int = board.revealedCount
    chord(*board, chordX, chordY)
    println("chord without flags does nothing: " + (board.revealedCount == beforeChord).toString())

    // A chord with the mine flagged opens the rest of that number's neighbours.
    var chordMineX: Int = 0 - 1
    var chordMineY: Int = 0 - 1
    y = chordY - 1
    while (y <= chordY + 1) {
        var x: Int = chordX - 1
        while (x <= chordX + 1) {
            if (isInside(*board, x, y) && board.cells[cellIndex(*board, x, y)].mine) {
                chordMineX = x
                chordMineY = y
            }
            x = x + 1
        }
        y = y + 1
    }
    toggleFlag(*board, chordMineX, chordMineY)
    val beforeRealChord: Int = board.revealedCount
    chord(*board, chordX, chordY)
    println(
        "chord with the mine flagged opens the rest: "
                + (board.revealedCount > beforeRealChord).toString()
    )
    toggleFlag(*board, chordMineX, chordMineY)

    // The loss: open a mine. Every other mine is then shown.
    var mineX: Int = 0 - 1
    var mineY: Int = 0 - 1
    y = 0
    while (y < board.height) {
        var x: Int = 0
        while (x < board.width) {
            if (board.cells[y * board.width + x].mine) {
                mineX = x
                mineY = y
            }
            x = x + 1
        }
        y = y + 1
    }
    println("a mine is at: " + mineX.toString() + "," + mineY.toString())
    reveal(*board, mineX, mineY)
    val statusAfterLoss: GameStatus = board.status
    println("after the mine: status=" + statusAfterLoss.toInt().toString())
    println("--- board after the loss")
    println(render(*board))
    val revealedAfterLoss: Int = board.revealedCount
    reveal(*board, 5, 5)
    println("clicks after the loss do nothing: " + (board.revealedCount == revealedAfterLoss).toString())

    // A win, on a fresh board with the same seed: the same first click, then every safe cell. The
    // script it prints is what the app replays to verify the game through real events.
    var won: Board = newGame(Difficulty.Beginner, 12345)
    reveal(*won, 4, 4)
    println("--- win script")
    println(winScript(*won))
    var row: Int = 0
    while (row < won.height) {
        var column: Int = 0
        while (column < won.width) {
            if (!won.cells[row * won.width + column].mine) {
                reveal(*won, column, row)
            }
            column = column + 1
        }
        row = row + 1
    }
    val statusAfterWin: GameStatus = won.status
    println(
        "after opening every safe cell: status=" + statusAfterWin.toInt().toString()
                + " counter=" + minesRemaining(*won).toString()
                + " flags=" + won.flagCount.toString()
    )
    println("--- board after the win")
    println(render(*won))

    // The other two presets: sizes, mine counts, and one click that has to open something.
    var expert: Board = newGame(Difficulty.Expert, 7)
    reveal(*expert, 15, 8)
    var mines: Int = 0
    var i: Int = 0
    while (i < expert.cells.size()) {
        if (expert.cells[i].mine) {
            mines = mines + 1
        }
        i = i + 1
    }
    println(
        "expert: " + expert.width.toString() + "x" + expert.height.toString()
                + " mines=" + mines.toString() + " revealed=" + expert.revealedCount.toString()
    )
    var medium: Board = newGame(Difficulty.Intermediate, 99)
    reveal(*medium, 8, 8)
    println(
        "intermediate: " + medium.width.toString() + "x" + medium.height.toString()
                + " revealed=" + medium.revealedCount.toString()
    )
    return 0
}
