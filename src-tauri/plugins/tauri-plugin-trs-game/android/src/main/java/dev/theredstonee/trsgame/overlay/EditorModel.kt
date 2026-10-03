package dev.theredstonee.trsgame.overlay

/**
 * Editor im Spiel (Pause → „Steuerung bearbeiten“) ohne Android: Knöpfe
 * wählen, ziehen, an der Ecke vergrößern, hinzufügen, entfernen, ändern.
 * Gleiche Schritte wie `moveButton`/`resizeButton`/`addButton` in controls.ts.
 */
class EditorModel(start: Layout) {
    var layout: Layout = start
        private set
    private val original = start
    var selectedId: String? = null
    var grid = true

    val selected: Button? get() = layout.buttons.firstOrNull { it.id == selectedId }
    val dirty: Boolean get() = layout != original

    private var drag: Drag? = null

    private class Drag(val id: String, val resize: Boolean, val start: Layout, val x: Float, val y: Float)

    /** Griff zum Vergrößern: Quadrat an der rechten unteren Ecke (Pixel). */
    fun handleBox(b: Button, safe: Box, handlePx: Float): Box {
        val r = Geometry.toPx(b, safe)
        return Box(r.x + r.w - handlePx / 2, r.y + r.h - handlePx / 2, handlePx, handlePx)
    }

    /** Finger runter: Griff des gewählten Knopfs, sonst ein Knopf, sonst Auswahl weg. */
    fun begin(px: Float, py: Float, safe: Box, handlePx: Float) {
        val sel = selected
        if (sel != null && handleBox(sel, safe, handlePx).contains(px, py)) {
            drag = Drag(sel.id, true, layout, px, py)
            return
        }
        val hit = layout.buttons.lastOrNull { Geometry.hit(it, safe, px, py) || Geometry.toPx(it, safe).contains(px, py) }
        selectedId = hit?.id
        drag = hit?.let { Drag(it.id, false, layout, px, py) }
    }

    fun dragTo(px: Float, py: Float, safe: Box) {
        val d = drag ?: return
        if (safe.w <= 0f || safe.h <= 0f) return
        val dx = (px - d.x) / safe.w
        val dy = (py - d.y) / safe.h
        layout = if (d.resize) resize(d.start, d.id, dx, dy, grid) else move(d.start, d.id, dx, dy, grid)
    }

    fun end() {
        drag = null
    }

    fun add(action: Action, label: String?, icon: String?): String? {
        if (layout.buttons.size >= MAX_BUTTONS) return null
        val taken = layout.buttons.map { it.id }.toSet()
        var n = layout.buttons.size + 1
        while ("b$n" in taken) n++
        val id = "b$n"
        var x = 0.47f
        var y = 0.42f
        while (layout.buttons.any { kotlin.math.abs(it.x - x) < 0.005f && kotlin.math.abs(it.y - y) < 0.005f } && y < 0.8f) {
            x += 0.02f
            y += 0.04f
        }
        val big = action is Action.Joystick
        val button = Button(
            id = id,
            label = label?.takeIf { it.isNotBlank() }?.take(MAX_LABEL_CHARS) ?: if (icon == null) "?" else null,
            icon = icon,
            x = x,
            y = y,
            w = if (big) 0.19f else 0.06f,
            h = if (big) 0.4f else 0.12f,
            shape = if (action is Action.Fn && action.special == Special.HOTBAR_SWIPE) Shape.RECT else Shape.ROUND,
            action = action,
        )
        val r = Geometry.clamp(Box(x, y, button.w, button.h))
        layout = layout.copy(buttons = layout.buttons + button.copy(x = r.x, y = r.y))
        selectedId = id
        return id
    }

    fun remove() {
        val id = selectedId ?: return
        if (layout.buttons.size <= 1) return
        layout = layout.copy(buttons = layout.buttons.filter { it.id != id })
        selectedId = null
    }

    fun update(change: (Button) -> Button) {
        val id = selectedId ?: return
        layout = layout.copy(buttons = layout.buttons.map { if (it.id == id) change(it) else it })
    }

    fun setOpacity(v: Float) = update { it.copy(opacity = v.coerceIn(MIN_OPACITY, 1f)) }

    fun cycleShape() = update { it.copy(shape = if (it.shape == Shape.ROUND) Shape.RECT else Shape.ROUND) }

    fun setAction(action: Action, label: String?, icon: String?) = update {
        it.copy(action = action, label = label?.take(MAX_LABEL_CHARS) ?: if (icon == null) it.label ?: "?" else null, icon = icon, toggle = false)
    }

    /** Größe in Schritten ändern (Knöpfe +/−), Mitte bleibt. */
    fun scale(factor: Float) = update {
        val w = it.w * factor
        val h = it.h * factor
        val r = Geometry.clamp(Box(it.x + (it.w - w) / 2, it.y + (it.h - h) / 2, w, h))
        it.copy(x = r.x, y = r.y, w = r.w, h = r.h)
    }

    /** Zum Speichern: geprüft, und fertige Layouts gelten danach als geändert. */
    fun result(): Layout {
        val out = layout.copy(builtinRev = null, name = layout.name.trim())
        Layout.validate(out)
        return out
    }

    companion object {
        fun move(l: Layout, id: String, dx: Float, dy: Float, grid: Boolean): Layout = l.copy(
            buttons = l.buttons.map { b ->
                if (b.id != id) return@map b
                var x = b.x + dx
                var y = b.y + dy
                if (grid) {
                    x = Geometry.snap(x)
                    y = Geometry.snap(y)
                }
                val r = Geometry.clamp(Box(x, y, b.w, b.h))
                b.copy(x = r.x, y = r.y, w = r.w, h = r.h)
            },
        )

        fun resize(l: Layout, id: String, dw: Float, dh: Float, grid: Boolean): Layout = l.copy(
            buttons = l.buttons.map { b ->
                if (b.id != id) return@map b
                var w = b.w + dw
                var h = b.h + dh
                if (grid) {
                    w = Geometry.snap(w)
                    h = Geometry.snap(h)
                }
                w = minOf(w, 1f - b.x)
                h = minOf(h, 1f - b.y)
                val r = Geometry.clamp(Box(b.x, b.y, w, h))
                b.copy(x = r.x, y = r.y, w = r.w, h = r.h)
            },
        )
    }
}
