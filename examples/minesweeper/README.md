# Minesweeper in Simse - and `minui`, the layer it is built on

The example is two things at once:

- **`minui`** - a minimal retained-mode UI layer: an SDL2 window, a Skia canvas, a tree of nodes
  with names and listeners, screens, and a painter (`minui.md` is its design, shot by shot). It is
  the seed of the small 2D UI framework a Heroes-style game would want.
- **the game** - Minesweeper on top of it. The rules are a module of their own with no UI import
  at all, so they are testable headless (`stress/minesweeper`), and the app is a thin client: a
  tree, a theme, and one handler per action.

```
minui.md          the design: the tree, names/listeners/bubbling, screens, XML, the shots
minui/            the module (`import minui`):
  tree.kt           the headless half: arena, names, listeners, hit-test, the leaf-to-root walk
  paint.kt          the painter: the theme, the palette values, the visitor
  api.kt            the raw @SmGen declarations: SDL2, Skia's C API, the glue helpers, constants
  ui.kt             the typed surface the app calls (window, canvas, text, sprites, events)
  _res.md           the C++ glue: the SDL_Event union readers, the buffers, the BMP writer
game/api.kt       the rules, UI-free: board, PRNG, first-click-safe seeding, reveal/flag/chord
app/src/main.kt   the game: `import minui` + `import minesweeper`, the whole client
```

## Build and run

From the repository root (the compiler has to be built first: `build.bat --release`):

```bat
run.bat                                    :: the game, until Escape or the window's X
run.bat --difficulty 1                     :: start on intermediate (2: expert, 0: beginner)
run.bat --seed 7                           :: a different board
run.bat --frames 3 --screenshot out.bmp    :: three frames and exit, writing what was drawn
run.bat --frames 3 --script "147,187:L"    :: ... after a click at (147,187): opens cell 4,4
run.bat --script "147,187:L; 207,127:L"    :: ... and then a mine: the loss screen
```

`run.bat` does the four steps by hand: transpile with the two `--module` flags, compile the
amalgamation with `bun build.js --release --cpp`, copy the two DLLs beside the executable, run.

The scripted clicks go through SDL's own event queue, so the path under test is the real one:
poll, translate, hit-test, bubble, action, rules. Coordinates are pixels of the window; for
Beginner (cell 30, board at 12,52) a cell's centre is `12 + col*30 + 15`, `52 + row*30 + 15`.
`:L` is a left click, `:R` a right click (flag), `:M` a middle click (chord).

## The two dependencies

Nothing is linked: every call resolves its symbol at run time (that is what `@SmGen("native", ...)`
generates), so the two DLLs only have to sit beside the executable.

| DLL | where it comes from |
| --- | --- |
| `libSkiaSharp.dll` (7.9 MB) | the SkiaSharp native package - `%USERPROFILE%\.nuget\packages\skiasharp.nativeassets.win32\2.88.9\runtimes\win-arm64\native\` (the same Skia build Avalonia.Skia 11.3.22 uses) |
| `SDL2.dll` (1.6 MB) | the SDL2 ARM64 dev kit unpacked under `Lib/3rdparty/SDL2-arm64/` (the release zip; `Lib/` is not committed) |

The SDL header the glue includes comes from that same kit (the repo root is on every build's
include path).

SDL is used for its window, its event queue and its blitter - *not* for drawing. Skia draws every
pixel into a byte buffer and SDL only presents it. The blitter is SDL's **software** renderer on
purpose: with the direct3d renderer on this machine some window sizes come back with red and blue
traded, in the window itself, not just in a read-back (504x592 swapped, 512x512 not, nothing else
different; the probes that show it are `build/colorprobe*.cpp`, git-ignored). The software path is
pixel-exact at every size and all it has to do is blit.

## What the game does

- **A board of cells.** One `grid` node holds all of them; each cell is an anonymous `Custom` node
  whose `payload` is its index, and one listener on the grid answers for all of them. The painter
  asks the rules what is under each cell and draws the covered face, the numbers 1-8, a flag, a
  mine, or the red of the one that exploded.
- **Clicks.** Left opens, right flags, middle (or both buttons) chords - a chord opens the covered
  neighbours of a satisfied number. A loss turns wrongly flagged cells into an X; a win flags every
  mine and the counter reads 000.
- **Three difficulties** (Beginner 9x9/10, Intermediate 16x16/40, Expert 30x16/99), each with its
  own cell size and window size: switching rebuilds the tree and resizes the window. The counter,
  the clock, the NEW GAME button and the difficulty buttons are ordinary nodes with names, bound
  once per build (`frameOn`) to actions.
- **Keys**: R restarts, 1/2/3 switch difficulty, Escape quits.
- **A frame is drawn when something changed** - an event, or the clock moving a second on. An idle
  window burns no CPU (measured: 0.000 s over 3 idle seconds), which is the whole point of a
  retained tree.
