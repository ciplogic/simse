package sdl2

// The `sdl2` module: a minimalist P/Invoke-style binding to SDL2, built on the `native`
// generator (src/compiler/NativeInvokeGen.kt, impl_specs/native-interop.md). Each
// declaration names the library and the exported symbol, and the compiler emits a thunk that
// resolves the symbol on first call with `LoadLibraryA`/`GetProcAddress` and calls through it.
// Nothing is linked and no SDL header is needed at the call site - SDL2.dll only has to sit
// beside the executable.
//
// The signatures are the library's ABI written in Simse types, the way a C# `[DllImport]`
// declaration is written: `Int` is the native `int`, and `RawPtr` is one of SDL's opaque
// handles (`SDL_Window*`, `SDL_Renderer*`, ...) - a `void*`, the raw pointer the generator
// casts on the way in and out. A program that needs a *typed* view of one writes the cast
// (`h.getAs<Int8>()`), which is what `RawPtr` leaves to C++.

// ---- lifecycle -----------------------------------------------------------

@SmGen("native", "SDL2.dll", "SDL_Init")
fun SDL_Init(flags: Int): Int

@SmGen("native", "SDL2.dll", "SDL_Quit")
fun SDL_Quit(): Unit

@SmGen("native", "SDL2.dll", "SDL_GetError")
fun SDL_GetError(): Str

@SmGen("native", "SDL2.dll", "SDL_Delay")
fun SDL_Delay(milliseconds: Int): Unit

// ---- window and renderer -------------------------------------------------

@SmGen("native", "SDL2.dll", "SDL_CreateWindow")
fun SDL_CreateWindow(title: Str, x: Int, y: Int, width: Int, height: Int, flags: Int): RawPtr

@SmGen("native", "SDL2.dll", "SDL_DestroyWindow")
fun SDL_DestroyWindow(window: RawPtr): Unit

@SmGen("native", "SDL2.dll", "SDL_CreateRenderer")
fun SDL_CreateRenderer(window: RawPtr, index: Int, flags: Int): RawPtr

@SmGen("native", "SDL2.dll", "SDL_DestroyRenderer")
fun SDL_DestroyRenderer(renderer: RawPtr): Unit

@SmGen("native", "SDL2.dll", "SDL_SetRenderDrawColor")
fun SDL_SetRenderDrawColor(renderer: RawPtr, red: Int, green: Int, blue: Int, alpha: Int): Int

@SmGen("native", "SDL2.dll", "SDL_RenderClear")
fun SDL_RenderClear(renderer: RawPtr): Int

@SmGen("native", "SDL2.dll", "SDL_RenderPresent")
fun SDL_RenderPresent(renderer: RawPtr): Unit

// ---- events --------------------------------------------------------------

@SmGen("native", "SDL2.dll", "SDL_PollEvent")
fun SDL_PollEvent(event: RawPtr): Int

// `SDL_Event` is the one thing here Simse cannot spell - a union of every event shape - so a
// little C++ glue (`_res.md`) owns it: `sdlEventBuffer` hands out the one event the program
// polls into, and the two readers reinterpret its bytes as the union member the event type
// says they are (a `SDL_KEYDOWN`'s bytes as a `SDL_KeyboardEvent`).
@SmGen("res", "sdlglue", "simse_sdl_eventBuffer")
fun sdlEventBuffer(): RawPtr

@SmGen("res", "sdlglue", "simse_sdl_eventType")
fun sdlEventType(event: RawPtr): Int

@SmGen("res", "sdlglue", "simse_sdl_eventKey")
fun sdlEventKey(event: RawPtr): Int

// ---- the handful of constants this wrapper exposes -----------------------

enum class SDL_EventKind {
    QUIT = 256,
    KEYDOWN = 768
}

enum class SDL_KeySymbol {
    ESCAPE = 27
}

// ---- a minimalist wrapper over the surface -------------------------------

// `SDL_INIT_VIDEO` (0x20, written decimal: Simse has no hex literals).
fun sdlInitVideo(): Bool {
    return SDL_Init(32) == 0
}

// `SDL_WINDOWPOS_CENTERED` is 0x2FFF0000.
fun sdlCreateWindow(title: Str, width: Int, height: Int): RawPtr {
    return SDL_CreateWindow(title, 805240832, 805240832, width, height, 0)
}

// `-1` is `SDL_RENDERER_DRIVERDEFAULT`; the flags are none (the first accelerated driver).
fun sdlCreateRenderer(window: RawPtr): RawPtr {
    return SDL_CreateRenderer(window, 0 - 1, 0)
}

// The whole "draw a background" step: set the clear colour, clear to it, present the frame.
fun sdlFill(renderer: RawPtr, red: Int, green: Int, blue: Int): Unit {
    SDL_SetRenderDrawColor(renderer, red, green, blue, 255)
    SDL_RenderClear(renderer)
    SDL_RenderPresent(renderer)
}

fun sdlEventKind(event: RawPtr): SDL_EventKind {
    return SDL_EventKind.fromInt(sdlEventType(event))
}

fun sdlKeySymbol(event: RawPtr): SDL_KeySymbol {
    return SDL_KeySymbol.fromInt(sdlEventKey(event))
}
