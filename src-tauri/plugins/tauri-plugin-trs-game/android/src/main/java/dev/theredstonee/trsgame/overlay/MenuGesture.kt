package dev.theredstonee.trsgame.overlay

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Eigener Mauszeiger im Menü (Spiel zeichnet auf dem Handy keinen sichtbaren).
 * Merkt sich die zuletzt gesendete Position in View-Pixeln, immer innerhalb
 * der View. Start: Mitte; bleibt beim Wechsel Spiel ↔ Menü erhalten.
 */
class MenuCursor {
    var x = 0f
        private set
    var y = 0f
        private set
    private var w = 0f
    private var h = 0f
    private var placed = false

    fun setBounds(width: Float, height: Float) {
        w = width
        h = height
        if (!placed && w > 0f && h > 0f) {
            x = w / 2
            y = h / 2
            placed = true
        } else {
            x = clampX(x)
            y = clampY(y)
        }
    }

    private fun clampX(v: Float) = if (w <= 0f) v else v.coerceIn(0f, w - 1f)
    private fun clampY(v: Float) = if (h <= 0f) v else v.coerceIn(0f, h - 1f)

    /** Zeiger setzen und dem Spiel schicken (immer absolut – Overlay und Spiel bleiben gleich). */
    fun moveTo(nx: Float, ny: Float, sink: InputSink) {
        x = clampX(nx)
        y = clampY(ny)
        sink.moveMouseAbsolute(x, y)
    }

    fun moveBy(dx: Float, dy: Float, sink: InputSink) = moveTo(x + dx, y + dy, sink)
}

/**
 * Ein Finger auf der freien Fläche im Menü (Maus nicht gefangen):
 * - Wischen (vor Ablauf von [GestureTiming.LONG_PRESS_MS] über [GestureTiming.SLOP_DP]
 *   bewegt) = Touchpad: Zeiger bewegt sich relativ, schnelle Bewegung wird verstärkt.
 * - Kurz tippen (losgelassen vor der Haltezeit, kaum bewegt) = Zeiger springt
 *   auf den Punkt, Linksklick.
 * - Ruhig halten bis zur Haltezeit = Zeiger springt auf den Punkt („scharf“).
 *   Danach loslassen ohne Bewegung = Rechtsklick dort; danach bewegen =
 *   Linke Taste gedrückt, Zeiger folgt dem Finger, Loslassen lässt die Taste los.
 */
class MenuGesture(
    private val held: HeldInputs,
    private val sink: InputSink,
    private val cursor: MenuCursor,
    private val density: Float,
    x: Float,
    y: Float,
    private val start: Long,
    /** Haltezeit erreicht (Vibration). */
    private val onArmed: () -> Unit = {},
) {
    enum class State { PENDING, SWIPING, ARMED, DRAGGING, DONE }

    var state = State.PENDING
        private set
    private val startX = x
    private val startY = y
    private var lastX = x
    private var lastY = y
    private var lastTime = start
    /** Geglättete Geschwindigkeit in dp/ms. */
    private var speed = 0f
    private val slopPx = GestureTiming.SLOP_DP * density

    private fun beyondSlop(x: Float, y: Float) = abs(x - startX) > slopPx || abs(y - startY) > slopPx

    /** [now] < 0 = Zeit unbekannt → ohne Verstärkung. */
    fun move(x: Float, y: Float, now: Long = -1L) {
        when (state) {
            State.PENDING -> if (beyondSlop(x, y)) {
                state = State.SWIPING
                // Weg innerhalb der Toleranz nicht verschlucken (1:1).
                cursor.moveBy(x - startX, y - startY, sink)
                remember(x, y, now)
            }
            State.SWIPING -> {
                val dx = x - lastX
                val dy = y - lastY
                if (dx == 0f && dy == 0f) return
                val g = if (now < 0L) 1f else {
                    val dt = (now - lastTime).coerceAtLeast(MIN_DT_MS).toFloat()
                    val inst = sqrt(dx * dx + dy * dy) / density / dt
                    speed = if (speed == 0f) inst else speed * 0.5f + inst * 0.5f
                    gain(speed)
                }
                cursor.moveBy(dx * g, dy * g, sink)
                remember(x, y, now)
            }
            State.ARMED -> if (beyondSlop(x, y)) {
                state = State.DRAGGING
                held.mouseDown(Glfw.MOUSE_LEFT)
                cursor.moveTo(x, y, sink)
            }
            State.DRAGGING -> cursor.moveTo(x, y, sink)
            State.DONE -> {}
        }
    }

    private fun remember(x: Float, y: Float, now: Long) {
        lastX = x
        lastY = y
        if (now >= 0L) lastTime = now
    }

    /** Zeitgesteuerter Übergang; `true` = weiter Ticks nötig. */
    fun tick(now: Long): Boolean {
        if (state != State.PENDING) return false
        if (now - start < GestureTiming.LONG_PRESS_MS) return true
        state = State.ARMED
        cursor.moveTo(startX, startY, sink)
        onArmed()
        return false
    }

    fun up(now: Long) {
        tick(now)
        when (state) {
            State.PENDING -> {
                cursor.moveTo(startX, startY, sink)
                held.click(Glfw.MOUSE_LEFT)
            }
            State.ARMED -> held.click(Glfw.MOUSE_RIGHT)
            State.DRAGGING -> held.mouseUp(Glfw.MOUSE_LEFT)
            State.SWIPING, State.DONE -> {}
        }
        state = State.DONE
    }

    /** Abbrechen ohne Klick (zweiter Finger, System-Geste). */
    fun cancel() {
        if (state == State.DRAGGING) held.mouseUp(Glfw.MOUSE_LEFT)
        state = State.DONE
    }

    companion object {
        /** Bis zu dieser Geschwindigkeit (dp/ms) 1:1 – für genaues Zielen. */
        const val SLOW_DP_PER_MS = 0.25f
        /** Ab hier volle Verstärkung. */
        const val FAST_DP_PER_MS = 1.5f
        const val MAX_GAIN = 3f
        private const val MIN_DT_MS = 4L

        /** Touchpad-Verstärkung: langsam 1, dazwischen linear, schnell [MAX_GAIN]. */
        fun gain(speedDpPerMs: Float): Float {
            if (speedDpPerMs <= SLOW_DP_PER_MS) return 1f
            if (speedDpPerMs >= FAST_DP_PER_MS) return MAX_GAIN
            val t = (speedDpPerMs - SLOW_DP_PER_MS) / (FAST_DP_PER_MS - SLOW_DP_PER_MS)
            return 1f + t * (MAX_GAIN - 1f)
        }
    }
}
