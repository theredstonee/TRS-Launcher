package dev.theredstonee.trsgame.overlay

import kotlin.math.abs
import kotlin.math.floor

/**
 * Herz des Overlays ohne Android: ordnet Finger Knöpfen, Sticks oder der
 * freien Fläche zu und schickt die passenden Eingaben an [InputSink].
 * Die View ruft nur `down/move/up/cancelAll/tick` und zeichnet den Zustand.
 */
class OverlayController(private val sink: InputSink, layout: Layout) {
    var layout: Layout = layout
        private set
    private var width = 0f
    private var height = 0f
    private var insets = Insets()
    /** Pixel je dp (Android `displayMetrics.density`). */
    var density = 1f
    var safe: Box = Box(0f, 0f, 0f, 0f)
        private set

    val held = HeldInputs(sink)
    /** Eingerastete Knöpfe (Toggle). */
    val latched = HashSet<String>()
    /** Gerade gedrückte Knöpfe (zum Hervorheben). */
    val pressed = HashSet<String>()
    /** Stick-Auslenkung je Knopf (normiert) – zum Zeichnen des Knaufs. */
    val knobs = HashMap<String, Joystick.Vec>()
    var keyboardShown = false
        private set
    /** Wird bei jedem Knopfdruck gerufen (Vibration), wenn im Layout an. */
    var onHaptic: (() -> Unit)? = null

    private sealed class Target {
        class Btn(val button: Button, var lastX: Float, var lastY: Float) : Target()
        class Stick(val button: Button, var keys: Set<Int>) : Target()
        class Hotbar(val button: Button, var slot: Int) : Target()
        class Camera(val gesture: CameraGesture) : Target()
        /** Zweiter Finger im Menü: Mausrad. */
        class Scroll(var lastY: Float, var rest: Float) : Target()
        object Ignored : Target()
    }

    private val pointers = HashMap<Int, Target>()
    private var lastTick = 0L

    fun setSize(w: Float, h: Float, insets: Insets = this.insets) {
        width = w
        height = h
        this.insets = insets
        safe = Geometry.safeRect(w, h, insets)
    }

    /** Neues Layout (Editor gespeichert, Profil gewechselt): alles loslassen. */
    fun setLayout(layout: Layout) {
        cancelAll()
        latched.clear()
        this.layout = layout
    }

    fun visibleButtons(grabbed: Boolean = sink.isGrabbed()): List<Button> = layout.buttons.filter { it.visible(grabbed) }

    private fun buttonAt(x: Float, y: Float, grabbed: Boolean): Button? =
        // Oben liegende (später gezeichnete) Knöpfe gewinnen.
        visibleButtons(grabbed).lastOrNull { Geometry.hit(it, safe, x, y) }

    fun down(pointer: Int, x: Float, y: Float, now: Long) {
        if (pointers.containsKey(pointer)) up(pointer, x, y, now)
        val grabbed = sink.isGrabbed()
        val button = buttonAt(x, y, grabbed)
        val target: Target = when {
            button == null -> freeArea(x, y, now, grabbed)
            button.isJoystick -> Target.Stick(button, emptySet()).also { stickMove(it, x, y) }
            button.isHotbar -> Target.Hotbar(button, -1).also { hotbarAt(it, x, initial = true) }
            else -> Target.Btn(button, x, y).also { press(button) }
        }
        if (button != null) {
            pressed.add(button.id)
            if (layout.gestures.haptics) onHaptic?.invoke()
        }
        pointers[pointer] = target
    }

    private fun freeArea(x: Float, y: Float, now: Long, grabbed: Boolean): Target {
        val cameras = pointers.values.filterIsInstance<Target.Camera>()
        if (!grabbed && cameras.isNotEmpty()) {
            // Zweiter Finger im Menü: der erste klickt nicht mehr, beide scrollen nicht doppelt.
            cameras.forEach { it.gesture.cancel() }
            return Target.Scroll(y, 0f)
        }
        if (grabbed && cameras.any { it.gesture.grabbed }) return Target.Ignored
        return Target.Camera(CameraGesture(held, sink, grabbed, layout.gestures, density, x, y, now))
    }

    fun move(pointer: Int, x: Float, y: Float) {
        when (val t = pointers[pointer] ?: return) {
            is Target.Btn -> {
                if (t.button.passThrough && sink.isGrabbed()) {
                    val k = GestureTiming.CAMERA_PER_DP * layout.gestures.cameraSensitivity / density
                    val dx = x - t.lastX
                    val dy = y - t.lastY
                    if (dx != 0f || dy != 0f) sink.moveMouseRelative(dx * k, dy * k)
                }
                t.lastX = x
                t.lastY = y
            }
            is Target.Stick -> stickMove(t, x, y)
            is Target.Hotbar -> if (layout.gestures.swipeHotbar) hotbarAt(t, x, initial = false)
            is Target.Camera -> t.gesture.move(x, y)
            is Target.Scroll -> {
                t.rest += y - t.lastY
                t.lastY = y
                val step = GestureTiming.SCROLL_STEP_DP * density
                while (abs(t.rest) >= step) {
                    val dir = if (t.rest > 0) 1f else -1f
                    sink.scroll(0f, dir)
                    t.rest -= dir * step
                }
            }
            Target.Ignored -> {}
        }
    }

    fun up(pointer: Int, x: Float, y: Float, now: Long) {
        val t = pointers.remove(pointer) ?: return
        when (t) {
            is Target.Btn -> {
                release(t.button)
                pressed.remove(t.button.id)
            }
            is Target.Stick -> {
                t.keys.forEach { held.keyUp(it) }
                knobs.remove(t.button.id)
                pressed.remove(t.button.id)
            }
            is Target.Hotbar -> pressed.remove(t.button.id)
            is Target.Camera -> {
                t.gesture.move(x, y)
                t.gesture.up(now)
            }
            is Target.Scroll, Target.Ignored -> {}
        }
    }

    /** Finger weg ohne Klicks (System-Geste, Overlay versteckt). Eingerastetes bleibt. */
    fun cancelAll() {
        for ((_, t) in pointers) {
            when (t) {
                is Target.Btn -> release(t.button)
                is Target.Stick -> t.keys.forEach { held.keyUp(it) }
                is Target.Camera -> t.gesture.cancel()
                else -> {}
            }
        }
        pointers.clear()
        pressed.clear()
        knobs.clear()
    }

    /** Pro Bild aufrufen, solange `true` zurückkommt (Halten, Kamera-Stick). */
    fun tick(now: Long): Boolean {
        val dt = if (lastTick == 0L) 0f else ((now - lastTick).coerceIn(0L, 100L)) / 1000f
        lastTick = now
        var more = false
        for (t in pointers.values) {
            when (t) {
                is Target.Camera -> more = t.gesture.tick(now) || more
                is Target.Stick -> if ((t.button.action as Action.Joystick).camera) {
                    val v = knobs[t.button.id]
                    if (v != null && v.raw >= Joystick.DEADZONE) {
                        // Ausschlag 1 = CAMERA_STICK_DP dp pro Sekunde als Wisch-Weg.
                        val k = GestureTiming.CAMERA_PER_DP * layout.gestures.cameraSensitivity * CAMERA_STICK_DP * dt
                        sink.moveMouseRelative(v.x * k, v.y * k)
                    }
                    more = true
                }
                else -> {}
            }
        }
        if (!more) lastTick = 0L
        return more
    }

    // --- Knöpfe --------------------------------------------------------------------

    private fun pressAction(button: Button) {
        when (val a = button.action) {
            is Action.Key -> {
                a.chord.forEach { held.keyDown(it) }
                held.keyDown(a.key)
            }
            is Action.Mouse -> {
                a.chord.forEach { held.keyDown(it) }
                held.mouseDown(a.button)
            }
            is Action.Toggle -> held.keyDown(a.key)
            else -> {}
        }
    }

    private fun releaseAction(button: Button) {
        when (val a = button.action) {
            is Action.Key -> {
                held.keyUp(a.key)
                a.chord.asReversed().forEach { held.keyUp(it) }
            }
            is Action.Mouse -> {
                held.mouseUp(a.button)
                a.chord.asReversed().forEach { held.keyUp(it) }
            }
            is Action.Toggle -> held.keyUp(a.key)
            else -> {}
        }
    }

    private fun isLatching(button: Button) = button.action is Action.Toggle || (button.toggle && (button.action is Action.Key || button.action is Action.Mouse))

    private fun press(button: Button) {
        if (isLatching(button)) {
            if (latched.remove(button.id)) releaseAction(button) else {
                latched.add(button.id)
                pressAction(button)
            }
            return
        }
        when (val a = button.action) {
            is Action.Key, is Action.Mouse -> pressAction(button)
            is Action.Fn -> when (a.special) {
                Special.KEYBOARD -> setKeyboard(!keyboardShown)
                Special.MENU -> held.keyDown(Glfw.KEY_ESCAPE)
                Special.TRS_MENU -> held.keyDown(Glfw.KEY_RIGHT_SHIFT)
                Special.EMOTE_WHEEL -> held.keyDown(Glfw.KEY_R)
                Special.CHAT -> {
                    held.tapKey(Glfw.KEY_T)
                    setKeyboard(true)
                }
                Special.SCROLL_UP -> sink.scroll(0f, 1f)
                Special.SCROLL_DOWN -> sink.scroll(0f, -1f)
                Special.HOTBAR_SWIPE -> {}
            }
            else -> {}
        }
    }

    private fun release(button: Button) {
        if (isLatching(button)) return
        when (val a = button.action) {
            is Action.Key, is Action.Mouse -> releaseAction(button)
            is Action.Fn -> when (a.special) {
                Special.MENU -> held.keyUp(Glfw.KEY_ESCAPE)
                Special.TRS_MENU -> held.keyUp(Glfw.KEY_RIGHT_SHIFT)
                Special.EMOTE_WHEEL -> held.keyUp(Glfw.KEY_R)
                else -> {}
            }
            else -> {}
        }
    }

    fun setKeyboard(show: Boolean) {
        keyboardShown = show
        sink.showKeyboard(show)
    }

    private fun stickMove(t: Target.Stick, x: Float, y: Float) {
        val r = Geometry.toPx(t.button, safe)
        val c = Geometry.circleOf(r)
        val v = Joystick.vector(c.cx, c.cy, c.r, x, y)
        knobs[t.button.id] = v
        if ((t.button.action as Action.Joystick).camera) return
        val next = Joystick.keys(v)
        (t.keys - next).forEach { held.keyUp(it) }
        (next - t.keys).forEach { held.keyDown(it) }
        t.keys = next
    }

    /** Hotbar-Platz unter dem Finger (0..8) wählen – Taste 1–9. */
    private fun hotbarAt(t: Target.Hotbar, x: Float, initial: Boolean) {
        val slot = slotAt(t.button, x)
        if (slot != t.slot) {
            if (initial || t.slot >= 0) held.tapKey(Glfw.KEY_1 + slot)
            t.slot = slot
        }
    }

    fun slotAt(button: Button, x: Float): Int {
        val r = Geometry.toPx(button, safe)
        if (r.w <= 0f) return 0
        return floor((x - r.x) / r.w * 9f).toInt().coerceIn(0, 8)
    }

    companion object {
        /** Kamera-Stick: voller Ausschlag ≈ so viele dp Wischen pro Sekunde. */
        const val CAMERA_STICK_DP = 220f
    }
}
