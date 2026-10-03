package dev.theredstonee.trsgame.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets

/**
 * Das Overlay über der Spielfläche: zeichnet die Knöpfe (TRS-Stil, Pixel-
 * Symbole) und gibt Berührungen an [OverlayController] weiter. Im
 * Bearbeiten-Modus verschiebt/vergrößert es stattdessen über [EditorModel].
 */
@SuppressLint("ViewConstructor")
class TouchOverlayView(
    context: Context,
    private val sink: InputSink,
    layout: Layout,
    /** Feste Ränder (Pixel); `null` = aus den Fenster-Insets (Notch). */
    private val fixedInsets: Insets? = null,
) : View(context) {
    val controller = OverlayController(sink, layout)
    val policy = VisibilityPolicy()
    var editor: EditorModel? = null
        private set
    private var lastGrabbed = sink.isGrabbed()
    private val density = resources.displayMetrics.density

    /** Wird gerufen, wenn das Overlay wegen Controller/Maus verschwindet oder zurückkommt. */
    var onVisibilityChanged: ((hidden: Boolean) -> Unit)? = null
    /** Auswahl im Editor geändert (Werkzeugleiste aktualisieren). */
    var onEditorChanged: (() -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pixel = Paint().apply { style = Paint.Style.FILL; color = COLOR_TEXT }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_TEXT
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val dashed = DashPathEffect(floatArrayOf(8f * density / 2, 5f * density / 2), 0f)
    private val tmp = RectF()

    init {
        controller.density = density
        controller.onHaptic = { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
        isClickable = true
        isFocusable = false
    }

    // --- Größe und Ränder --------------------------------------------------------------

    private fun currentInsets(): Insets {
        fixedInsets?.let { return it }
        val wi = rootWindowInsets ?: return Insets()
        return when {
            Build.VERSION.SDK_INT >= 30 -> {
                val i = wi.getInsets(WindowInsets.Type.displayCutout())
                Insets(i.left.toFloat(), i.top.toFloat(), i.right.toFloat(), i.bottom.toFloat())
            }
            Build.VERSION.SDK_INT >= 28 -> {
                val c = wi.displayCutout ?: return Insets()
                Insets(c.safeInsetLeft.toFloat(), c.safeInsetTop.toFloat(), c.safeInsetRight.toFloat(), c.safeInsetBottom.toFloat())
            }
            else -> Insets()
        }
    }

    /** Ränder für `-Dtrs.safeInsets` (l,t,r,b in Pixeln). */
    fun safeInsetsText(): String = Geometry.formatInsets(currentInsets())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        controller.setSize(w.toFloat(), h.toFloat(), currentInsets())
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        controller.setSize(width.toFloat(), height.toFloat(), currentInsets())
        invalidate()
        return super.onApplyWindowInsets(insets)
    }

    // --- Zustand von außen --------------------------------------------------------------

    fun setLayout(layout: Layout) {
        controller.setLayout(layout)
        invalidate()
    }

    fun startEditing() {
        controller.cancelAll()
        editor = EditorModel(controller.layout)
        invalidate()
        onEditorChanged?.invoke()
    }

    fun stopEditing() {
        editor = null
        invalidate()
        onEditorChanged?.invoke()
    }

    /** Controller oder echte Maus benutzt → ausblenden, alles loslassen. */
    fun hardwareInput() {
        if (policy.onHardwareInput()) {
            controller.cancelAll()
            invalidate()
            onVisibilityChanged?.invoke(true)
        }
    }

    // --- Eingabe ----------------------------------------------------------------------

    private fun isHardware(event: MotionEvent): Boolean {
        val tool = event.getToolType(0)
        return tool == MotionEvent.TOOL_TYPE_MOUSE ||
            event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK) ||
            event.isFromSource(InputDevice.SOURCE_GAMEPAD)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isHardware(event)) hardwareInput()
        // Nicht verbrauchen: die Engine bekommt Maus und Controller selbst.
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isHardware(event)) {
            hardwareInput()
            return false
        }
        if (policy.onTouch()) onVisibilityChanged?.invoke(false)
        val ed = editor
        if (ed != null) return editorTouch(ed, event)

        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                controller.down(event.getPointerId(i), event.getX(i), event.getY(i), now)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                controller.move(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                controller.up(event.getPointerId(i), event.getX(i), event.getY(i), now)
            }
            MotionEvent.ACTION_CANCEL -> controller.cancelAll()
        }
        scheduleTick()
        invalidate()
        return true
    }

    private fun editorTouch(ed: EditorModel, event: MotionEvent): Boolean {
        val handle = HANDLE_DP * density
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> ed.begin(event.x, event.y, controller.safe, handle).also { onEditorChanged?.invoke() }
            MotionEvent.ACTION_MOVE -> ed.dragTo(event.x, event.y, controller.safe)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ed.end()
        }
        invalidate()
        return true
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (controller.tick(SystemClock.uptimeMillis())) scheduleTick()
            invalidate()
        }
    }
    private var tickScheduled = false

    private fun scheduleTick() {
        if (tickScheduled) return
        tickScheduled = true
        postOnAnimation(ticker)
    }

    /** Menü auf/zu ändert, welche Knöpfe sichtbar sind – kurz nachsehen. */
    private val grabWatch = object : Runnable {
        override fun run() {
            val g = sink.isGrabbed()
            if (g != lastGrabbed) {
                lastGrabbed = g
                invalidate()
            }
            postDelayed(this, GRAB_POLL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        post(grabWatch)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(grabWatch)
        removeCallbacks(ticker)
        controller.cancelAll()
        controller.held.releaseAll()
        super.onDetachedFromWindow()
    }

    // --- Zeichnen ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ed = editor
        if (ed == null && policy.hidden) return
        val safe = controller.safe
        val layout = ed?.layout ?: controller.layout
        val grabbed = sink.isGrabbed()
        if (ed != null) drawGrid(canvas, safe, ed.grid)
        for (b in layout.buttons) {
            if (ed == null && !b.visible(grabbed)) continue
            val alpha = if (ed != null) maxOf(0.35f, b.opacity) else b.opacity
            drawButton(canvas, b, safe, alpha)
        }
        val sel = ed?.selected ?: return
        val r = Geometry.toPx(sel, safe)
        stroke.color = COLOR_ACCENT
        stroke.strokeWidth = 2f * density
        stroke.pathEffect = dashed
        tmp.set(r.x - 3 * density, r.y - 3 * density, r.x + r.w + 3 * density, r.y + r.h + 3 * density)
        canvas.drawRect(tmp, stroke)
        stroke.pathEffect = null
        val hb = ed.handleBox(sel, safe, HANDLE_DP * density)
        fill.color = COLOR_ACCENT
        canvas.drawRect(hb.x, hb.y, hb.x + hb.w, hb.y + hb.h, fill)
    }

    private fun drawGrid(canvas: Canvas, safe: Box, on: Boolean) {
        canvas.drawColor(0x66000000)
        if (!on) return
        stroke.color = 0x22FFFFFF
        stroke.strokeWidth = 1f
        for (i in 1 until 20) {
            val x = safe.x + safe.w * i / 20f
            canvas.drawLine(x, safe.y, x, safe.y + safe.h, stroke)
        }
        for (i in 1 until 10) {
            val y = safe.y + safe.h * i / 10f
            canvas.drawLine(safe.x, y, safe.x + safe.w, y, stroke)
        }
    }

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) * alpha).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun drawButton(canvas: Canvas, b: Button, safe: Box, alpha: Float) {
        val r = Geometry.toPx(b, safe)
        val c = Geometry.circleOf(r)
        val down = b.id in controller.pressed
        val latched = b.id in controller.latched
        val border = when {
            down -> COLOR_ACCENT
            latched -> COLOR_LAMP
            else -> COLOR_BORDER
        }
        stroke.pathEffect = null
        stroke.strokeWidth = 2.5f * density

        if (b.isJoystick) {
            fill.color = withAlpha(COLOR_FILL, alpha * 0.6f)
            canvas.drawCircle(c.cx, c.cy, c.r, fill)
            stroke.color = withAlpha(COLOR_TEXT, alpha)
            canvas.drawCircle(c.cx, c.cy, c.r, stroke)
            val v = controller.knobs[b.id]
            val kx = c.cx + (v?.x ?: 0f) * c.r * 0.6f
            val ky = c.cy + (v?.y ?: 0f) * c.r * 0.6f
            fill.color = withAlpha(COLOR_KNOB, maxOf(alpha, if (down) 0.9f else 0f))
            canvas.drawCircle(kx, ky, c.r * 0.4f, fill)
            stroke.color = withAlpha(COLOR_ACCENT, maxOf(alpha, if (down) 0.9f else 0f))
            canvas.drawCircle(kx, ky, c.r * 0.4f, stroke)
            return
        }
        if (b.isHotbar) {
            fill.color = withAlpha(COLOR_ACCENT, alpha * 0.5f)
            canvas.drawRect(r.x, r.y, r.x + r.w, r.y + r.h, fill)
            stroke.color = withAlpha(COLOR_ACCENT_LIGHT, alpha)
            stroke.pathEffect = dashed
            canvas.drawRect(r.x, r.y, r.x + r.w, r.y + r.h, stroke)
            stroke.pathEffect = null
            stroke.strokeWidth = 1.5f * density
            for (i in 1 until 9) {
                val x = r.x + r.w * i / 9f
                canvas.drawLine(x, r.y + r.h * 0.2f, x, r.y + r.h * 0.8f, stroke)
            }
            return
        }

        fill.color = withAlpha(if (down) COLOR_FILL_DOWN else COLOR_FILL, alpha)
        stroke.color = withAlpha(border, maxOf(alpha, if (down || latched) 1f else 0f))
        val box: Float
        if (b.shape == Shape.ROUND) {
            canvas.drawCircle(c.cx, c.cy, c.r, fill)
            canvas.drawCircle(c.cx, c.cy, c.r, stroke)
            box = c.r * 2
        } else {
            val rad = 6f * density / 2
            tmp.set(r.x, r.y, r.x + r.w, r.y + r.h)
            canvas.drawRoundRect(tmp, rad, rad, fill)
            canvas.drawRoundRect(tmp, rad, rad, stroke)
            box = minOf(r.w, r.h)
        }

        val icon = b.icon
        if (icon != null) {
            val s = box * 0.5f / Icons.SIZE
            val ox = c.cx - s * Icons.SIZE / 2
            val oy = c.cy - s * Icons.SIZE / 2
            pixel.color = withAlpha(COLOR_TEXT, minOf(1f, alpha + 0.25f))
            for ((x, y) in Icons.pixels(icon)) {
                canvas.drawRect(ox + x * s, oy + y * s, ox + (x + 1) * s + 0.3f, oy + (y + 1) * s + 0.3f, pixel)
            }
            val badge = b.badge
            if (badge != null) {
                text.color = withAlpha(COLOR_LAMP, minOf(1f, alpha + 0.25f))
                text.textSize = maxOf(8f * density / 2, minOf(r.w, r.h) * 0.22f)
                text.textAlign = Paint.Align.RIGHT
                val bx = if (b.shape == Shape.ROUND) c.cx + c.r * 0.62f else r.x + r.w - 2 * density
                val by = if (b.shape == Shape.ROUND) c.cy + c.r * 0.72f else r.y + r.h - 2 * density
                canvas.drawText(badge, bx, by, text)
                text.textAlign = Paint.Align.CENTER
            }
        } else {
            val label = b.label ?: return
            text.color = withAlpha(COLOR_TEXT, minOf(1f, alpha + 0.25f))
            val count = maxOf(1, label.codePointCount(0, label.length))
            text.textSize = maxOf(9f * density / 2, minOf(r.h * 0.32f, r.w * 1.6f / count))
            canvas.drawText(label, c.cx, c.cy - (text.descent() + text.ascent()) / 2, text)
        }
    }

    companion object {
        /** Farben wie im Launcher (base-900, base-700, redstone-500, lamp-400). */
        const val COLOR_FILL = 0xFF17171E.toInt()
        const val COLOR_FILL_DOWN = 0xFF331614.toInt()
        const val COLOR_BORDER = 0xFF333343.toInt()
        const val COLOR_TEXT = 0xFFF3F3F8.toInt()
        const val COLOR_ACCENT = 0xFFE0281E.toInt()
        const val COLOR_ACCENT_LIGHT = 0xFFFF5A4D.toInt()
        const val COLOR_LAMP = 0xFFFFB84D.toInt()
        const val COLOR_KNOB = 0xFF252531.toInt()
        const val HANDLE_DP = 22f
        const val GRAB_POLL_MS = 200L
    }
}
