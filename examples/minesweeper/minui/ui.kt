package minui

// The typed surface the app calls: one window with a Skia canvas over a byte buffer that SDL
// uploads and presents. Integer coordinates everywhere - the UI is pixel art, and the wrapper
// widens to the floats Skia asks for at the boundary, so no call site writes a float.
//
// A frame is: draw into the canvas (Skia), then `uiPresent` (SDL uploads the bytes as a streaming
// texture and blits it). A screenshot is that same upload plus a read-back through the renderer,
// so it captures the whole pipeline rather than Skia's buffer alone.
//
// Two decisions worth naming:
//
//  - **The pixel buffer is C++'s** (`winPixels`, `api.kt`), not a `List<Int8>` field here: a `Ui`
//    value is *copied* when it is returned or assigned, so a list field's storage would move out
//    from under the surface Skia built over it. A `Ui` is then small enough to copy freely.
//  - **One paint, reconfigured per call.** Skia's paint is a state object; the wrapper owns one
//    and every call sets the colour it needs, which keeps the API allocation-free and stateless
//    from the caller's side. `uiFrame`/`uiLine` switch it to a stroke and back to a fill, so a
//    fill after a stroke is still a fill.

data class Ui(
    var window: RawPtr,    // SDL_Window*
    var renderer: RawPtr,  // SDL_Renderer*
    var texture: RawPtr,   // SDL_Texture*, SDL_PIXELFORMAT_ARGB8888 + streaming
    var surface: RawPtr,   // sk_surface_t* over `pixels`
    var canvas: RawPtr,    // the surface's canvas: every draw goes here
    var paint: RawPtr,     // the one sk_paint_t, reconfigured per call
    var pixels: *Int8,     // width * height * 4, B,G,R,A in memory
    var width: Int,
    var height: Int,
    var mouseX: Int,       // the cursor's last known position (from the event stream)
    var mouseY: Int,
    var font: RawPtr,      // sk_font_t* for body text, null before `uiLoadFont`
    var bold: RawPtr,
    var fontSize: Int,
    var boldSize: Int,
    var sheet: RawPtr,     // the decoded atlas (sk_image_t*), null when none
    var sheetWidth: Int,
    var sheetHeight: Int,
    var cell: Int,         // atlas cell size, square
    var background: Int,   // ARGB
    var event: UiEvent     // what `uiPollEvent` last filled
)

// `SDL_GetError()`'s text, for the app's own message when `uiOpen` answers a window-less `Ui`.
fun uiFailed(): Str {
    return SDL_GetError()
}

fun uiIsOpen(ui: *Ui): Bool {
    return ui.window != null
}

// Open the window, the renderer, the streaming texture and the Skia surface over the buffer.
// A failure at any step closes what was opened and answers a `Ui` whose `window` is null (check it
// with `uiIsOpen`, and `uiFailed()` for the reason).
fun uiOpen(title: Str, width: Int, height: Int, resizable: Bool, vsync: Bool): Ui {
    var ui: Ui = Ui(
        null, null, null, null, null, null, null, width, height, 0, 0,
        null, null, 16, 20, null, 0, 0, 16,
        winArgb(255, 24, 32, 48), UiEvent(UiEventKind.None, 0, 0, Button.None, 0, 0, 0)
    )
    if (SDL_Init(SDL_INIT_VIDEO) != 0) {
        return ui
    }
    var windowFlags: Int = 0
    if (resizable) {
        windowFlags = SDL_WINDOW_RESIZABLE
    }
    ui.window = SDL_CreateWindow(
        title, SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
        width, height, windowFlags
    )
    if (ui.window == null) {
        SDL_Quit()
        return ui
    }
    // SDL's software blitter, not the accelerated renderer: with the direct3d renderer on this
    // machine some window sizes come back with red and blue traded - in the window itself, not just
    // in a readback (504x592 swapped, 512x512 not, and no other difference between the two runs;
    // the probes that show it are `build/colorprobe*.cpp`, git-ignored). The software path is
    // pixel-exact at every tested size, and all it is asked to do is blit: Skia has drawn every
    // pixel already. `vsync` is still passed on (the blitter ignores it; a caller that redraws only
    // when something changed - the app below - needs no pacing, and a caller that wants the display
    // to pace it needs the accelerated renderer).
    var rendererFlags: Int = SDL_RENDERER_SOFTWARE
    if (vsync) {
        rendererFlags = rendererFlags + SDL_RENDERER_PRESENTVSYNC
    }
    ui.renderer = SDL_CreateRenderer(ui.window, 0 - 1, rendererFlags)
    if (ui.renderer == null) {
        uiClose(*ui)
        return ui
    }
    ui.texture = SDL_CreateTexture(
        ui.renderer, SDL_PIXELFORMAT_ARGB8888,
        SDL_TEXTUREACCESS_STREAMING, width, height
    )
    if (ui.texture == null) {
        uiClose(*ui)
        return ui
    }
    ui.pixels = winPixels(width, height)
    var info: SkImageInfo = SkImageInfo(
        null, width, height, SK_COLORTYPE_BGRA_8888,
        SK_ALPHATYPE_PREMUL
    )
    ui.surface = sk_surface_new_raster_direct(*info, ui.pixels, width * 4, null, null, null)
    if (ui.surface == null) {
        uiClose(*ui)
        return ui
    }
    ui.canvas = sk_surface_get_canvas(ui.surface)
    ui.paint = sk_paint_new()
    if (ui.paint == null) {
        uiClose(*ui)
        return ui
    }
    sk_paint_set_antialias(ui.paint, true)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
    uiClear(*ui, ui.background)
    return ui
}

// Close everything `uiOpen` built. Safe on a half-open `Ui` (each handle is tested), and safe to
// call twice. Skia's typeface is deliberately left alone: the fonts hold their own references.
fun uiClose(ui: *Ui): Unit {
    if (ui.sheet != null) {
        sk_image_unref(ui.sheet)
        ui.sheet = null
    }
    if (ui.font != null) {
        sk_font_delete(ui.font)
        ui.font = null
    }
    if (ui.bold != null) {
        sk_font_delete(ui.bold)
        ui.bold = null
    }
    if (ui.paint != null) {
        sk_paint_delete(ui.paint)
        ui.paint = null
    }
    if (ui.surface != null) {
        sk_surface_unref(ui.surface)
        ui.surface = null
    }
    if (ui.texture != null) {
        SDL_DestroyTexture(ui.texture)
        ui.texture = null
    }
    if (ui.renderer != null) {
        SDL_DestroyRenderer(ui.renderer)
        ui.renderer = null
    }
    if (ui.window != null) {
        SDL_DestroyWindow(ui.window)
        ui.window = null
    }
    SDL_Quit()
}

// The bytes as a streaming texture, then to the screen through SDL's software blitter: 0.16 ms at
// 294x382, 0.37 ms at 744x496 (upload + clear + copy + present, a full-window blit, measured).
fun uiPresent(ui: *Ui): Unit {
    if (!uiIsOpen(ui)) {
        return
    }
    SDL_UpdateTexture(ui.texture, null, ui.pixels, ui.width * 4)
    SDL_RenderClear(ui.renderer)
    SDL_RenderCopy(ui.renderer, ui.texture, null, null)
    SDL_RenderPresent(ui.renderer)
}

// A new canvas size: the window, the texture, the buffer and the Skia surface over it. The buffer
// is C++'s and may have moved, so the surface is rebuilt on the new address (which is why this
// exists rather than a caller poking `ui.width`). The frame's own geometry is the caller's: a
// bigger board is a rebuilt tree, not a stretched one.
fun uiResize(ui: *Ui, width: Int, height: Int): Bool {
    if (!uiIsOpen(ui) || width <= 0 || height <= 0) {
        return false
    }
    SDL_SetWindowSize(ui.window, width, height)
    if (ui.texture != null) {
        SDL_DestroyTexture(ui.texture)
    }
    ui.texture = SDL_CreateTexture(
        ui.renderer, SDL_PIXELFORMAT_ARGB8888,
        SDL_TEXTUREACCESS_STREAMING, width, height
    )
    ui.width = width
    ui.height = height
    ui.pixels = winPixels(width, height)
    if (ui.surface != null) {
        sk_surface_unref(ui.surface)
    }
    var info: SkImageInfo = SkImageInfo(
        null, width, height, SK_COLORTYPE_BGRA_8888,
        SK_ALPHATYPE_PREMUL
    )
    ui.surface = sk_surface_new_raster_direct(*info, ui.pixels, width * 4, null, null, null)
    if (ui.surface == null) {
        return false
    }
    ui.canvas = sk_surface_get_canvas(ui.surface)
    return ui.texture != null
}

// What the renderer actually holds, as a 32-bit BMP: the verification path. The frame is
// re-uploaded first, so the answer is the last drawn canvas, not whatever was on screen.
fun uiScreenshot(ui: *Ui, path: Str): Bool {
    if (!uiIsOpen(ui)) {
        return false
    }
    SDL_UpdateTexture(ui.texture, null, ui.pixels, ui.width * 4)
    SDL_RenderClear(ui.renderer)
    SDL_RenderCopy(ui.renderer, ui.texture, null, null)
    val readback: *Int8 = winReadPixels(ui.width, ui.height)
    if (readback == null) {
        return false
    }
    val status: Int = SDL_RenderReadPixels(
        ui.renderer, null, SDL_PIXELFORMAT_ARGB8888,
        readback, ui.width * 4
    )
    SDL_RenderPresent(ui.renderer)
    if (status != 0) {
        return false
    }
    return winWriteBmp(path, readback, ui.width, ui.height) != 0
}

// Drain one platform event into `ui.event`. False when the queue is empty. Mouse positions are
// kept on the `Ui` as well, because hover is read at draw time rather than handled per event.
fun uiPollEvent(ui: *Ui): Bool {
    val buffer: RawPtr = winEventBuffer()
    if (SDL_PollEvent(buffer) == 0) {
        return false
    }
    val code: Int = winEventType(buffer)
    var e: UiEvent = UiEvent(UiEventKind.None, 0, 0, Button.None, 0, 0, 0)
    if (code == SDL_QUIT) {
        e.kind = UiEventKind.Close
    } else if (code == SDL_WINDOWEVENT) {
        val windowEvent: Int = winWindowEvent(buffer)
        if (windowEvent == SDL_WINDOWEVENT_CLOSE) {
            e.kind = UiEventKind.Close
        } else if (windowEvent == SDL_WINDOWEVENT_RESIZED || windowEvent == SDL_WINDOWEVENT_SIZE_CHANGED) {
            e.kind = UiEventKind.Resize
        }
    } else if (code == SDL_MOUSEMOTION) {
        e.kind = UiEventKind.MouseMove
        e.x = winEventX(buffer)
        e.y = winEventY(buffer)
        ui.mouseX = e.x
        ui.mouseY = e.y
    } else if (code == SDL_MOUSEBUTTONDOWN || code == SDL_MOUSEBUTTONUP) {
        if (code == SDL_MOUSEBUTTONDOWN) {
            e.kind = UiEventKind.MouseDown
        } else {
            e.kind = UiEventKind.MouseUp
        }
        e.x = winEventX(buffer)
        e.y = winEventY(buffer)
        e.button = Button.fromInt(winEventButton(buffer))
        e.buttons = uiButtons(ui)
        ui.mouseX = e.x
        ui.mouseY = e.y
    } else if (code == SDL_MOUSEWHEEL) {
        e.kind = UiEventKind.Wheel
        e.wheel = winEventWheel(buffer)
    } else if (code == SDL_KEYDOWN || code == SDL_KEYUP) {
        if (code == SDL_KEYDOWN) {
            e.kind = UiEventKind.KeyDown
        } else {
            e.kind = UiEventKind.KeyUp
        }
        e.key = winEventKey(buffer)
    }
    ui.event = e
    return true
}

// The buttons held right now, as a mask (`MaskLeft`, `MaskMiddle`, `MaskRight`). This is what
// tells a chord from a plain click: SDL reports one button per event, the mask reports all of them.
fun uiButtons(ui: *Ui): Int {
    return SDL_GetMouseState(*ui.mouseX, *ui.mouseY)
}

// Feed a synthetic mouse event through SDL's own queue: the scripted-input path (the app's
// `--script` click entries). It is the real path - the event is written as SDL writes
// it, polled as SDL polls it - so what a scripted run proves is what a real click would do. The
// *mask* stays the real device's (`uiButtons`), which is why a control should take its pressed
// state from the down/up events rather than the mask; the mask is for chording.
fun uiPushMouse(kind: UiEventKind, x: Int, y: Int, button: Button): Bool {
    var code: Int = 0
    if (kind == UiEventKind.MouseMove) {
        code = SDL_MOUSEMOTION
    } else if (kind == UiEventKind.MouseDown) {
        code = SDL_MOUSEBUTTONDOWN
    } else if (kind == UiEventKind.MouseUp) {
        code = SDL_MOUSEBUTTONUP
    } else {
        return false
    }
    return SDL_PushEvent(winMouseEvent(code, x, y, button.toInt())) != 0
}

// Feed a synthetic key event through SDL's own queue, for the scripted `:K` entries: the same bytes
// SDL writes for a real keystroke (the loop reads `keysym.sym`, which is the ASCII value for letters
// and digits and SDL's own number for the rest). A script can therefore press R, Escape or 1/2/3
// without a hand on the keyboard - which is also the only way a headless run can press anything.
fun uiPushKey(kind: UiEventKind, key: Int): Bool {
    var code: Int = 0
    if (kind == UiEventKind.KeyDown) {
        code = SDL_KEYDOWN
    } else if (kind == UiEventKind.KeyUp) {
        code = SDL_KEYUP
    } else {
        return false
    }
    return SDL_PushEvent(winKeyEvent(code, key)) != 0
}

// ---- colours --------------------------------------------------------------

fun uiColor(alpha: Int, red: Int, green: Int, blue: Int): Int {
    return winArgb(alpha, red, green, blue)
}

fun uiRgb(red: Int, green: Int, blue: Int): Int {
    return winArgb(255, red, green, blue)
}

// ---- drawing --------------------------------------------------------------

// Every primitive takes the tree's own `Rect` (`Int` fields): the four floats Skia reads are built
// from it once, in the glue (`winRect`), so no call site here converts anything and no float type
// is named above the glue at all.

fun uiClear(ui: *Ui, color: Int): Unit {
    sk_canvas_clear(ui.canvas, color)
}

fun uiFill(ui: *Ui, rect: Rect, color: Int): Unit {
    sk_paint_set_color(ui.paint, color)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
    sk_canvas_draw_rect(ui.canvas, winRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height), ui.paint)
}

fun uiRoundFill(ui: *Ui, rect: Rect, radius: Int, color: Int): Unit {
    sk_paint_set_color(ui.paint, color)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
    sk_canvas_draw_round_rect(
        ui.canvas, winRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height),
        radius, radius, ui.paint
    )
}

// A one-pixel outline. The paint is a stroke only for this call, then back to a fill.
fun uiOutline(ui: *Ui, rect: Rect, color: Int): Unit {
    sk_paint_set_color(ui.paint, color)
    sk_paint_set_style(ui.paint, SK_STYLE_STROKE)
    sk_paint_set_stroke_width(ui.paint, 1)
    sk_canvas_draw_rect(ui.canvas, winRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height), ui.paint)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
}

// A line is not a rect, so it takes its two points.
fun uiLine(ui: *Ui, x0: Int, y0: Int, x1: Int, y1: Int, color: Int): Unit {
    sk_paint_set_color(ui.paint, color)
    sk_paint_set_style(ui.paint, SK_STYLE_STROKE)
    sk_paint_set_stroke_width(ui.paint, 1)
    sk_canvas_draw_line(ui.canvas, x0, y0, x1, y1, ui.paint)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
}

fun uiCircle(ui: *Ui, centerX: Int, centerY: Int, radius: Int, color: Int): Unit {
    sk_paint_set_color(ui.paint, color)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
    sk_canvas_draw_circle(ui.canvas, centerX, centerY, radius, ui.paint)
}

// A pie slice: the arc of `rect`, `startDegrees` clockwise from three o'clock, swept by
// `sweepDegrees`, filled to the centre.
fun uiPie(ui: *Ui, rect: Rect, startDegrees: Int, sweepDegrees: Int, color: Int): Unit {
    sk_paint_set_color(ui.paint, color)
    sk_paint_set_style(ui.paint, SK_STYLE_FILL)
    sk_canvas_draw_arc(
        ui.canvas, winRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height),
        startDegrees, sweepDegrees, true, ui.paint
    )
}

// ---- text -----------------------------------------------------------------

// The two faces the UI uses, from the *system's* fonts (`DirectWrite` lives inside
// libSkiaSharp: no .ttf ships with the example). Sizes are in pixels.
fun uiLoadFont(ui: *Ui, family: Str, size: Int, boldSize: Int): Bool {
    val manager: RawPtr = sk_fontmgr_create_default()
    if (manager == null) {
        return false
    }
    val style: RawPtr = sk_fontstyle_new(400, 5, 0) // weight normal, width normal, slant upright
    val face: RawPtr = sk_fontmgr_match_family_style(manager, family, style)
    sk_fontstyle_delete(style)
    sk_fontmgr_unref(manager)
    if (face == null) {
        return false
    }
    ui.fontSize = size
    ui.boldSize = boldSize
    ui.font = uiMakeFont(face, size, false)
    ui.bold = uiMakeFont(face, boldSize, true)
    return ui.font != null
}

// One face at one size: grayscale anti-aliasing (subpixel off), because the text composites into
// our own buffer rather than onto a screen's ClearType surface.
fun uiMakeFont(face: RawPtr, size: Int, bold: Bool): RawPtr {
    val font: RawPtr = sk_font_new_with_values(face, size, 1.0, 0.0)
    if (font == null) {
        return null
    }
    sk_font_set_subpixel(font, false)
    sk_font_set_edging(font, SK_FONT_EDGING_ANTIALIAS)
    if (bold) {
        sk_font_set_embolden(font, true)
    }
    return font
}

// One line, its *baseline* at `baselineY` - Skia's own convention, so nothing here guesses at line
// height. `uiTextBox` is the "somewhere in this box" version, which is what a button wants.
fun uiText(ui: *Ui, x: Int, baselineY: Int, text: Str, color: Int, bold: Bool): Unit {
    var font: RawPtr = ui.font
    if (bold) {
        font = ui.bold
    }
    if (font == null) {
        return
    }
    sk_paint_set_color(ui.paint, color)
    sk_canvas_draw_simple_text(
        ui.canvas, text, text.size(), SK_ENCODING_UTF8, x, baselineY,
        font, ui.paint
    )
}

// How wide `text` draws: Skia measures it (`sk_font_measure_text`), rounded up to a whole pixel.
// Centred text and any layout the app does on top of it come from this.
fun uiTextWidth(ui: *Ui, text: Str, bold: Bool): Int {
    var font: RawPtr = ui.font
    if (bold) {
        font = ui.bold
    }
    if (font == null) {
        return 0
    }
    val width: Float32 = sk_font_measure_text(
        font, text, text.size(), SK_ENCODING_UTF8,
        null, ui.paint
    )
    return width + 0.5
}

// A line centred in a box: horizontally by measurement, vertically by half the face's size (the
// baseline sits about a third of the size below the middle of a line of text).
fun uiTextBox(ui: *Ui, rect: Rect, text: Str, color: Int, bold: Bool): Unit {
    val textWidth: Int = uiTextWidth(ui, text, bold)
    var size: Int = ui.fontSize
    if (bold) {
        size = ui.boldSize
    }
    val textX: Int = rect.x + (rect.width - textWidth) / 2
    val baseline: Int = rect.y + rect.height / 2 + size / 3
    uiText(ui, textX, baseline, text, color, bold)
}

// ---- images ---------------------------------------------------------------

// Decode a PNG (Skia does it; SDL would need SDL_image for that) into the atlas this UI draws
// from. `cell` is the sprite size: square cells, laid out row-major, `uiSprite` picks one.
fun uiLoadSheet(ui: *Ui, path: Str, cell: Int): Bool {
    val encoded: RawPtr = sk_data_new_from_file(path)
    if (encoded == null) {
        return false
    }
    val image: RawPtr = sk_image_new_from_encoded(encoded)
    sk_data_unref(encoded)
    if (image == null) {
        return false
    }
    if (ui.sheet != null) {
        sk_image_unref(ui.sheet)
    }
    ui.sheet = image
    ui.sheetWidth = sk_image_get_width(image)
    ui.sheetHeight = sk_image_get_height(image)
    ui.cell = cell
    return true
}

// One atlas cell (row-major from the top-left) drawn into `rect`. Nearest sampling, because
// whole-number scaling of pixel art must stay crisp.
fun uiSprite(ui: *Ui, index: Int, rect: Rect): Unit {
    if (ui.sheet == null || ui.cell <= 0) {
        return
    }
    val columns: Int = ui.sheetWidth / ui.cell
    if (columns <= 0) {
        return
    }
    val column: Int = index % columns
    val row: Int = index / columns
    sk_paint_set_filter_quality(ui.paint, SK_FILTER_QUALITY_NONE)
    sk_canvas_draw_image_rect(
        ui.canvas, ui.sheet,
        winRect(column * ui.cell, row * ui.cell, column * ui.cell + ui.cell, row * ui.cell + ui.cell),
        winRect(rect.x, rect.y, rect.x + rect.width, rect.y + rect.height),
        null, ui.paint
    )
}

// ---- clock ----------------------------------------------------------------

// SDL's own millisecond clock, for a game timer (nothing in minui reads a clock by itself).
fun uiTicks(): Int {
    return SDL_GetTicks()
}

fun uiDelay(milliseconds: Int): Unit {
    SDL_Delay(milliseconds)
}
