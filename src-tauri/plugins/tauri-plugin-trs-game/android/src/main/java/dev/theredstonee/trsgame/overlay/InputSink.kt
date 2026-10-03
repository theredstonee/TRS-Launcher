package dev.theredstonee.trsgame.overlay

/**
 * Wohin das Overlay Eingaben schickt – gleiche Methoden wie `GameInput` der
 * Engine (GLFW-Codes). Eigene Schnittstelle, damit die ganze Logik ohne
 * Android und ohne Engine testbar bleibt; [GameInputSink] verbindet beides.
 */
interface InputSink {
    fun sendKey(glfwKey: Int, scancode: Int, down: Boolean, mods: Int)
    fun sendChar(codepoint: Int)
    fun sendMouseButton(button: Int, down: Boolean)
    fun moveMouseRelative(dx: Float, dy: Float)
    fun moveMouseAbsolute(x: Float, y: Float)
    fun scroll(dx: Float, dy: Float)
    /** Maus gefangen = im Spiel; sonst Menü mit Zeiger. */
    fun isGrabbed(): Boolean
    fun showKeyboard(show: Boolean)
}

/** GLFW-Codes, die das Overlay selbst braucht. */
object Glfw {
    const val KEY_SPACE = 32
    const val KEY_1 = 49
    const val KEY_R = 82
    const val KEY_T = 84
    const val KEY_W = 87
    const val KEY_A = 65
    const val KEY_S = 83
    const val KEY_D = 68
    const val KEY_ESCAPE = 256
    // Feste Tasten des TRS Clients im Touch-Modus (docs/touch-mode.md): TRS-Menü, Emote-Rad.
    const val KEY_F13 = 302
    const val KEY_F14 = 303
    const val KEY_LEFT_SHIFT = 340
    const val KEY_LEFT_CONTROL = 341
    const val KEY_LEFT_ALT = 342
    const val KEY_RIGHT_SHIFT = 344
    const val KEY_RIGHT_CONTROL = 345
    const val KEY_RIGHT_ALT = 346
    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2
    const val MOD_SHIFT = 0x1
    const val MOD_CONTROL = 0x2
    const val MOD_ALT = 0x4

    fun isValidKey(key: Int): Boolean = key in 32..348
}
