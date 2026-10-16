package minesweeper

// The rules of Minesweeper, and nothing else: no window, no drawing, no clock. A `Board` is a value
// the caller owns; every operation takes it by raw pointer (`*Board`) and writes in place, so the UI
// holds one board and redraws it, and the stress case drives one with no UI at all.
//
// Three decisions worth naming:
//
//  - **Mines are placed on the first reveal, not in `newGame`.** The first click is then always safe
//    *and* opens a hole: the 3x3 around it is kept clear, which is what the original game does.
//    `seeded` records whether that happened.
//  - **The generator is Lehmer/Park-Miller stepped with Schrage's algorithm** (`nextRandom`): the
//    classic minimal-standard PRNG, chosen because every intermediate value fits an `Int32`. A
//    32-bit LCG multiplying in the open would overflow a signed `Int` - undefined behaviour in the
//    emitted C++ - and this one cannot, so a fixed seed gives the same board on every build.
//  - **Time is the caller's.** Nothing here reads a clock; the UI times the game.

enum class Difficulty {
    Beginner = 0,
    Intermediate = 1,
    Expert = 2
}

data class Layout(var width: Int, var height: Int, var mineCount: Int)

// The three classic presets.
fun layoutOf(difficulty: Difficulty): Layout {
    if (difficulty == Difficulty.Beginner) {
        return Layout(9, 9, 10)
    }
    if (difficulty == Difficulty.Intermediate) {
        return Layout(16, 16, 40)
    }
    return Layout(30, 16, 99)
}

fun difficultyName(difficulty: Difficulty): Str {
    if (difficulty == Difficulty.Beginner) {
        return "BEGINNER"
    }
    if (difficulty == Difficulty.Intermediate) {
        return "INTERMEDIATE"
    }
    return "EXPERT"
}

// `Hidden`/`Revealed` are the player's view; `Flagged` is a guess; the last two are what a loss
// turns the board into (`Exploded` is the mine that was clicked, `WrongFlag` a flag on a safe cell).
enum class CellState {
    Hidden = 0,
    Revealed = 1,
    Flagged = 2,
    Exploded = 3,
    WrongFlag = 4
}

enum class GameStatus {
    Playing = 0,
    Won = 1,
    Lost = 2
}

// `mine` is the generator's answer and `neighbors` the count around a safe cell; the UI reads both
// only after `seeded` (a mine still hidden is not something a player can see).
data class Cell(var state: CellState, var neighbors: Int, var mine: Bool)

data class Board(
    var width: Int,
    var height: Int,
    var mineCount: Int,
    var cells: List<Cell>,
    var status: GameStatus,
    var flagCount: Int,
    var revealedCount: Int,
    var seeded: Bool,
    var rng: Int
)

// A seed of any `Int` lands in the generator's own range, and never on 0 (a fixed point).
fun seedState(seed: Int): Int {
    var state: Int = seed % 2147483647
    if (state < 0) {
        state = state + 2147483647
    }
    if (state == 0) {
        state = 1
    }
    return state
}

// `x = 16807 * x mod (2^31 - 1)`, written the way Schrage wrote it: `hi`/`lo` split the state so both
// products stay inside an `Int32` (`16807 * 127772` and `2836 * 16807` are the worst cases, both
// below 2^31). The result is in [1, 2147483646].
data fun nextRandom(state: Int): Int {
    val hi: Int = state / 127773
    val lo: Int = state % 127773
    var next: Int = 16807 * lo - 2836 * hi
    if (next < 0) {
        next = next + 2147483647
    }
    return next
}

fun newGame(difficulty: Difficulty, seed: Int): Board {
    val layout: Layout = layoutOf(difficulty)
    val total: Int = layout.width * layout.height
    val blank: Cell = Cell(CellState.Hidden, 0, false)
    return Board(
        layout.width, layout.height, layout.mineCount,
        List<Cell>(total, blank), GameStatus.Playing, 0, 0, false, seedState(seed)
    )
}

fun isInside(b: *Board, x: Int, y: Int): Bool {
    return x >= 0 && x < b.width && y >= 0 && y < b.height
}

fun cellIndex(b: *Board, x: Int, y: Int): Int {
    return y * b.width + x
}

// How many mines sit around (x, y) - the eight neighbours a revealed cell counts.
fun neighborMines(b: *Board, x: Int, y: Int): Int {
    var count: Int = 0
    var dy: Int = 0 - 1
    while (dy <= 1) {
        var dx: Int = 0 - 1
        while (dx <= 1) {
            val nx: Int = x + dx
            val ny: Int = y + dy
            if ((dx != 0 || dy != 0) && isInside(b, nx, ny)) {
                if (b.cells[cellIndex(b, nx, ny)].mine) {
                    count = count + 1
                }
            }
            dx = dx + 1
        }
        dy = dy + 1
    }
    return count
}

// Place `mineCount` mines, keeping the 3x3 around (safeX, safeY) clear, then count every safe cell's
// neighbours. Picking with rejection keeps this short, and mines are always far fewer than cells (99
// of 480 on Expert), so it converges immediately.
fun placeMines(b: *Board, safeX: Int, safeY: Int): Unit {
    var placed: Int = 0
    while (placed < b.mineCount) {
        b.rng = nextRandom(b.rng)
        val pick: Int = b.rng % (b.width * b.height)
        val x: Int = pick % b.width
        val y: Int = pick / b.width
        val inSafeZone: Bool =
            x >= safeX - 1 && x <= safeX + 1 && y >= safeY - 1 && y <= safeY + 1
        if (!inSafeZone && !b.cells[pick].mine) {
            b.cells[pick].mine = true
            placed = placed + 1
        }
    }
    var y: Int = 0
    while (y < b.height) {
        var x: Int = 0
        while (x < b.width) {
            val index: Int = cellIndex(b, x, y)
            if (!b.cells[index].mine) {
                b.cells[index].neighbors = neighborMines(b, x, y)
            }
            x = x + 1
        }
        y = y + 1
    }
    b.seeded = true
}

// Open one cell, and every neighbour of a cell with no adjacent mine (the flood the game is known
// for). Recursion is bounded by the board: at most one call per cell.
fun floodReveal(b: *Board, x: Int, y: Int): Unit {
    if (!isInside(b, x, y)) {
        return
    }
    val index: Int = cellIndex(b, x, y)
    val state: CellState = b.cells[index].state
    if (state == CellState.Revealed || state == CellState.Flagged || b.cells[index].mine) {
        return
    }
    b.cells[index].state = CellState.Revealed
    b.revealedCount = b.revealedCount + 1
    if (b.cells[index].neighbors == 0) {
        var dy: Int = 0 - 1
        while (dy <= 1) {
            var dx: Int = 0 - 1
            while (dx <= 1) {
                if (dx != 0 || dy != 0) {
                    floodReveal(b, x + dx, y + dy)
                }
                dx = dx + 1
            }
            dy = dy + 1
        }
    }
}

// A loss shows the board: every mine revealed (the one clicked as `Exploded`) and every flag that
// was wrong marked as such.
fun markLoss(b: *Board): Unit {
    var y: Int = 0
    while (y < b.height) {
        var x: Int = 0
        while (x < b.width) {
            val index: Int = cellIndex(b, x, y)
            if (b.cells[index].mine && b.cells[index].state == CellState.Hidden) {
                b.cells[index].state = CellState.Revealed
            } else if (!b.cells[index].mine && b.cells[index].state == CellState.Flagged) {
                b.cells[index].state = CellState.WrongFlag
            }
            x = x + 1
        }
        y = y + 1
    }
}

// A win flags every mine (the counter then reads zero, as it should).
fun markWin(b: *Board): Unit {
    b.status = GameStatus.Won
    var y: Int = 0
    while (y < b.height) {
        var x: Int = 0
        while (x < b.width) {
            val index: Int = cellIndex(b, x, y)
            if (b.cells[index].mine && b.cells[index].state != CellState.Flagged) {
                b.cells[index].state = CellState.Flagged
                b.flagCount = b.flagCount + 1
            }
            x = x + 1
        }
        y = y + 1
    }
}

// The player's left click. The first one places the mines (so it cannot lose), a flag or an
// already-open cell is ignored, and a mine ends the game.
fun reveal(b: *Board, x: Int, y: Int): Unit {
    if (b.status != GameStatus.Playing || !isInside(b, x, y)) {
        return
    }
    val index: Int = cellIndex(b, x, y)
    val state: CellState = b.cells[index].state
    if (state == CellState.Flagged || state == CellState.Revealed) {
        return
    }
    if (!b.seeded) {
        placeMines(b, x, y)
    }
    if (b.cells[index].mine) {
        b.cells[index].state = CellState.Exploded
        markLoss(b)
        b.status = GameStatus.Lost
        return
    }
    floodReveal(b, x, y)
    if (b.revealedCount == b.width * b.height - b.mineCount) {
        markWin(b)
    }
}

// The right click: hidden <-> flagged. The counter is allowed to go negative, as it does in the
// original - the player may flag more cells than there are mines.
fun toggleFlag(b: *Board, x: Int, y: Int): Unit {
    if (b.status != GameStatus.Playing || !isInside(b, x, y)) {
        return
    }
    val index: Int = cellIndex(b, x, y)
    val state: CellState = b.cells[index].state
    if (state == CellState.Hidden) {
        b.cells[index].state = CellState.Flagged
        b.flagCount = b.flagCount + 1
    } else if (state == CellState.Flagged) {
        b.cells[index].state = CellState.Hidden
        b.flagCount = b.flagCount - 1
    }
}

fun neighborFlags(b: *Board, x: Int, y: Int): Int {
    var count: Int = 0
    var dy: Int = 0 - 1
    while (dy <= 1) {
        var dx: Int = 0 - 1
        while (dx <= 1) {
            val nx: Int = x + dx
            val ny: Int = y + dy
            if ((dx != 0 || dy != 0) && isInside(b, nx, ny)) {
                if (b.cells[cellIndex(b, nx, ny)].state == CellState.Flagged) {
                    count = count + 1
                }
            }
            dx = dx + 1
        }
        dy = dy + 1
    }
    return count
}

// The chord: a click on an open number whose flags already match that number opens the rest of its
// neighbours. A wrong flag makes this the losing move, which is the point of the feature.
fun chord(b: *Board, x: Int, y: Int): Unit {
    if (b.status != GameStatus.Playing || !isInside(b, x, y)) {
        return
    }
    val index: Int = cellIndex(b, x, y)
    if (b.cells[index].state != CellState.Revealed) {
        return
    }
    val neighbors: Int = b.cells[index].neighbors
    if (neighbors == 0 || neighborFlags(b, x, y) != neighbors) {
        return
    }
    var dy: Int = 0 - 1
    while (dy <= 1) {
        var dx: Int = 0 - 1
        while (dx <= 1) {
            if (dx != 0 || dy != 0) {
                reveal(b, x + dx, y + dy)
            }
            dx = dx + 1
        }
        dy = dy + 1
    }
}

// What the mine counter shows: mines minus flags placed.
fun minesRemaining(b: *Board): Int {
    return b.mineCount - b.flagCount
}
