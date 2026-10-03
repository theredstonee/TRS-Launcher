package dev.theredstonee.trsgame.overlay

import kotlin.math.abs

/** Gedrückte Tasten/Maustasten mit Zähler – zwei Quellen für dieselbe Taste stören sich nicht. */
class HeldInputs(private val sink: InputSink) {
    private val keys = HashMap<Int, Int>()
    private val mouse = HashMap<Int, Int>()

    /** GLFW-Modifikatoren aus den gehaltenen Tasten (Shift/Strg/Alt). */
    fun mods(): Int {
        var m = 0
        if (isDown(Glfw.KEY_LEFT_SHIFT) || isDown(Glfw.KEY_RIGHT_SHIFT)) m = m or Glfw.MOD_SHIFT
        if (isDown(Glfw.KEY_LEFT_CONTROL) || isDown(Glfw.KEY_RIGHT_CONTROL)) m = m or Glfw.MOD_CONTROL
        if (isDown(Glfw.KEY_LEFT_ALT) || isDown(Glfw.KEY_RIGHT_ALT)) m = m or Glfw.MOD_ALT
        return m
    }

    fun isDown(key: Int): Boolean = (keys[key] ?: 0) > 0
    fun isMouseDown(button: Int): Boolean = (mouse[button] ?: 0) > 0

    fun keyDown(key: Int) {
        val n = (keys[key] ?: 0) + 1
        keys[key] = n
        if (n == 1) sink.sendKey(key, 0, true, mods())
    }

    fun keyUp(key: Int) {
        val n = (keys[key] ?: 0) - 1
        if (n < 0) return
        if (n == 0) {
            keys.remove(key)
            sink.sendKey(key, 0, false, mods())
        } else {
            keys[key] = n
        }
    }

    fun mouseDown(button: Int) {
        val n = (mouse[button] ?: 0) + 1
        mouse[button] = n
        if (n == 1) sink.sendMouseButton(button, true)
    }

    fun mouseUp(button: Int) {
        val n = (mouse[button] ?: 0) - 1
        if (n < 0) return
        if (n == 0) {
            mouse.remove(button)
            sink.sendMouseButton(button, false)
        } else {
            mouse[button] = n
        }
    }

    fun tapKey(key: Int) {
        keyDown(key)
        keyUp(key)
    }

    fun click(button: Int) {
        mouseDown(button)
        mouseUp(button)
    }

    /** Alles loslassen (Overlay weg, Fokus verloren). */
    fun releaseAll() {
        for (k in keys.keys.toList()) {
            keys.remove(k)
            sink.sendKey(k, 0, false, mods())
        }
        for (b in mouse.keys.toList()) {
            mouse.remove(b)
            sink.sendMouseButton(b, false)
        }
    }
}

/** Zeiten und Wege der Gesten (Millisekunden, dp). */
object GestureTiming {
    /** So lange ruhig halten = Halten-Aktion (benutzen/abbauen). */
    const val HOLD_MS = 300L
    /** Im Menü: so lange halten = Rechtsklick. */
    const val LONG_PRESS_MS = 450L
    /** Bis zu diesem Weg zählt ein Finger als „ruhig“. */
    const val SLOP_DP = 8f
    /** Maus-Einheiten je dp Wischen bei Empfindlichkeit 1. */
    const val CAMERA_PER_DP = 3f
    /** Im Menü: dp Fingerweg je Mausrad-Raste (zwei Finger). */
    const val SCROLL_STEP_DP = 28f
}

/**
 * Ein Finger auf der freien Fläche. Im Spiel: wischen = umsehen, tippen =
 * angreifen bzw. setzen, ruhig halten = benutzen bzw. abbauen. Im Menü:
 * Zeiger folgt dem Finger, tippen = Linksklick, ziehen = mit gedrückter
 * Taste ziehen, lange halten = Rechtsklick.
 */
class CameraGesture(
    private val held: HeldInputs,
    private val sink: InputSink,
    val grabbed: Boolean,
    private val gestures: Gestures,
    private val density: Float,
    x: Float,
    y: Float,
    private val start: Long,
) {
    enum class State { PENDING, MOVED, HOLDING, DRAGGING, DONE }

    var state = State.PENDING
        private set
    private val startX = x
    private val startY = y
    private var lastX = x
    private var lastY = y
    private val slopPx = GestureTiming.SLOP_DP * density

    init {
        if (!grabbed) sink.moveMouseAbsolute(x, y)
    }

    private val holdButton get() = if (gestures.holdUse) Glfw.MOUSE_RIGHT else Glfw.MOUSE_LEFT
    private val tapButton get() = if (gestures.tapAttack) Glfw.MOUSE_LEFT else Glfw.MOUSE_RIGHT

    private fun beyondSlop(x: Float, y: Float) = abs(x - startX) > slopPx || abs(y - startY) > slopPx

    fun move(x: Float, y: Float) {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        if (grabbed) {
            if (dx != 0f || dy != 0f) {
                val k = GestureTiming.CAMERA_PER_DP * gestures.cameraSensitivity / density
                sink.moveMouseRelative(dx * k, dy * k)
            }
            if (state == State.PENDING && beyondSlop(x, y)) state = State.MOVED
        } else {
            sink.moveMouseAbsolute(x, y)
            if (state == State.PENDING && beyondSlop(x, y)) {
                state = State.DRAGGING
                held.mouseDown(Glfw.MOUSE_LEFT)
            }
        }
    }

    /** Zeitgesteuerte Übergänge; `true` = weiter Ticks nötig. */
    fun tick(now: Long): Boolean {
        if (state != State.PENDING) return false
        val elapsed = now - start
        if (grabbed && elapsed >= GestureTiming.HOLD_MS) {
            state = State.HOLDING
            held.mouseDown(holdButton)
            return false
        }
        if (!grabbed && elapsed >= GestureTiming.LONG_PRESS_MS) {
            state = State.DONE
            held.click(Glfw.MOUSE_RIGHT)
            return false
        }
        return true
    }

    fun up(now: Long) {
        tick(now)
        when (state) {
            State.PENDING -> if (grabbed) held.click(tapButton) else held.click(Glfw.MOUSE_LEFT)
            State.HOLDING -> held.mouseUp(holdButton)
            State.DRAGGING -> held.mouseUp(Glfw.MOUSE_LEFT)
            State.MOVED, State.DONE -> {}
        }
        state = State.DONE
    }

    /** Abbrechen ohne Klick (z. B. zweiter Finger im Menü). */
    fun cancel() {
        when (state) {
            State.HOLDING -> held.mouseUp(holdButton)
            State.DRAGGING -> held.mouseUp(Glfw.MOUSE_LEFT)
            else -> {}
        }
        state = State.DONE
    }
}
