# Minesweeper in Simse - on `minui`, the module it is built on

The example is two things at once:

- **`minui`** - a minimal retained-mode UI layer, and a module of its own (`modules/minui/`, beside
  `modules/xml` and the rest): an SDL2 window, a Skia canvas, a tree of nodes with names and
  listeners, a **stack of screens**, an **XML loader**, and a painter (`minui.md`, in the module's
  folder, is its design, shot by shot). It is the seed of the small 2D UI framework a Heroes-style
  game would want.
- **the game** - Minesweeper on top of it. The rules are an example-local module with no UI import
  at all, so they are testable headless (`stress/minesweeper`); the menu screen is **XML text**
  and the board is code, because its cells are painted by the app.

```
modules/minui/    the module (`import minui`, `--module modules/minui`):
  minui.md          the design: the tree, names/listeners/bubbling, screens, XML, the shots
  tree.kt           the headless core: arena, names, listeners, hit-test, the walk, the two
                    geometry transforms (`setRect`, `moveNode`)
  paint.kt          the painter: the theme, the palette values, the visitor
  screens.kt        the layer stack: push/pop, the paint base, the window follows the top screen
  xmlread.kt        the XML text reader (the `xml` module has the tree types, not a reader)
  xml.kt            the loader: XML text -> a `Frame` (kinds, geometry, names, the grid expansion)
  api.kt            the raw @SmGen declarations: SDL2, Skia's C API, the glue helpers, constants
  ui.kt             the typed surface the app calls (window, canvas, text, sprites, events)
  _res.md           the C++ glue: the SDL_Event union readers, the buffers, the BMP writer
examples/minesweeper/
  game/api.kt       the rules, UI-free: board, PRNG, first-click-safe seeding, reveal/flag/chord
  app/src/main.kt   the game: `import minui` + `import minesweeper`, the menu XML, both screens
```

`minui` depends on the `xml` module (`modules/xml`) for the parse tree's types (`XmlNode`,
`Attribute`), so a build names three roots: `modules/minui`, `examples/minesweeper/game` and
`modules/xml`. The module is deliberately *not* part of the compiler's own build (`tools/roots.mjs`
lists what the compiler is built from): it needs SDL and Skia where the compiler needs neither.

## Build and run

From the repository root (the compiler has to be built first: `build.bat --release`):

```bat
run.bat                                      :: the menu, until Escape or the window's X
run.bat --difficulty 1                       :: straight to a board (0: beginner, 2: expert)
run.bat --seed 7                             :: a different board
run.bat --frames 3 --screenshot out.bmp      :: three frames and exit, writing what was drawn
run.bat --frames 3 --script "180,136:L"      :: ... after a click on the menu's BEGINNER
run.bat --difficulty 0 --script "147,187:L"  :: ... or straight to the board and a click on 4,4
run.bat --difficulty 0 --script "147,187:L; r:K"        :: open a cell, then press R (a fresh board)
run.bat --difficulty 0 --script "esc:K"      :: press Escape: back to the menu
run.bat --difficulty 0 --script "147,187:L; 207,127:L"  :: open a cell, then a mine: the result panel
run.bat --difficulty 0 --script "147,187:L; 207,127:L; 85,232:L"   :: ... then PLAY AGAIN
```

`run.bat` does the four steps by hand: transpile with the three `--module` flags, compile the
amalgamation with `bun build.js --release --cpp`, copy the two DLLs beside the executable, run.

The scripted input goes through SDL's own event queue, so the path under test is the real one:
poll, translate, hit-test, bubble, action, rules. Coordinates are pixels of the window; for
Beginner (cell 30, board at 12,52) a cell's centre is `12 + col*30 + 15`, `52 + row*30 + 15`, the
menu's buttons are at `80,120` (BEGINNER), `80,160` (MEDIUM), `80,200` (EXPERT) and `80,264`
(QUIT). `:L` is a left click, `:R` a right click (flag), `:M` a middle click (chord), and `:K` is
a keystroke (`r:K`, `esc:K`, `1:K`, or SDL's own number: `114:K`).

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

- **Two screens on a stack.** The menu (360x348) pushes the board; the board's MENU button or
  Escape pops back to it, and Escape on the menu closes the window. The stack paints bottom-up
  (starting at the last opaque layer, so the covered menu costs nothing), dispatches input to the
  top layer only, and resizes the window to the top screen - which is why the menu and Expert's
  744x496 board can live on the same stack.
- **The menu is XML.** `<screen name="menu" width="360" height="348">` with a panel, two labels
  and four buttons; `loadScreen` builds the arena, `menuBind` binds behaviour to the names, and a
  screen that does not load is a message with the line it is on. Its buttons carry the difficulty
  as their listener's *payload* (`Act.Play`), which is how one handler serves all three.
- **The board is code.** Counter, clock, status line, MENU and NEW GAME are named nodes; the board
  is one `grid` whose cells are anonymous `Custom` nodes whose `payload` is the cell index, painted
  by `paintCell`. Left opens, right flags, middle (or both buttons) chords - a chord opens the
  covered neighbours of a satisfied number. A loss turns wrongly flagged cells into an X; a win
  flags every mine and the counter reads 000.
- **A finished board raises a modal.** Win or lose, a result panel (also XML, 260x180) is pushed
  *over* the board with `opaque = false`: the board is painted below it and dimmed by a scrim the
  app fills, every click goes to the panel (the board is untouchable while it is up), PLAY AGAIN or
  R plays the same board again, and MENU (or Escape) backs out. Its dialog is centred in a window
  whose size the XML cannot know: the app stretches the scrim with `setRect` and moves the dialog
  subtree with `moveNode`.
- **A frame is drawn when something changed** - an event, or the clock moving a second on. An idle
  window burns no CPU (measured: 0.000 s over 3 idle seconds), which is the whole point of a
  retained tree.
- **Keys**: R restarts, 1/2/3 switch difficulty, Escape backs out one screen.
