package dev.theredstonee.trs.game.engine

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Eingebautes Minimal-Overlay, bis das Touch-Steuerungs-Modul da ist: Laufen
 * (WASD), Springen, Schleichen, Angreifen/Benutzen, Inventar, Chat, Pause,
 * Tastatur – freie Fläche dreht die Kamera (im Spiel) bzw. ist die Maus (Menü).
 */
class FallbackOverlay : OverlayProvider {
    private var view: OverlayView? = null

    override fun createOverlay(activity: Activity, input: GameInput, profile: String?): View =
        OverlayView(activity, input).also { view = it }

    override fun onGrabChanged(grabbed: Boolean) {
        view?.post { view?.invalidate() }
    }

    override fun onHardwareInput(active: Boolean) {
        view?.post { view?.visibility = if (active) View.INVISIBLE else View.VISIBLE }
    }

    override fun onSafeInsets(left: Int, top: Int, right: Int, bottom: Int) {
        view?.setInsets(left, top, right, bottom)
    }

    private class Button(
        val label: String,
        /** Mitte in Bruchteilen (0..1) der Breite/Höhe, Größe in Bruchteilen der Höhe. */
        val cx: Float, val cy: Float, val size: Float,
        val key: Int = 0, val mouse: Int = -1,
        val toggle: Boolean = false, val inMenu: Boolean = false, val keyboard: Boolean = false,
    ) {
        val rect = RectF()
        var pointer = -1
        var latched = false
    }

    @SuppressLint("ViewConstructor")
    private class OverlayView(context: Context, private val input: GameInput) : View(context) {
        private val buttons = listOf(
            Button("W", 0.13f, 0.60f, 0.15f, key = Glfw.KEY_W),
            Button("A", 0.05f, 0.76f, 0.15f, key = Glfw.KEY_A),
            Button("S", 0.13f, 0.92f, 0.15f, key = Glfw.KEY_S),
            Button("D", 0.21f, 0.76f, 0.15f, key = Glfw.KEY_D),
            Button("⇧", 0.13f, 0.76f, 0.13f, key = Glfw.KEY_LEFT_SHIFT, toggle = true),
            Button("␣", 0.92f, 0.82f, 0.18f, key = Glfw.KEY_SPACE),
            Button("⚔", 0.80f, 0.62f, 0.15f, mouse = Glfw.MOUSE_LEFT),
            Button("✋", 0.92f, 0.56f, 0.15f, mouse = Glfw.MOUSE_RIGHT),
            Button("E", 0.80f, 0.86f, 0.13f, key = Glfw.KEY_E),
            Button("Q", 0.70f, 0.90f, 0.11f, key = Glfw.KEY_Q),
            Button("T", 0.36f, 0.08f, 0.11f, key = Glfw.KEY_T),
            Button("⌨", 0.50f, 0.08f, 0.11f, inMenu = true, keyboard = true),
            Button("Ⅱ", 0.95f, 0.09f, 0.12f, key = Glfw.KEY_ESCAPE, inMenu = true),
        )
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
        private val active = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 200, 30, 30) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 3f; color = Color.argb(150, 255, 255, 255)
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
        private var insets = IntArray(4)
        private var keyboardShown = false

        /** Zeiger für Kamera/Maus (nicht auf einem Knopf). */
        private var lookPointer = -1
        private var lastX = 0f
        private var lastY = 0f

        fun setInsets(l: Int, t: Int, r: Int, b: Int) {
            insets = intArrayOf(l, t, r, b)
            post { requestLayout(); invalidate() }
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            super.onLayout(changed, left, top, right, bottom)
            val w = (width - insets[0] - insets[2]).toFloat()
            val h = (height - insets[1] - insets[3]).toFloat()
            for (b in buttons) {
                val half = b.size * h / 2
                val x = insets[0] + b.cx * w
                val y = insets[1] + b.cy * h
                b.rect.set(x - half, y - half, x + half, y + half)
            }
            text.textSize = h * 0.05f
        }

        private fun visible(b: Button) = input.isGrabbed() || b.inMenu

        override fun onDraw(canvas: Canvas) {
            for (b in buttons) {
                if (!visible(b)) continue
                val pressed = b.pointer != -1 || b.latched
                canvas.drawRoundRect(b.rect, 16f, 16f, if (pressed) active else fill)
                canvas.drawRoundRect(b.rect, 16f, 16f, stroke)
                canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() + text.textSize / 3, text)
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    down(event.getPointerId(i), event.getX(i), event.getY(i))
                }
                MotionEvent.ACTION_MOVE -> {
                    val i = event.findPointerIndex(lookPointer)
                    if (i >= 0) look(event.getX(i), event.getY(i))
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(event.getPointerId(event.actionIndex))
                MotionEvent.ACTION_CANCEL -> {
                    for (b in buttons) if (b.pointer != -1) release(b)
                    if (lookPointer != -1 && !input.isGrabbed()) input.sendMouseButton(Glfw.MOUSE_LEFT, false)
                    lookPointer = -1
                }
            }
            invalidate()
            return true
        }

        private fun down(id: Int, x: Float, y: Float) {
            val hit = buttons.firstOrNull { visible(it) && it.pointer == -1 && it.rect.contains(x, y) }
            if (hit != null) {
                hit.pointer = id
                press(hit)
                return
            }
            if (lookPointer != -1) return
            lookPointer = id
            lastX = x
            lastY = y
            if (!input.isGrabbed()) {
                input.moveMouseAbsolute(x, y)
                input.sendMouseButton(Glfw.MOUSE_LEFT, true)
            }
        }

        private fun look(x: Float, y: Float) {
            if (input.isGrabbed()) input.moveMouseRelative(x - lastX, y - lastY) else input.moveMouseAbsolute(x, y)
            lastX = x
            lastY = y
        }

        private fun up(id: Int) {
            val hit = buttons.firstOrNull { it.pointer == id }
            if (hit != null) {
                release(hit)
                return
            }
            if (id == lookPointer) {
                if (!input.isGrabbed()) input.sendMouseButton(Glfw.MOUSE_LEFT, false)
                lookPointer = -1
            }
        }

        private fun press(b: Button) {
            when {
                b.keyboard -> {
                    keyboardShown = !keyboardShown
                    input.showKeyboard(keyboardShown)
                }
                b.toggle -> {
                    b.latched = !b.latched
                    input.sendKey(b.key, 0, b.latched, 0)
                }
                b.mouse >= 0 -> input.sendMouseButton(b.mouse, true)
                else -> input.sendKey(b.key, 0, true, 0)
            }
        }

        private fun release(b: Button) {
            b.pointer = -1
            when {
                b.keyboard || b.toggle -> {}
                b.mouse >= 0 -> input.sendMouseButton(b.mouse, false)
                else -> input.sendKey(b.key, 0, false, 0)
            }
        }
    }
}
