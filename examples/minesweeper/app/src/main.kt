package minesweeperapp

import minui

// minui v0: the window and the canvas, driven from Simse - the first *visible* win of the
// Minesweeper example (`../minui.md`, shot 1). The tree and the game come next; what this file
// proves is the platform layer end to end: an SDL window whose input reaches Simse, a Skia canvas
// whose pixels SDL presents, text from a system font (no .ttf ships), a PNG decoded and scaled,
// and a screenshot written by the renderer's own read-back.
//
//   minesweeperdemo.exe                        run until Escape (or the window's close button)
//   minesweeperdemo.exe --frames 3             draw three frames and leave (a smoke test)
//   minesweeperdemo.exe --screenshot out.bmp   write what the renderer presented
//   minesweeperdemo.exe --sheet tiles.png      decode an atlas for the sprite path (16px cells)

fun inside(x: Int, y: Int, width: Int, height: Int, px: Int, py: Int): Bool {
    return px >= x && px < x + width && py >= y && py < y + height
}

// A fake button: chrome that answers the pointer. Idle, hovered and pressed are three different
// fills, so a screenshot says which state the frame was drawn in.
fun drawButton(
    ui: *Ui, x: Int, y: Int, width: Int, height: Int, label: Str,
    hovered: Bool, pressed: Bool
): Unit {
    var face: Int = uiRgb(58, 90, 138)
    if (hovered) {
        face = uiRgb(78, 116, 172)
    }
    if (pressed) {
        face = uiRgb(44, 68, 104)
    }
    uiRoundFill(ui, x, y, width, height, 6, face)
    uiTextBox(ui, x, y, width, height, label, uiRgb(232, 240, 248), false)
}

fun drawFrame(ui: *Ui, frames: Int, sheetLoaded: Bool, mouseDown: Bool): Unit {
    uiClear(ui, uiRgb(24, 32, 48))

    // Text: a bold title and a body line, both from the system's Segoe UI.
    uiText(ui, 20, 44, "minui v0 - SDL2 window, Skia canvas", uiRgb(232, 240, 248), true)
    uiText(ui, 20, 68, "text from a system font, no .ttf shipped", uiRgb(143, 211, 244), false)

    // A 9x9 board: 16-pixel art drawn at 32 pixels when an atlas was loaded (nearest sampling, so
    // it stays crisp), flat colours when not.
    var row: Int = 0
    while (row < 9) {
        var column: Int = 0
        while (column < 9) {
            val x: Int = 20 + column * 32
            val y: Int = 88 + row * 32
            if (sheetLoaded) {
                uiSprite(ui, row * 9 + column, x, y, 32)
            } else {
                val shade: Int = 40 + ((row * 9 + column) % 4) * 12
                uiRoundFill(ui, x + 2, y + 2, 28, 28, 4, uiRgb(shade, shade + 40, 120))
                uiOutline(ui, x + 2, y + 2, 28, 28, uiRgb(16, 22, 34))
            }
            column = column + 1
        }
        row = row + 1
    }

    // A panel whose two buttons follow the pointer: hover and press come straight from the events
    // and the button mask, which is all v0 needs to show that input arrives.
    uiRoundFill(ui, 336, 88, 284, 292, 8, uiRgb(32, 44, 64))
    uiOutline(ui, 336, 88, 284, 292, uiRgb(58, 74, 100))
    val pointerX: Int = ui.mouseX
    val pointerY: Int = ui.mouseY
    val firstHover: Bool = inside(356, 108, 244, 44, pointerX, pointerY)
    drawButton(ui, 356, 108, 244, 44, "NEW GAME", firstHover, firstHover && mouseDown)
    val secondHover: Bool = inside(356, 164, 244, 44, pointerX, pointerY)
    drawButton(ui, 356, 164, 244, 44, "DIFFICULTY: BEGINNER", secondHover, secondHover && mouseDown)

    // A pie and the frame's own state: the counter and the cursor make the screenshot explain
    // itself (why it is deterministic for a fixed --frames is the point of the smoke test).
    val pieX: Int = 356
    val pieY: Int = 232
    uiPie(ui, pieX, pieY, 96, 96, 0 - 90, 270, uiRgb(232, 193, 90))
    uiText(ui, pieX + 112, pieY + 34, "frames " + frames.toString(), uiRgb(232, 240, 248), false)
    uiText(
        ui, pieX + 112, pieY + 60, "cursor " + pointerX.toString() + "," + pointerY.toString(),
        uiRgb(143, 211, 244), false
    )
    uiText(ui, 20, 386, "escape quits - click the buttons", uiRgb(110, 126, 148), false)
}

fun main(args: List<Str>): Int {
    var maxFrames: Int = 0 - 1
    var screenshotPath: Str = ""
    var sheetPath: Str = ""
    var clickX: Int = 0 - 1
    var clickY: Int = 0 - 1
    var holdClick: Bool = false
    var index: Int = 0
    while (index < args.size()) {
        if (args[index] == "--frames" && index + 1 < args.size()) {
            val parsed: Opt<Int> = args[index + 1].toInt()
            if (parsed.hasValue()) {
                maxFrames = parsed.value()
            }
            index = index + 1
        } else if (args[index] == "--screenshot" && index + 1 < args.size()) {
            screenshotPath = args[index + 1]
            index = index + 1
        } else if (args[index] == "--sheet" && index + 1 < args.size()) {
            sheetPath = args[index + 1]
            index = index + 1
        } else if ((args[index] == "--click" || args[index] == "--press") && index + 1 < args.size()) {
            holdClick = args[index] == "--press"
            val parts: List<Str> = args[index + 1].split(",")
            if (parts.size() == 2) {
                val pointX: Opt<Int> = parts[0].toInt()
                val pointY: Opt<Int> = parts[1].toInt()
                if (pointX.hasValue() && pointY.hasValue()) {
                    clickX = pointX.value()
                    clickY = pointY.value()
                }
            }
            index = index + 1
        }
        index = index + 1
    }

    var ui: Ui = uiOpen("minui v0 - SDL2 window, Skia canvas", 640, 400, false, true)
    if (!uiIsOpen(*ui)) {
        println("window open failed: " + uiFailed())
        return 1
    }
    val fontLoaded: Bool = uiLoadFont(*ui, "Segoe UI", 18, 26)
    var sheetLoaded: Bool = false
    if (sheetPath.size() > 0) {
        sheetLoaded = uiLoadSheet(*ui, sheetPath, 16)
    }
    println("minui v0: 640x400 font=" + fontLoaded.toString() + " sheet=" + sheetLoaded.toString())

    // The scripted pointer, if any: it lands on a button so a screenshot shows hover - and, with
    // `--press`, the held state - with nobody at the mouse. The events go through SDL's queue, so
    // what they exercise is the real input path.
    if (clickX >= 0) {
        uiPushMouse(UiEventKind.MouseMove, clickX, clickY, Button.None)
        uiPushMouse(UiEventKind.MouseDown, clickX, clickY, Button.Left)
        if (!holdClick) {
            uiPushMouse(UiEventKind.MouseUp, clickX, clickY, Button.Left)
        }
    }

    var frames: Int = 0
    var running: Bool = true
    var mouseDown: Bool = false
    while (running) {
        while (uiPollEvent(*ui)) {
            val e: UiEvent = ui.event
            if (e.kind == UiEventKind.Close) {
                running = false
            } else if (e.kind == UiEventKind.KeyDown && e.key == SDLK_ESCAPE) {
                running = false
            } else if (e.kind == UiEventKind.MouseDown || e.kind == UiEventKind.MouseUp) {
                // The pressed visual comes from the down/up events, not the mask: a scripted click
                // has no device holding a button, and the mask is for chording anyway.
                mouseDown = e.kind == UiEventKind.MouseDown
                val button: Button = e.button
                println(
                    "  pointer " + e.x.toString() + "," + e.y.toString()
                            + " button=" + button.toInt().toString()
                            + " mask=" + e.buttons.toString()
                )
            }
        }
        drawFrame(*ui, frames, sheetLoaded, mouseDown)
        uiPresent(*ui)
        frames = frames + 1
        if (maxFrames >= 0 && frames >= maxFrames) {
            running = false
        }
    }
    println("frames drawn: " + frames.toString())
    if (screenshotPath.size() > 0) {
        val written: Bool = uiScreenshot(*ui, screenshotPath)
        println("screenshot " + screenshotPath + ": " + written.toString())
    }
    uiClose(*ui)
    return 0
}
