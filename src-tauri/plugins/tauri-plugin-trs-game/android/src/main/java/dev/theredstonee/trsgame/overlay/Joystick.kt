package dev.theredstonee.trsgame.overlay

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Lauf-Stick: Fingerlage relativ zur Mitte → gedrückte Tasten.
 * 8 Richtungen; ganz nach vorn über den Rand hinaus = Sprinten (Strg).
 */
object Joystick {
    /** Innerhalb davon passiert nichts (Anteil des Radius). */
    const val DEADZONE = 0.22f
    /** Ab diesem Anteil zählt eine Achse (≈ 22,5° bei gleichem Ausschlag). */
    const val AXIS = 0.38f
    /** Über den Rand hinaus nach vorn = Sprinten. */
    const val SPRINT_RADIUS = 1.05f
    /** Höchstens so weit (Grad) neben „genau vorn“ für Sprinten. */
    const val SPRINT_ANGLE = 30.0

    /** Normierter Ausschlag (−1..1 je Achse, Länge auf 1 begrenzt) und Rohlänge. */
    data class Vec(val x: Float, val y: Float, val raw: Float)

    fun vector(cx: Float, cy: Float, radius: Float, px: Float, py: Float): Vec {
        if (radius <= 0f) return Vec(0f, 0f, 0f)
        val dx = (px - cx) / radius
        val dy = (py - cy) / radius
        val len = sqrt(dx * dx + dy * dy)
        return if (len > 1f) Vec(dx / len, dy / len, len) else Vec(dx, dy, len)
    }

    /** Welche Tasten bei diesem Ausschlag gedrückt sind. */
    fun keys(v: Vec): Set<Int> {
        if (v.raw < DEADZONE) return emptySet()
        val out = HashSet<Int>()
        if (v.y < -AXIS) out.add(Glfw.KEY_W)
        if (v.y > AXIS) out.add(Glfw.KEY_S)
        if (v.x < -AXIS) out.add(Glfw.KEY_A)
        if (v.x > AXIS) out.add(Glfw.KEY_D)
        if (v.raw >= SPRINT_RADIUS) {
            // Winkel zu „genau vorn“ (−y): 0° = vorn.
            val angle = Math.toDegrees(atan2(v.x.toDouble(), (-v.y).toDouble()))
            if (abs(angle) <= SPRINT_ANGLE) out.add(Glfw.KEY_LEFT_CONTROL)
        }
        return out
    }
}
