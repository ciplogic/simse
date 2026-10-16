package minui

// The raw bindings behind minui: SDL2 (window, renderer, texture, input) and Skia's C API
// (`sk_*`), plus the handful of C++ helpers in `_res.md` (`win*`) for the two things Simse cannot
// spell - SDL's `SDL_Event` union and a byte buffer's address.
//
// Nothing is linked: each `@SmGen("native", ...)` declaration becomes a thunk that resolves its
// symbol with `LoadLibraryA`/`GetProcAddress` on first call, so the two DLLs only have to sit
// beside the executable:
//
//   libSkiaSharp.dll   Skia + its C API, 7.9 MB, from the SkiaSharp NuGet package
//                      (`skiasharp.nativeassets.win32/2.88.9/runtimes/win-arm64/native/`)
//   SDL2.dll           SDL 2.33.0, from the ARM64 dev kit unpacked under `Lib/3rdparty/`
//
// The signatures are the libraries' ABI written in Simse types: `Int` is the native `int`,
// `RawPtr` an opaque handle, `*Int8` a byte buffer, and `Str` a `const char*` (the thunk passes
// `text.c_str()`). Two shapes are worth naming because they are easy to get wrong:
//
//  - **`Float32` parameters take Simse ints** (`sk_canvas_draw_rect` is all floats). The language
//    converts numbers in arguments, bindings and field writes, so no call site writes a float.
//  - **`size_t` parameters are declared `Int64`** (`rowBytes`, a text byte length): a 64-bit
//    parameter must not be handed a 32-bit argument, and `Int` -> `Int64` is one of those
//    conversions.
//
// Every constant below is named after the library's own macro (`SDL_PIXELFORMAT_ARGB8888`,
// `SK_COLORTYPE_BGRA_8888`) and its decimal value was printed from the real headers by
// `build/sdlconst.cpp` and the probes - Simse has no hex and no preprocessor, so they are data
// here rather than comments to trust.

// ---- SDL2: lifecycle, window, renderer, texture ---------------------------

@SmGen("native", "SDL2.dll", "SDL_Init")
fun SDL_Init(flags: Int): Int

@SmGen("native", "SDL2.dll", "SDL_Quit")
fun SDL_Quit(): Unit

@SmGen("native", "SDL2.dll", "SDL_GetError")
fun SDL_GetError(): Str

@SmGen("native", "SDL2.dll", "SDL_CreateWindow")
fun SDL_CreateWindow(title: Str, x: Int, y: Int, width: Int, height: Int, flags: Int): RawPtr

@SmGen("native", "SDL2.dll", "SDL_DestroyWindow")
fun SDL_DestroyWindow(window: RawPtr): Unit

@SmGen("native", "SDL2.dll", "SDL_CreateRenderer")
fun SDL_CreateRenderer(window: RawPtr, index: Int, flags: Int): RawPtr

@SmGen("native", "SDL2.dll", "SDL_DestroyRenderer")
fun SDL_DestroyRenderer(renderer: RawPtr): Unit

@SmGen("native", "SDL2.dll", "SDL_CreateTexture")
fun SDL_CreateTexture(renderer: RawPtr, format: Int, access: Int, width: Int, height: Int): RawPtr

@SmGen("native", "SDL2.dll", "SDL_DestroyTexture")
fun SDL_DestroyTexture(texture: RawPtr): Unit

// The frame's upload: our bytes -> the streaming texture. `rect` is null for the whole texture.
@SmGen("native", "SDL2.dll", "SDL_UpdateTexture")
fun SDL_UpdateTexture(texture: RawPtr, rect: RawPtr, pixels: *Int8, pitch: Int): Int

@SmGen("native", "SDL2.dll", "SDL_RenderClear")
fun SDL_RenderClear(renderer: RawPtr): Int

@SmGen("native", "SDL2.dll", "SDL_RenderCopy")
fun SDL_RenderCopy(renderer: RawPtr, texture: RawPtr, src: RawPtr, dst: RawPtr): Int

@SmGen("native", "SDL2.dll", "SDL_RenderPresent")
fun SDL_RenderPresent(renderer: RawPtr): Unit

// What the renderer holds *now*, into our bytes: the screenshot path (it proves the whole
// pipeline - Skia, the upload, the blit - not just Skia's own buffer).
@SmGen("native", "SDL2.dll", "SDL_RenderReadPixels")
fun SDL_RenderReadPixels(renderer: RawPtr, rect: RawPtr, format: Int, pixels: *Int8, pitch: Int): Int

// ---- SDL2: input and time -------------------------------------------------

@SmGen("native", "SDL2.dll", "SDL_PollEvent")
fun SDL_PollEvent(event: RawPtr): Int

// The buttons held *right now*, as a mask, with the cursor's position written through the two
// pointers - that mask is what tells a chord from a plain click.
@SmGen("native", "SDL2.dll", "SDL_GetMouseState")
fun SDL_GetMouseState(x: *Int, y: *Int): Int

@SmGen("native", "SDL2.dll", "SDL_GetTicks")
fun SDL_GetTicks(): Int

// Puts an event back on SDL's queue: how the scripted-input modes feed a click through the *real*
// path instead of calling the app's handler directly.
@SmGen("native", "SDL2.dll", "SDL_PushEvent")
fun SDL_PushEvent(event: RawPtr): Int

@SmGen("native", "SDL2.dll", "SDL_Delay")
fun SDL_Delay(milliseconds: Int): Unit

// ---- Skia: the surface over our byte buffer, and the drawing calls --------

// The surface is a *view* over memory we own (`winPixels`): Skia writes pixels into it and we
// hand the same bytes to SDL. The release proc is null - the buffer outlives the surface here.
@SmGen("native", "libSkiaSharp.dll", "sk_surface_new_raster_direct")
fun sk_surface_new_raster_direct(
    info: *SkImageInfo, pixels: *Int8, rowBytes: Int64,
    releaseProc: RawPtr, context: RawPtr, props: RawPtr
): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_surface_get_canvas")
fun sk_surface_get_canvas(surface: RawPtr): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_surface_unref")
fun sk_surface_unref(surface: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_paint_new")
fun sk_paint_new(): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_paint_delete")
fun sk_paint_delete(paint: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_paint_set_color")
fun sk_paint_set_color(paint: RawPtr, color: Int): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_paint_set_antialias")
fun sk_paint_set_antialias(paint: RawPtr, antialias: Bool): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_paint_set_filter_quality")
fun sk_paint_set_filter_quality(paint: RawPtr, quality: Int): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_paint_set_style")
fun sk_paint_set_style(paint: RawPtr, style: Int): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_paint_set_stroke_width")
fun sk_paint_set_stroke_width(paint: RawPtr, width: Float32): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_clear")
fun sk_canvas_clear(canvas: RawPtr, color: Int): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_rect")
fun sk_canvas_draw_rect(canvas: RawPtr, rect: *SkRect, paint: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_round_rect")
fun sk_canvas_draw_round_rect(canvas: RawPtr, rect: *SkRect, rx: Float32, ry: Float32, paint: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_oval")
fun sk_canvas_draw_oval(canvas: RawPtr, rect: *SkRect, paint: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_circle")
fun sk_canvas_draw_circle(canvas: RawPtr, cx: Float32, cy: Float32, radius: Float32, paint: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_line")
fun sk_canvas_draw_line(canvas: RawPtr, x0: Float32, y0: Float32, x1: Float32, y1: Float32, paint: RawPtr): Unit

// `startAngle`/`sweepAngle` in degrees, `useCenter` for a pie.
@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_arc")
fun sk_canvas_draw_arc(
    canvas: RawPtr, oval: *SkRect, startAngle: Float32, sweepAngle: Float32,
    useCenter: Bool, paint: RawPtr
): Unit

// Six arguments: the sampling options sit between the rects and the paint (a five-argument call
// is a latent bug - it worked once by luck).
@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_image_rect")
fun sk_canvas_draw_image_rect(
    canvas: RawPtr, image: RawPtr, src: *SkRect, dst: *SkRect,
    sampling: RawPtr, paint: RawPtr
): Unit

// ---- Skia: images (`sk_data` -> `sk_image`), and text ---------------------

@SmGen("native", "libSkiaSharp.dll", "sk_data_new_from_file")
fun sk_data_new_from_file(path: Str): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_image_new_from_encoded")
fun sk_image_new_from_encoded(encoded: RawPtr): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_data_unref")
fun sk_data_unref(encoded: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_image_unref")
fun sk_image_unref(image: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_image_get_width")
fun sk_image_get_width(image: RawPtr): Int

@SmGen("native", "libSkiaSharp.dll", "sk_image_get_height")
fun sk_image_get_height(image: RawPtr): Int

// Text comes from the *system's* fonts: the font manager is DirectWrite inside libSkiaSharp, so
// no .ttf ships with the example. `sk_typeface_create_default` is still exported but crashed every
// run - the manager trio below is the supported path (and the one `minui.md` pins).
@SmGen("native", "libSkiaSharp.dll", "sk_fontmgr_create_default")
fun sk_fontmgr_create_default(): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_fontmgr_unref")
fun sk_fontmgr_unref(manager: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_fontmgr_match_family_style")
fun sk_fontmgr_match_family_style(manager: RawPtr, family: Str, style: RawPtr): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_fontstyle_new")
fun sk_fontstyle_new(weight: Int, width: Int, slant: Int): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_fontstyle_delete")
fun sk_fontstyle_delete(style: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_typeface_unref")
fun sk_typeface_unref(typeface: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_font_new_with_values")
fun sk_font_new_with_values(typeface: RawPtr, size: Float32, scaleX: Float32, skewX: Float32): RawPtr

@SmGen("native", "libSkiaSharp.dll", "sk_font_delete")
fun sk_font_delete(font: RawPtr): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_font_set_subpixel")
fun sk_font_set_subpixel(font: RawPtr, value: Bool): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_font_set_edging")
fun sk_font_set_edging(font: RawPtr, value: Int): Unit

@SmGen("native", "libSkiaSharp.dll", "sk_font_set_embolden")
fun sk_font_set_embolden(font: RawPtr, value: Bool): Unit

// `sk_font_measure_text` answers the advance width and writes the ink bounds through `bounds` -
// that width is what centred text and layout are built on.
@SmGen("native", "libSkiaSharp.dll", "sk_font_measure_text")
fun sk_font_measure_text(
    font: RawPtr, text: Str, byteLength: Int64, encoding: Int,
    bounds: *SkRect, paint: RawPtr
): Float32

@SmGen("native", "libSkiaSharp.dll", "sk_canvas_draw_simple_text")
fun sk_canvas_draw_simple_text(
    canvas: RawPtr, text: Str, byteLength: Int64, encoding: Int,
    x: Float32, y: Float32, font: RawPtr, paint: RawPtr
): Unit

// ---- the two structs a call has to hand over physically -------------------

// Skia's `sk_imageinfo_t` and `sk_rect_t` as the ABI defines them. `native class` is exactly this
// job: the host's own alignment instead of the language's 4-byte packing. `SkImageInfo`'s field
// order is the trap the probe caught - colorspace *first*, then width/height/colorType/alphaType.
native class SkImageInfo(
    var colorspace: RawPtr, var width: Int, var height: Int,
    var colorType: Int, var alphaType: Int
)

native class SkRect(var left: Float32, var top: Float32, var right: Float32, var bottom: Float32)

// ---- the C++ helpers (`_res.md`, section `uiwin`) -------------------------

// SDL's `SDL_Event` is a union of every event shape, which Simse cannot name: the glue owns the
// one event the loop polls into and reads the member the event's type says it is.
@SmGen("res", "uiwin", "simse_ui_eventBuffer")
fun winEventBuffer(): RawPtr

@SmGen("res", "uiwin", "simse_ui_eventType")
fun winEventType(event: RawPtr): Int

@SmGen("res", "uiwin", "simse_ui_eventX")
fun winEventX(event: RawPtr): Int

@SmGen("res", "uiwin", "simse_ui_eventY")
fun winEventY(event: RawPtr): Int

@SmGen("res", "uiwin", "simse_ui_eventButton")
fun winEventButton(event: RawPtr): Int

@SmGen("res", "uiwin", "simse_ui_eventKey")
fun winEventKey(event: RawPtr): Int

@SmGen("res", "uiwin", "simse_ui_eventWheel")
fun winEventWheel(event: RawPtr): Int

@SmGen("res", "uiwin", "simse_ui_windowEvent")
fun winWindowEvent(event: RawPtr): Int

// A synthetic mouse event, built by the glue and handed to `SDL_PushEvent`.
@SmGen("res", "uiwin", "simse_ui_mouseEvent")
fun winMouseEvent(kind: Int, x: Int, y: Int, button: Int): RawPtr

// The pixel buffer and the readback buffer: one allocation each, owned here rather than by a
// Simse value, because a `Ui` struct is *copied* when it is returned or assigned - a `List<Int8>`
// field's storage would move while Skia still pointed at the old bytes. Handing the address out
// from C++ makes the canvas stable by construction.
@SmGen("res", "uiwin", "simse_ui_pixels")
fun winPixels(width: Int, height: Int): *Int8

@SmGen("res", "uiwin", "simse_ui_readPixels")
fun winReadPixels(width: Int, height: Int): *Int8

@SmGen("res", "uiwin", "simse_ui_writeBmp")
fun winWriteBmp(path: Str, pixels: *Int8, width: Int, height: Int): Int

// `alpha`, `red`, `green`, `blue` in 0..255 -> one ARGB `Int`. In C++ because `255 << 24` in
// Simse arithmetic is a needless signed-overflow question (`Int` is 32-bit signed).
@SmGen("res", "uiwin", "simse_ui_argb")
fun winArgb(alpha: Int, red: Int, green: Int, blue: Int): Int

// ---- constants, named after the headers they come from --------------------

val SDL_INIT_VIDEO: Int = 32
val SDL_WINDOWPOS_CENTERED: Int = 805240832
val SDL_WINDOW_RESIZABLE: Int = 32
val SDL_RENDERER_PRESENTVSYNC: Int = 4
val SDL_TEXTUREACCESS_STREAMING: Int = 1

// SDL names a pixel format by its *packed value*, so on a little-endian machine the bytes of
// SDL_PIXELFORMAT_ARGB8888 are B,G,R,A - the same order Skia's BGRA_8888 writes and the same a
// 32-bit BMP holds. The name that reads "BGRA" would be A,R,G,B in memory and swaps every channel.
val SDL_PIXELFORMAT_ARGB8888: Int = 372645892

val SDL_QUIT: Int = 256
val SDL_WINDOWEVENT: Int = 512
val SDL_KEYDOWN: Int = 768
val SDL_KEYUP: Int = 769
val SDL_MOUSEMOTION: Int = 1024
val SDL_MOUSEBUTTONDOWN: Int = 1025
val SDL_MOUSEBUTTONUP: Int = 1026
val SDL_MOUSEWHEEL: Int = 1027

val SDL_WINDOWEVENT_RESIZED: Int = 5
val SDL_WINDOWEVENT_SIZE_CHANGED: Int = 6
val SDL_WINDOWEVENT_CLOSE: Int = 14

// The keys this UI names. SDL reports its own `SDLK_*` code; these are the ones the example uses.
val SDLK_ESCAPE: Int = 27
val SDLK_RETURN: Int = 13
val SDLK_SPACE: Int = 32
val SDLK_1: Int = 49
val SDLK_2: Int = 50
val SDLK_3: Int = 51
val SDLK_f: Int = 102
val SDLK_r: Int = 114

// Skia's enums (sk_types.h), the members this wrapper uses.
val SK_COLORTYPE_BGRA_8888: Int = 6
val SK_ALPHATYPE_PREMUL: Int = 2
val SK_FILTER_QUALITY_NONE: Int = 0
val SK_STYLE_FILL: Int = 0
val SK_STYLE_STROKE: Int = 1
val SK_ENCODING_UTF8: Int = 0
val SK_FONT_EDGING_ANTIALIAS: Int = 1
