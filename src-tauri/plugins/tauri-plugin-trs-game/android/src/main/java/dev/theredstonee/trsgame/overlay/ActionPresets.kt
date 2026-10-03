package dev.theredstonee.trsgame.overlay

/**
 * Auswahl „Aktion“ im Editor im Spiel: fertige Belegungen mit Symbol und
 * kurzer Beschriftung. Name = Schlüssel in [OverlayStrings] (`preset.<key>`).
 */
data class ActionPreset(val key: String, val action: Action, val icon: String?, val label: String?)

object ActionPresets {
    val ALL: List<ActionPreset> = listOf(
        ActionPreset("moveStick", Action.Joystick(false), null, "WASD"),
        ActionPreset("cameraStick", Action.Joystick(true), null, "Cam"),
        ActionPreset("jump", Action.Key(Glfw.KEY_SPACE), "jump", null),
        ActionPreset("sneak", Action.Key(Glfw.KEY_LEFT_SHIFT), "sneak", null),
        ActionPreset("sneakToggle", Action.Toggle(Glfw.KEY_LEFT_SHIFT), "sneak", null),
        ActionPreset("sprint", Action.Toggle(Glfw.KEY_LEFT_CONTROL), "sprint", null),
        ActionPreset("attack", Action.Mouse(Glfw.MOUSE_LEFT), "attack", null),
        ActionPreset("use", Action.Mouse(Glfw.MOUSE_RIGHT), "use", null),
        ActionPreset("place", Action.Mouse(Glfw.MOUSE_RIGHT), "place", null),
        ActionPreset("breakBlock", Action.Mouse(Glfw.MOUSE_LEFT), "break", null),
        ActionPreset("pick", Action.Mouse(Glfw.MOUSE_MIDDLE), "pick", null),
        ActionPreset("sneakPlace", Action.Mouse(Glfw.MOUSE_RIGHT, listOf(Glfw.KEY_LEFT_SHIFT)), "place", "Shift"),
        ActionPreset("inventory", Action.Key(69), "inventory", "E"),
        ActionPreset("drop", Action.Key(81), "drop", "Q"),
        ActionPreset("offhand", Action.Key(70), "swap", "F"),
        ActionPreset("perspective", Action.Key(294), "perspective", "F5"),
        ActionPreset("zoom", Action.Key(86), "zoom", null),
        ActionPreset("debug", Action.Key(292), "debug", "F3"),
        ActionPreset("chunks", Action.Key(71, listOf(292)), null, "F3+G"),
        ActionPreset("hitboxes", Action.Key(66, listOf(292)), null, "F3+B"),
        ActionPreset("signal", Action.Key(295), "redstone", "F6"),
        ActionPreset("signalLegacy", Action.Key(297), "redstone", "F8"),
        ActionPreset("chat", Action.Fn(Special.CHAT), "chat", null),
        ActionPreset("keyboard", Action.Fn(Special.KEYBOARD), "keyboard", null),
        ActionPreset("menu", Action.Fn(Special.MENU), "menu", null),
        ActionPreset("trsMenu", Action.Fn(Special.TRS_MENU), "trs", null),
        ActionPreset("emotes", Action.Fn(Special.EMOTE_WHEEL), "emote", null),
        ActionPreset("hotbar", Action.Fn(Special.HOTBAR_SWIPE), "hotbar", null),
        ActionPreset("prev", Action.Fn(Special.SCROLL_UP), "prev", null),
        ActionPreset("next", Action.Fn(Special.SCROLL_DOWN), "next", null),
    )

    /** Tasten für „Andere Taste …“ (GLFW-Code). */
    val KEYS: List<Int> = buildList {
        addAll(listOf(32, 340, 341, 342, 344, 256, 257, 258, 259, 265, 264, 263, 262))
        addAll(65..90)
        addAll(48..57)
        addAll(290..301)
    }

    /** Anzeigename einer Taste (sprachneutral, wie auf der Tastatur). */
    fun keyName(code: Int): String = when (code) {
        32 -> "Space"
        256 -> "Esc"
        257 -> "Enter"
        258 -> "Tab"
        259 -> "⌫"
        262 -> "→"
        263 -> "←"
        264 -> "↓"
        265 -> "↑"
        340 -> "L-Shift"
        341 -> "L-Ctrl"
        342 -> "L-Alt"
        344 -> "R-Shift"
        345 -> "R-Ctrl"
        346 -> "R-Alt"
        in 65..90, in 48..57 -> code.toChar().toString()
        in 290..314 -> "F${code - 289}"
        else -> "#$code"
    }
}
