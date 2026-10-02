package sdl2demo

import sdl2

// A minimal SDL2 program over the `sdl2` P/Invoke wrapper beside it: it opens a window with a
// solid background and leaves when Escape is pressed (or the window's close button is). The
// functions are the wrapper's, which the `native` generator binds to SDL2.dll at run time -
// nothing is linked. Its one argument is the number of frames to render before leaving, which
// is how it is smoke-tested without a keyboard; with no argument it runs until Escape. See
// `../README.md` for the build and run commands.

fun main(args: List<Str>): Int {
    var frames: Int = 0 - 1
    if (args.size() > 0) {
        val parsed: Opt<Int> = args[0].toInt()
        if (parsed.hasValue()) {
            frames = parsed.value()
        }
    }

    if (!sdlInitVideo()) {
        println("SDL_Init failed: " + SDL_GetError())
        return 1
    }
    val window: RawPtr = sdlCreateWindow("simse + SDL2 - press escape to quit", 640, 480)
    if (window == null) {
        println("SDL_CreateWindow failed: " + SDL_GetError())
        SDL_Quit()
        return 1
    }
    val renderer: RawPtr = sdlCreateRenderer(window)
    if (renderer == null) {
        println("SDL_CreateRenderer failed: " + SDL_GetError())
        SDL_DestroyWindow(window)
        SDL_Quit()
        return 1
    }

    // The wrapper's glue owns the one `SDL_Event` the program polls into, and reinterprets its
    // bytes as the event it holds (`sdlEventKind`/`sdlKeySymbol`).
    val eventPtr: RawPtr = sdlEventBuffer()

    var running: Bool = true
    var frame: Int = 0
    while (running) {
        while (SDL_PollEvent(eventPtr) != 0) {
            val kind: SDL_EventKind = sdlEventKind(eventPtr)
            if (kind == SDL_EventKind.QUIT) {
                running = false
            } else if (kind == SDL_EventKind.KEYDOWN && sdlKeySymbol(eventPtr) == SDL_KeySymbol.ESCAPE) {
                running = false
            }
        }
        // The background: a solid colour, redrawn every frame.
        sdlFill(renderer, 24, 32, 48)
        SDL_Delay(16)
        frame = frame + 1
        if (frames >= 0 && frame >= frames) {
            running = false
        }
    }

    SDL_DestroyRenderer(renderer)
    SDL_DestroyWindow(window)
    SDL_Quit()
    return 0
}
