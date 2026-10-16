# Minesweeper in Simse - and `minui`, the layer it is built on

The example is two things at once:

- **`minui`** - a minimal retained-mode UI layer: an SDL2 window, a Skia canvas, a tree of nodes
  with names and listeners, screens, and a painter (`minui.md` is its design, shot by shot). It is
  the seed of the small 2D UI framework a Heroes-style game would want.
- **the game** - Minesweeper on top of it. The rules are a module of their own with no UI import
  at all, so they are testable headless (`stress/minesweeper` when that shot lands).

```
minui.md          the design: the tree, names/listeners/bubbling, screens, XML, the shots
minui/            the module (`import minui`):
  tree.kt           the headless half: arena, names, listeners, hit-test, the leaf-to-root walk
  paint.kt          the painter: the theme, the palette values, the visitor
  api.kt            the raw @SmGen declarations: SDL2, Skia's C API, the glue helpers, constants
  ui.kt             the typed surface the app calls (window, canvas, text, sprites, events)
  _res.md           the C++ glue: the SDL_Event union readers, the buffers, the BMP writer
app/src/main.kt   minui v0.5: a client of the tree (shot 2b was shot 1 drawn this way)
```

## Build and run

From the repository root (the compiler has to be built first: `build.bat --release`):

```bat
run.bat                                 :: window until Escape
run.bat --frames 3                      :: three frames and exit (a smoke test)
run.bat --frames 3 --screenshot out.bmp :: ... and write what the renderer presented
run.bat --frames 3 --sheet tiles.png    :: ... with a 16px-cell atlas decoded and drawn
run.bat --frames 3 --click 210,115      :: ... after a scripted click on NEW GAME
run.bat --frames 3 --press 210,115      :: ... with the button still held (pressed shows)
run.bat --frames 3 --click 162,344      :: ... or on a cell (the board's listener names it)
run.bat --frames 3 --rclick 162,344     :: ... or a right click, which flags that cell
```

`run.bat` does the four steps by hand: transpile with `--module examples/minesweeper/minui`,
compile the amalgamation with `bun build.js --release --cpp`, copy the two DLLs beside the
executable, run.

## The two dependencies

Nothing is linked: every call resolves its symbol at run time (that is what `@SmGen("native", ...)`
generates), so the two DLLs only have to sit beside the executable.

| DLL | where it comes from |
| --- | --- |
| `libSkiaSharp.dll` (7.9 MB) | the SkiaSharp native package - `%USERPROFILE%\.nuget\packages\skiasharp.nativeassets.win32\2.88.9\runtimes\win-arm64\native\` (the same Skia build Avalonia.Skia 11.3.22 uses) |
| `SDL2.dll` (1.6 MB) | the SDL2 ARM64 dev kit unpacked under `Lib/3rdparty/SDL2-arm64/` (the release zip; `Lib/` is not committed) |

The SDL header the glue includes comes from that same kit (the repo root is on every build's
include path).

## What is landed (and what is not)

`app/src/main.kt` is a *client* of minui now: it builds a frame (names, listeners, geometry),
routes the platform events through `uiEvent` and paints through `paintFrame`. Two shots of
`minui.md` are in it:

- **shot 1, the platform**: an SDL window whose input reaches Simse, a Skia canvas whose pixels SDL
  presents as a streaming texture, text from a system font (no `.ttf` ships: the font manager is
  DirectWrite inside `libSkiaSharp`), a PNG decoded and scaled with nearest sampling, a screenshot
  taken from the renderer's own read-back.
- **shot 2b, the tree**: the title, panel and three buttons are ordinary nodes; the board is one
  `grid` node with one listener and 64 anonymous cells whose `payload` says which cell; the
  handlers mutate the demo's state and rewrite the status line the screenshot reads.

What the scripted modes prove is the *dispatch*, not the drawing - the status line can only say
`cell 26` if a click travelled hit test -> bubble to the grid's listener -> action -> the leaf's
payload. The events are built and pushed through **SDL's own queue** (`SDL_PushEvent`), so the
path under test is the real one, and the pixels then say the same thing: `58,90,138` idle,
`78,116,172` hovered, `44,68,104` pressed, `30,38,52` revealed.

Not yet: the rules (shot 3), the game (shot 4), screens/layers and XML (shot 5). The window is
fixed-size and there is no damage tracking - a full redraw is 0.19 ms at 560x300, measured.
