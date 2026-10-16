Generated C++
====
The `minui` wrapper's platform glue - the declarations are `api.kt`. Two libraries are bound and
nothing is linked: SDL2's calls and Skia's `sk_*` calls are `@SmGen("native", ...)` declarations,
so each one resolves its symbol at first call. What stays C++ here is only what Simse cannot
spell:

- **`SDL_Event`** - the union of every event shape (`simse_ui_eventBuffer` plus one reader per
  field). The readers pick the member the event's type says it is: motion coordinates only for
  `SDL_MOUSEMOTION`, button fields only for `SDL_MOUSEBUTTON*`, and so on. This is the same shape
  `examples/sdl2/wrapper/_res.md` uses for its keyboard reader, extended to the mouse.
- **The pixel buffers** - `simse_ui_pixels` and `simse_ui_readPixels` hand out one allocation each.
  They are here rather than a Simse `List<Int8>` field because a `Ui` *value* is copied whenever it
  is returned or assigned: a list field's storage would move out from under the raster surface
  Skia built over it. Allocating in C++ makes the canvas stable by construction (one canvas, one
  buffer; a larger window reallocates).
- **The BMP writer** - the screenshot path. `SDL_RenderReadPixels` fills the readback buffer and
  this writes a 32-bit top-down `BI_RGB` BMP, which anything can open: the shape a screenshot has
  to have to be looked at, including by the harness.
- **`simse_ui_argb`** - packing a colour. `255 << 24` in Simse arithmetic is a needless question
  about signed overflow, so the shift happens where `Uint32` exists.

The SDL header comes from the kit under `Lib/3rdparty/`, which every build has on its include path
(`build.js` passes `/I<repo>`); `emit: reached` gates the text for the module's declarations, so a
program that names `minui` but never opens a window carries none of it.

!uiwin
====
emit: reached
includes:
```cpp
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
// The event union and its accessors. `WIN32_LEAN_AND_MEAN` is defined by the `native`
// generator's own include block, which renders before this one.
#include "Lib/3rdparty/SDL2-arm64/include/SDL2/SDL_events.h"
```
forward:
```cpp
void* simse_ui_eventBuffer();
Int32 simse_ui_eventType(const void* event);
Int32 simse_ui_eventX(const void* event);
Int32 simse_ui_eventY(const void* event);
Int32 simse_ui_eventButton(const void* event);
Int32 simse_ui_eventKey(const void* event);
Int32 simse_ui_eventWheel(const void* event);
Int32 simse_ui_windowEvent(const void* event);
void* simse_ui_mouseEvent(Int32 kind, Int32 x, Int32 y, Int32 button);
Int8* simse_ui_pixels(Int32 width, Int32 height);
Int8* simse_ui_readPixels(Int32 width, Int32 height);
Int32 simse_ui_writeBmp(const Str& path, const Int8* pixels, Int32 width, Int32 height);
Int32 simse_ui_argb(Int32 alpha, Int32 red, Int32 green, Int32 blue);
```
bodies:
```cpp
// The one event the loop polls into. Events are read one at a time on the thread that owns the
// window, and this is where the union's size and layout - which Simse cannot spell - live.
void* simse_ui_eventBuffer() {
    static SDL_Event event;
    return reinterpret_cast<void*>(&event);
}

Int32 simse_ui_eventType(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    return (Int32) typed->type;
}

// Each reader below takes the union member its event type owns, and nothing else: reading
// `motion.x` from a button event would be a different member of the same bytes.
Int32 simse_ui_eventX(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    if (typed->type == SDL_MOUSEMOTION) return (Int32) typed->motion.x;
    if (typed->type == SDL_MOUSEBUTTONDOWN || typed->type == SDL_MOUSEBUTTONUP) return (Int32) typed->button.x;
    return 0;
}

Int32 simse_ui_eventY(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    if (typed->type == SDL_MOUSEMOTION) return (Int32) typed->motion.y;
    if (typed->type == SDL_MOUSEBUTTONDOWN || typed->type == SDL_MOUSEBUTTONUP) return (Int32) typed->button.y;
    return 0;
}

// SDL's button *number*: 1 left, 2 middle, 3 right.
Int32 simse_ui_eventButton(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    if (typed->type == SDL_MOUSEBUTTONDOWN || typed->type == SDL_MOUSEBUTTONUP) return (Int32) typed->button.button;
    return 0;
}

// SDL's own `SDLK_*` code (`SDLK_ESCAPE` is 27).
Int32 simse_ui_eventKey(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    if (typed->type == SDL_KEYDOWN || typed->type == SDL_KEYUP) return (Int32) typed->key.keysym.sym;
    return 0;
}

// Positive is up, negative down, in notches.
Int32 simse_ui_eventWheel(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    if (typed->type == SDL_MOUSEWHEEL) return (Int32) typed->wheel.y;
    return 0;
}

// The `SDL_WINDOWEVENT_*` code, for the window events a loop cares about (close, resize).
Int32 simse_ui_windowEvent(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    if (typed->type == SDL_WINDOWEVENT) return (Int32) typed->window.event;
    return 0;
}

// A synthetic mouse event, for the scripted-input modes (`--click`, `--press`, and the game's own
// click sequences): the same bytes SDL writes for the real thing, so a scripted run exercises the
// real path - the union readers, the loop's translation, the app's handling. `kind` is SDL's own
// event type (`SDL_MOUSEMOTION`, `SDL_MOUSEBUTTONDOWN`, `SDL_MOUSEBUTTONUP`), and the caller hands
// the result to `SDL_PushEvent`.
void* simse_ui_mouseEvent(Int32 kind, Int32 x, Int32 y, Int32 button) {
    static SDL_Event event;
    std::memset(&event, 0, sizeof(event));
    event.type = (Uint32) kind;
    if (kind == SDL_MOUSEMOTION) {
        event.motion.x = (Sint32) x;
        event.motion.y = (Sint32) y;
    } else {
        event.button.x = (Sint32) x;
        event.button.y = (Sint32) y;
        event.button.button = (Uint8) button;
        event.button.state = (kind == SDL_MOUSEBUTTONDOWN) ? SDL_PRESSED : SDL_RELEASED;
        event.button.clicks = 1;
    }
    return reinterpret_cast<void*>(&event);
}

// One buffer per job, grown on demand and kept for the process: `simse_ui_pixels` is what Skia
// rasterizes into and what SDL uploads, `simse_ui_readPixels` is what a screenshot reads back
// into (it must not be the live buffer: reading back would overwrite the frame).
static Int8* simse_ui_buffer(Int8** slot, Int32* capacity, Int32 width, Int32 height) {
    const Int32 wanted = width * height * 4;
    if (wanted <= 0) return nullptr;
    if (*slot == nullptr || wanted > *capacity) {
        std::free(*slot);
        *slot = (Int8*) std::malloc((std::size_t) wanted);
        *capacity = (*slot != nullptr) ? wanted : 0;
        if (*slot != nullptr) std::memset(*slot, 0, (std::size_t) wanted);
    }
    return *slot;
}

Int8* simse_ui_pixels(Int32 width, Int32 height) {
    static Int8* slot = nullptr;
    static Int32 capacity = 0;
    return simse_ui_buffer(&slot, &capacity, width, height);
}

Int8* simse_ui_readPixels(Int32 width, Int32 height) {
    static Int8* slot = nullptr;
    static Int32 capacity = 0;
    return simse_ui_buffer(&slot, &capacity, width, height);
}

// A 32-bit top-down `BI_RGB` BMP of the bytes SDL read back: 1 on success. The negative height is
// the top-down flag - the buffer's first row is the top of the window.
Int32 simse_ui_writeBmp(const Str& path, const Int8* pixels, Int32 width, Int32 height) {
    if (pixels == nullptr || width <= 0 || height <= 0) return 0;
    const Int32 imageBytes = width * height * 4;
    BITMAPFILEHEADER fileHeader;
    BITMAPINFOHEADER infoHeader;
    std::memset(&fileHeader, 0, sizeof(fileHeader));
    std::memset(&infoHeader, 0, sizeof(infoHeader));
    fileHeader.bfType = 0x4D42; // "BM"
    fileHeader.bfOffBits = sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER);
    fileHeader.bfSize = fileHeader.bfOffBits + imageBytes;
    infoHeader.biSize = sizeof(BITMAPINFOHEADER);
    infoHeader.biWidth = width;
    infoHeader.biHeight = -height;
    infoHeader.biPlanes = 1;
    infoHeader.biBitCount = 32;
    infoHeader.biCompression = BI_RGB;
    infoHeader.biSizeImage = imageBytes;
    std::FILE* out = std::fopen(path.c_str(), "wb");
    if (out == nullptr) return 0;
    std::fwrite(&fileHeader, sizeof(fileHeader), 1, out);
    std::fwrite(&infoHeader, sizeof(infoHeader), 1, out);
    std::fwrite(pixels, 1, (std::size_t) imageBytes, out);
    std::fclose(out);
    return 1;
}

// `alpha`, `red`, `green`, `blue` in 0..255 -> one ARGB `Int32` (which is negative for an opaque
// colour, because `Int` is signed - that is the value Skia's `sk_color_t` wants).
Int32 simse_ui_argb(Int32 alpha, Int32 red, Int32 green, Int32 blue) {
    return (Int32) (((Uint32) alpha << 24) | ((Uint32) red << 16) | ((Uint32) green << 8)
                    | (Uint32) blue);
}
```
