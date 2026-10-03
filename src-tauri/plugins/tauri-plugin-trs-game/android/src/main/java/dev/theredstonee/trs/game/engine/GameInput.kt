package dev.theredstonee.trs.game.engine

/**
 * Eingabe ins Spiel (Vertrag, identisch zu Swift `protocol GameInput`).
 * Tasten sind GLFW-Codes ([Glfw]). Koordinaten/Bewegungen in View-Pixeln (Bildschirm) –
 * die Engine rechnet auf die Spielauflösung um.
 */
interface GameInput {
    fun sendKey(glfwKey: Int, scancode: Int, down: Boolean, mods: Int)
    fun sendChar(codepoint: Int)
    fun sendMouseButton(button: Int, down: Boolean)
    fun moveMouseRelative(dx: Float, dy: Float)
    fun moveMouseAbsolute(x: Float, y: Float)
    fun scroll(dx: Float, dy: Float)

    /** Maus gefangen = im Spiel (Kamera), sonst Menü (Zeiger). */
    fun isGrabbed(): Boolean
    fun showKeyboard(show: Boolean)
}

/** GLFW-Konstanten (Auszug, Werte wie glfw3.h). */
object Glfw {
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2

    const val MOD_SHIFT = 0x1
    const val MOD_CONTROL = 0x2
    const val MOD_ALT = 0x4

    const val KEY_SPACE = 32
    const val KEY_APOSTROPHE = 39
    const val KEY_COMMA = 44
    const val KEY_MINUS = 45
    const val KEY_PERIOD = 46
    const val KEY_SLASH = 47
    const val KEY_0 = 48
    const val KEY_1 = 49
    const val KEY_9 = 57
    const val KEY_SEMICOLON = 59
    const val KEY_EQUAL = 61
    const val KEY_A = 65
    const val KEY_D = 68
    const val KEY_E = 69
    const val KEY_F = 70
    const val KEY_Q = 81
    const val KEY_S = 83
    const val KEY_T = 84
    const val KEY_W = 87
    const val KEY_Z = 90
    const val KEY_LEFT_BRACKET = 91
    const val KEY_BACKSLASH = 92
    const val KEY_RIGHT_BRACKET = 93
    const val KEY_GRAVE_ACCENT = 96
    const val KEY_ESCAPE = 256
    const val KEY_ENTER = 257
    const val KEY_TAB = 258
    const val KEY_BACKSPACE = 259
    const val KEY_INSERT = 260
    const val KEY_DELETE = 261
    const val KEY_RIGHT = 262
    const val KEY_LEFT = 263
    const val KEY_DOWN = 264
    const val KEY_UP = 265
    const val KEY_PAGE_UP = 266
    const val KEY_PAGE_DOWN = 267
    const val KEY_HOME = 268
    const val KEY_END = 269
    const val KEY_CAPS_LOCK = 280
    const val KEY_F1 = 290
    const val KEY_F3 = 292
    const val KEY_F5 = 294
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341
    const val KEY_LEFT_ALT = 342
    const val KEY_RIGHT_SHIFT = 344
    const val KEY_RIGHT_CONTROL = 345
    const val KEY_RIGHT_ALT = 346
}
