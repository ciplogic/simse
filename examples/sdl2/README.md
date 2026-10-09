# SDL2 in Simse - a P/Invoke wrapper and a program

This example is the worked proof of the **`native` generator**: a Simse declaration whose
implementation is an exported symbol of a native shared library, resolved at run time with
`LoadLibraryA`/`GetProcAddress` - the C# `[DllImport]` / P/Invoke shape. Nothing is linked and
no SDL header is included at the call site; the SDL2 import library and include directory are
not used at all.

```
wrapper/       the `sdl2` module (package `sdl2`): the P/Invoke declarations and a small API
  api.kt         @SmGen("native", "SDL2.dll", "SDL_Init") fun SDL_Init(flags: Int): Int, ...
  _res.md        the SDL_Event glue: the union is C++, since Simse has no pointer cast
app/src/       the demo (package `sdl2demo`): a window with a solid background, Escape quits
```

## Build and run

From the repository root (the compiler must be built first: `build.bat --release`):

```bat
:: 1. transpile the demo together with the wrapper module -> app/out.cpp
simse.exe --root examples/sdl2/app/src --module examples/sdl2/wrapper -o examples/sdl2/app/out.cpp

:: 2. compile the one amalgamated translation unit -> app/sdl2demo.exe
bun build.js --release --cpp examples/sdl2/app/out.cpp --exe examples/sdl2/app/sdl2demo.exe

:: 3. SDL2.dll has to be findable beside the executable - nothing links it
copy Lib\3rdparty\SDL2-arm64\bin\SDL2.dll docs\examples\sdl2\app\SDL2.dll

:: 4. run it; the argument is the number of frames to render (a smoke test)
docs\examples\sdl2\app\sdl2demo.exe 1
docs\examples\sdl2\app\sdl2demo.exe        :: ... or, with no argument, until Escape
```

`run.bat` in this folder does all four steps, so `run.bat 3` renders three frames and exits.

## What to look at

- `wrapper/api.kt` - the declarations. The signatures are the library's ABI written in Simse
  types: `Int` is the native `int`, `Str` is a native `const char*`, and an SDL handle
  (`SDL_Window*`, `SDL_Renderer*`) is a `RawPtr` - a `void*`, the raw pointer the generator casts
  on the way in and out (`specs/memory-model.md`).
- The emitted thunk for one of them (in `app/out.cpp`, after transpiling):

  ```cpp
  RawPtr __sm_native_SDL_CreateWindow(const Str& title, const Int32& x, /* ... */) {
      using Fn = void* (*)(const char*, Int32, Int32, Int32, Int32, Int32);
      static Fn fn = (Fn) __sm_nativeResolve("SDL2.dll", "SDL_CreateWindow");
      if (fn == nullptr) return nullptr;
      return (RawPtr) fn(title.c_str(), x, y, width, height, flags);
  }
  ```

  `__sm_nativeResolve` is the shared loader (`LoadLibraryA`/`GetProcAddress`, cached); the
  `(Fn)` cast is the raw-pointer cast P/Invoke makes for you.
- `wrapper/_res.md` - the one piece that stays C++. `SDL_Event` is a union of every event
  shape, which a `RawPtr` cannot name (it is the pointer, not the pointee), so the glue includes
  the real SDL header and reinterprets the event bytes (`SDL_Event*` for the type,
  `SDL_KeyboardEvent*` once the type says `SDL_KEYDOWN`).

## Caveats

- Windows only: the loader is `LoadLibraryA`/`GetProcAddress`. `SDL2.dll` must match the
  architecture `cl.exe` targets (this repository ships the ARM64 build under `Lib/3rdparty`).
- If the DLL or a symbol is missing, the thunk answers the declaration's default value (0, an
  empty `Str`, a null pointer) instead of crashing - so a missing DLL surfaces as SDL returning
  a null window, not as a load-time failure.
- The two files `app/out.cpp` and `app/sdl2demo.exe`, and the copied `app/SDL2.dll`, are build
  output (the first two are git-ignored).
