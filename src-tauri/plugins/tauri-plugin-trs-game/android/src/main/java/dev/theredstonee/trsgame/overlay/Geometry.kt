package dev.theredstonee.trsgame.overlay

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Ränder (Notch, Gestenleiste) in Pixeln. */
data class Insets(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f)

/** Rechteck in Pixeln oder Anteilen. */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val cx: Float get() = x + w / 2
    val cy: Float get() = y + h / 2
    fun contains(px: Float, py: Float): Boolean = px >= x && px <= x + w && py >= y && py <= y + h
}

/** Kreis eines runden Knopfs: Durchmesser = kürzere Seite, mittig. */
data class Circle(val cx: Float, val cy: Float, val r: Float)

/** Umrechnung Anteile ↔ Pixel (gleich wie `toPx`/`fromPx` in controls.ts). */
object Geometry {
    const val GRID_STEP = 0.01f

    /** Sichere Fläche – darauf beziehen sich die Anteile eines Layouts. */
    fun safeRect(width: Float, height: Float, insets: Insets = Insets()): Box =
        Box(insets.left, insets.top, max(0f, width - insets.left - insets.right), max(0f, height - insets.top - insets.bottom))

    fun toPx(b: Box, safe: Box): Box = Box(safe.x + b.x * safe.w, safe.y + b.y * safe.h, b.w * safe.w, b.h * safe.h)

    fun toPx(b: Button, safe: Box): Box = toPx(Box(b.x, b.y, b.w, b.h), safe)

    fun fromPx(px: Box, safe: Box): Box {
        if (safe.w <= 0f || safe.h <= 0f) return Box(0f, 0f, MIN_SIZE, MIN_SIZE)
        return Box((px.x - safe.x) / safe.w, (px.y - safe.y) / safe.h, px.w / safe.w, px.h / safe.h)
    }

    fun circleOf(px: Box): Circle = Circle(px.cx, px.cy, min(px.w, px.h) / 2)

    /** Treffer? Runde Knöpfe nur im (etwas großzügigeren) Kreis. */
    fun hit(b: Button, safe: Box, px: Float, py: Float): Boolean {
        val r = toPx(b, safe)
        if (b.shape == Shape.ROUND && !b.isHotbar) {
            val c = circleOf(r)
            val dx = px - c.cx
            val dy = py - c.cy
            val rr = c.r * 1.1f
            return dx * dx + dy * dy <= rr * rr
        }
        return r.contains(px, py)
    }

    fun snap(v: Float, step: Float = GRID_STEP): Float = (v / step).roundToLong() * step

    private fun round4(v: Float): Float = (v * 10000f).roundToLong() / 10000f

    /** Hält ein Rechteck (Anteile) vollständig in der sicheren Fläche. */
    fun clamp(r: Box): Box {
        val w = min(1f, max(MIN_SIZE, r.w))
        val h = min(1f, max(MIN_SIZE, r.h))
        val x = min(1f - w, max(0f, r.x))
        val y = min(1f - h, max(0f, r.y))
        return Box(round4(x), round4(y), round4(w), round4(h))
    }

    /** Insets aus `-Dtrs.safeInsets=l,t,r,b` bzw. gleichem Text (Pixel). */
    fun parseInsets(text: String?): Insets? {
        val parts = text?.split(',')?.map { it.trim().toFloatOrNull() } ?: return null
        if (parts.size != 4 || parts.any { it == null || it < 0f || !it.isFinite() }) return null
        return Insets(parts[0]!!, parts[1]!!, parts[2]!!, parts[3]!!)
    }

    fun formatInsets(i: Insets): String = listOf(i.left, i.top, i.right, i.bottom).joinToString(",") { it.roundToLong().toString() }
}
