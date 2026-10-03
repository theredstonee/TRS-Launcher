package dev.theredstonee.trsgame.overlay

/**
 * Layout der Touch-Steuerung, Format Version 1 – gleich wie
 * `trs_core::controls` (Rust) und `app/utils/controls.ts`. Positionen sind
 * Anteile (0..1) der sicheren Fläche (ohne Notch/Ränder).
 */
const val LAYOUT_VERSION = 1
const val MAX_BUTTONS = 48
const val MAX_NAME_CHARS = 48
const val MAX_LABEL_CHARS = 12
const val MAX_CHORD = 3
const val MIN_SIZE = 0.02f
const val MIN_OPACITY = 0.05f
const val MIN_SENSITIVITY = 0.1f
const val MAX_SENSITIVITY = 5f
private const val EPS = 1e-6f

enum class Shape { ROUND, RECT }

enum class Show { GAME, MENU, ALWAYS }

enum class Special(val json: String) {
    KEYBOARD("keyboard"),
    MENU("menu"),
    TRS_MENU("trsMenu"),
    EMOTE_WHEEL("emoteWheel"),
    CHAT("chat"),
    HOTBAR_SWIPE("hotbarSwipe"),
    SCROLL_UP("scrollUp"),
    SCROLL_DOWN("scrollDown");

    companion object {
        fun of(json: String): Special? = entries.firstOrNull { it.json == json }
    }
}

sealed class Action {
    /** Taste; [chord] = vorher gedrückt, danach losgelassen (z. B. F3 für F3+G). */
    data class Key(val key: Int, val chord: List<Int> = emptyList()) : Action()
    data class Mouse(val button: Int, val chord: List<Int> = emptyList()) : Action()
    /** Taste einrasten: tippen = gedrückt, nochmal = los. */
    data class Toggle(val key: Int) : Action()
    data class Joystick(val camera: Boolean) : Action()
    data class Fn(val special: Special) : Action()
}

data class Button(
    val id: String,
    val label: String? = null,
    val icon: String? = null,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val opacity: Float = 0.6f,
    val shape: Shape = Shape.ROUND,
    val action: Action,
    val toggle: Boolean = false,
    val passThrough: Boolean = false,
    val show: Show? = null,
) {
    /** Ohne `show`: Menü, Tastatur, Chat, TRS-Menü (und Esc) immer, sonst nur im Spiel. */
    fun effectiveShow(): Show = show ?: defaultShow(action)

    fun visible(grabbed: Boolean): Boolean = when (effectiveShow()) {
        Show.ALWAYS -> true
        Show.GAME -> grabbed
        Show.MENU -> !grabbed
    }

    val isHotbar: Boolean get() = action is Action.Fn && action.special == Special.HOTBAR_SWIPE
    val isJoystick: Boolean get() = action is Action.Joystick

    /** Kurze Beschriftung als Tasten-Hinweis neben einem Symbol (z. B. „E“, „F5“). */
    val badge: String? get() = if (icon != null && label != null && label.codePointCount(0, label.length) <= 3) label else null

    companion object {
        fun defaultShow(action: Action): Show = when {
            action is Action.Fn && action.special in setOf(Special.KEYBOARD, Special.MENU, Special.CHAT, Special.TRS_MENU) -> Show.ALWAYS
            action is Action.Key && action.key == Glfw.KEY_ESCAPE -> Show.ALWAYS
            else -> Show.GAME
        }
    }
}

data class Gestures(
    /** Tippen = angreifen (sonst benutzen/setzen). */
    val tapAttack: Boolean = true,
    /** Halten = benutzen (sonst abbauen). */
    val holdUse: Boolean = true,
    val swipeHotbar: Boolean = true,
    val cameraSensitivity: Float = 1f,
    val haptics: Boolean = true,
)

data class Layout(
    val version: Int = LAYOUT_VERSION,
    val id: String,
    val name: String,
    val profile: String,
    /** Nur bei unveränderten fertigen Layouts. */
    val builtinRev: Int? = null,
    val buttons: List<Button>,
    val gestures: Gestures,
) {
    class InvalidLayout(reason: String) : Exception(reason)

    fun toJson(): String = Json.write(toMap()) + "\n"

    fun toMap(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["version"] = version
        m["id"] = id
        m["name"] = name
        m["profile"] = profile
        builtinRev?.let { m["builtinRev"] = it }
        m["buttons"] = buttons.map { buttonMap(it) }
        m["gestures"] = linkedMapOf(
            "tapAttack" to gestures.tapAttack,
            "holdUse" to gestures.holdUse,
            "swipeHotbar" to gestures.swipeHotbar,
            "cameraSensitivity" to round4(gestures.cameraSensitivity),
            "haptics" to gestures.haptics,
        )
        return m
    }

    companion object {
        val PROFILES = setOf("pvp", "build", "redstone", "custom")
        private val ID_RE = Regex("^[a-z0-9][a-z0-9-]{0,39}$")
        private val BUTTON_ID_RE = Regex("^[A-Za-z0-9_-]{1,32}$")

        fun isValidId(id: String): Boolean = ID_RE.matches(id)

        private fun round4(v: Float): Double = Math.round(v * 10000.0) / 10000.0

        private fun buttonMap(b: Button): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["id"] = b.id
            b.label?.let { m["label"] = it }
            b.icon?.let { m["icon"] = it }
            m["x"] = round4(b.x)
            m["y"] = round4(b.y)
            m["w"] = round4(b.w)
            m["h"] = round4(b.h)
            m["opacity"] = round4(b.opacity)
            m["shape"] = if (b.shape == Shape.ROUND) "round" else "rect"
            m["action"] = when (val a = b.action) {
                is Action.Key -> linkedMapOf<String, Any?>("type" to "key", "key" to a.key).also { if (a.chord.isNotEmpty()) it["chord"] = a.chord }
                is Action.Mouse -> linkedMapOf<String, Any?>("type" to "mouse", "button" to a.button).also { if (a.chord.isNotEmpty()) it["chord"] = a.chord }
                is Action.Toggle -> linkedMapOf("type" to "toggle", "key" to a.key)
                is Action.Joystick -> linkedMapOf("type" to "joystick", "mode" to if (a.camera) "camera" else "wasd")
                is Action.Fn -> linkedMapOf("type" to "special", "special" to a.special.json)
            }
            if (b.toggle) m["toggle"] = true
            if (b.passThrough) m["passThrough"] = true
            b.show?.let { m["show"] = it.name.lowercase() }
            return m
        }

        /** Liest und prüft ein Layout (wirft [InvalidLayout] bzw. [Json.ParseException]). */
        fun parse(text: String): Layout {
            val root = Json.parse(text) as? Map<*, *> ?: throw InvalidLayout("not an object")
            val layout = fromMap(root)
            validate(layout)
            return layout
        }

        private fun num(v: Any?, what: String): Float = (v as? Double)?.toFloat() ?: throw InvalidLayout(what)
        private fun int(v: Any?, what: String): Int {
            val d = v as? Double ?: throw InvalidLayout(what)
            if (d != Math.rint(d) || d > Int.MAX_VALUE || d < Int.MIN_VALUE) throw InvalidLayout(what)
            return d.toInt()
        }
        private fun bool(v: Any?, default: Boolean): Boolean = v as? Boolean ?: default
        private fun keys(v: Any?): List<Int> = when (v) {
            null -> emptyList()
            is List<*> -> v.map { int(it, "chord") }
            else -> throw InvalidLayout("chord")
        }

        private fun action(v: Any?): Action {
            val m = v as? Map<*, *> ?: throw InvalidLayout("action")
            return when (m["type"]) {
                "key" -> Action.Key(int(m["key"], "key"), keys(m["chord"]))
                "mouse" -> Action.Mouse(int(m["button"], "mouse"), keys(m["chord"]))
                "toggle" -> Action.Toggle(int(m["key"], "key"))
                "joystick" -> when (m["mode"]) {
                    "wasd" -> Action.Joystick(camera = false)
                    "camera" -> Action.Joystick(camera = true)
                    else -> throw InvalidLayout("joystick")
                }
                "special" -> Action.Fn(Special.of(m["special"] as? String ?: "") ?: throw InvalidLayout("special"))
                else -> throw InvalidLayout("action")
            }
        }

        private fun button(v: Any?): Button {
            val m = v as? Map<*, *> ?: throw InvalidLayout("button")
            return Button(
                id = m["id"] as? String ?: throw InvalidLayout("button id"),
                label = m["label"] as? String,
                icon = (m["icon"] as? String)?.takeIf { it in Icons.NAMES },
                x = num(m["x"], "position"),
                y = num(m["y"], "position"),
                w = num(m["w"], "position"),
                h = num(m["h"], "position"),
                opacity = if (m["opacity"] == null) 0.6f else num(m["opacity"], "opacity"),
                shape = when (m["shape"]) {
                    null, "round" -> Shape.ROUND
                    "rect" -> Shape.RECT
                    else -> throw InvalidLayout("shape")
                },
                action = action(m["action"]),
                toggle = bool(m["toggle"], false),
                passThrough = bool(m["passThrough"], false),
                show = when (m["show"]) {
                    null -> null
                    "game" -> Show.GAME
                    "menu" -> Show.MENU
                    "always" -> Show.ALWAYS
                    else -> throw InvalidLayout("show")
                },
            )
        }

        fun fromMap(m: Map<*, *>): Layout {
            val g = m["gestures"] as? Map<*, *> ?: throw InvalidLayout("gestures")
            val buttons = m["buttons"] as? List<*> ?: throw InvalidLayout("buttons")
            if (buttons.size > MAX_BUTTONS) throw InvalidLayout("buttons")
            return Layout(
                version = int(m["version"], "version"),
                id = m["id"] as? String ?: throw InvalidLayout("id"),
                name = m["name"] as? String ?: throw InvalidLayout("name"),
                profile = m["profile"] as? String ?: throw InvalidLayout("profile"),
                builtinRev = m["builtinRev"]?.let { int(it, "builtinRev") },
                buttons = buttons.map { button(it) },
                gestures = Gestures(
                    tapAttack = g["tapAttack"] as? Boolean ?: throw InvalidLayout("gestures"),
                    holdUse = g["holdUse"] as? Boolean ?: throw InvalidLayout("gestures"),
                    swipeHotbar = g["swipeHotbar"] as? Boolean ?: throw InvalidLayout("gestures"),
                    cameraSensitivity = num(g["cameraSensitivity"], "sensitivity"),
                    haptics = bool(g["haptics"], true),
                ),
            )
        }

        private fun unit(v: Float) = v.isFinite() && v >= -EPS && v <= 1 + EPS
        private fun size(v: Float) = v.isFinite() && v >= MIN_SIZE - EPS && v <= 1 + EPS

        private fun validChord(key: Int?, chord: List<Int>): Boolean =
            chord.size <= MAX_CHORD && chord.all { Glfw.isValidKey(it) && it != key } && chord.toSet().size == chord.size

        /** Gleiche Regeln wie `controls::validate` in Rust. */
        fun validate(l: Layout) {
            if (l.version != LAYOUT_VERSION) throw InvalidLayout("version")
            if (!isValidId(l.id)) throw InvalidLayout("id")
            val nameLen = l.name.trim().let { it.codePointCount(0, it.length) }
            if (nameLen == 0 || nameLen > MAX_NAME_CHARS || l.name.any { it.isISOControl() }) throw InvalidLayout("name")
            if (l.profile !in PROFILES) throw InvalidLayout("profile")
            if (l.buttons.isEmpty() || l.buttons.size > MAX_BUTTONS) throw InvalidLayout("buttons")
            val ids = HashSet<String>()
            for (b in l.buttons) {
                if (!BUTTON_ID_RE.matches(b.id) || !ids.add(b.id)) throw InvalidLayout("button id")
                val label = b.label
                if (label != null && (label.isBlank() || label.codePointCount(0, label.length) > MAX_LABEL_CHARS || label.any { it.isISOControl() })) {
                    throw InvalidLayout("label")
                }
                if (label == null && b.icon == null) throw InvalidLayout("label")
                if (!unit(b.x) || !unit(b.y) || !size(b.w) || !size(b.h) || b.x + b.w > 1 + EPS || b.y + b.h > 1 + EPS) {
                    throw InvalidLayout("position")
                }
                if (!b.opacity.isFinite() || b.opacity < MIN_OPACITY - EPS || b.opacity > 1 + EPS) throw InvalidLayout("opacity")
                when (val a = b.action) {
                    is Action.Key -> if (!Glfw.isValidKey(a.key) || !validChord(a.key, a.chord)) throw InvalidLayout("key")
                    is Action.Mouse -> if (a.button !in 0..7 || !validChord(null, a.chord)) throw InvalidLayout("mouse")
                    is Action.Toggle -> if (!Glfw.isValidKey(a.key)) throw InvalidLayout("key")
                    else -> {}
                }
            }
            val s = l.gestures.cameraSensitivity
            if (!s.isFinite() || s < MIN_SENSITIVITY || s > MAX_SENSITIVITY) throw InvalidLayout("sensitivity")
        }

        /** Notfall-Layout, wenn keine Datei lesbar ist (Laufen, Springen, Angreifen, Menü, Tastatur). */
        fun fallback(): Layout = Layout(
            id = "pvp",
            name = "PvP",
            profile = "pvp",
            buttons = listOf(
                Button(id = "move", label = "WASD", x = 0.03f, y = 0.5f, w = 0.19f, h = 0.4f, opacity = 0.55f, action = Action.Joystick(false)),
                Button(id = "attack", icon = "attack", x = 0.8f, y = 0.38f, w = 0.1f, h = 0.2f, opacity = 0.7f, action = Action.Mouse(Glfw.MOUSE_LEFT), passThrough = true),
                Button(id = "use", icon = "use", x = 0.69f, y = 0.56f, w = 0.085f, h = 0.17f, opacity = 0.65f, action = Action.Mouse(Glfw.MOUSE_RIGHT), passThrough = true),
                Button(id = "jump", icon = "jump", x = 0.885f, y = 0.62f, w = 0.09f, h = 0.18f, opacity = 0.65f, action = Action.Key(Glfw.KEY_SPACE)),
                Button(id = "sneak", icon = "sneak", x = 0.8f, y = 0.68f, w = 0.07f, h = 0.14f, opacity = 0.6f, action = Action.Key(Glfw.KEY_LEFT_SHIFT)),
                Button(id = "hotbar", icon = "hotbar", x = 0.3f, y = 0.88f, w = 0.4f, h = 0.12f, opacity = 0.15f, shape = Shape.RECT, action = Action.Fn(Special.HOTBAR_SWIPE)),
                Button(id = "inventory", icon = "inventory", label = "E", x = 0.71f, y = 0.88f, w = 0.05f, h = 0.1f, shape = Shape.RECT, action = Action.Key(69), show = Show.ALWAYS),
                Button(id = "keyboard", icon = "keyboard", x = 0.075f, y = 0.02f, w = 0.05f, h = 0.1f, shape = Shape.RECT, action = Action.Fn(Special.KEYBOARD)),
                Button(id = "menu", icon = "menu", x = 0.475f, y = 0.01f, w = 0.05f, h = 0.1f, shape = Shape.RECT, action = Action.Fn(Special.MENU)),
            ),
            gestures = Gestures(),
        )
    }
}
