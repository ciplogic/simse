Generated C++
====
The `sdl2` wrapper's event glue - the declarations are in `api.kt`. `SDL_Event` is a union of
every event shape, which Simse cannot name, so the two readers are C++: the same bytes are read
as the union's shared `type` field, and then as the `SDL_KeyboardEvent` the type says they are.
The include is the SDL header itself, found through the repository root, which every build puts
on the include path (`build.js` passes `/I<repo>`). `emit: reached` gates the text for a module's
declaration, so a program that names the module but never polls an event carries none of it.

A handle here is a `void*` (`RawPtr`): the language spells the pointer, not the pointee, so the
reinterpretation stays on this side - which is the one thing a `RawPtr` cannot express.

!sdlglue
====
emit: reached
includes:
```cpp
#include "Lib/3rdparty/SDL2-arm64/include/SDL2/SDL_events.h"
```
forward:
```cpp
void* simse_sdl_eventBuffer();
Int32 simse_sdl_eventType(const void* event);
Int32 simse_sdl_eventKey(const void* event);
```
bodies:
```cpp
// The one event the program polls into. Events are read one at a time on the thread that owns
// the window, and this is where the union's size and layout - which Simse cannot spell - live.
void* simse_sdl_eventBuffer() {
    static SDL_Event event;
    return reinterpret_cast<void*>(&event);
}

// `SDL_Event` is a union whose first member is the shared `type` (SDL_events.h), so the type
// is read through the union; the keyboard fields are read by reinterpreting the same bytes as
// a `SDL_KeyboardEvent` - the raw pointer cast, in the one place the language cannot make it.
Int32 simse_sdl_eventType(const void* event) {
    if (event == nullptr) return 0;
    const SDL_Event* typed = reinterpret_cast<const SDL_Event*>(event);
    return (Int32) typed->type;
}

Int32 simse_sdl_eventKey(const void* event) {
    if (event == nullptr) return 0;
    const SDL_KeyboardEvent* key = reinterpret_cast<const SDL_KeyboardEvent*>(event);
    return (Int32) key->keysym.sym;
}
```
