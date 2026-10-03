package dev.theredstonee.trs.game.engine

import android.view.KeyEvent

/** Android-Tastencodes → GLFW (Hardware-Tastatur). 0 = unbekannt. */
internal object HardwareKeys {
    fun toGlfw(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> Glfw.KEY_A + (keyCode - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> Glfw.KEY_0 + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> Glfw.KEY_F1 + (keyCode - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_SPACE -> Glfw.KEY_SPACE
        KeyEvent.KEYCODE_APOSTROPHE -> Glfw.KEY_APOSTROPHE
        KeyEvent.KEYCODE_COMMA -> Glfw.KEY_COMMA
        KeyEvent.KEYCODE_MINUS -> Glfw.KEY_MINUS
        KeyEvent.KEYCODE_PERIOD -> Glfw.KEY_PERIOD
        KeyEvent.KEYCODE_SLASH -> Glfw.KEY_SLASH
        KeyEvent.KEYCODE_SEMICOLON -> Glfw.KEY_SEMICOLON
        KeyEvent.KEYCODE_EQUALS -> Glfw.KEY_EQUAL
        KeyEvent.KEYCODE_LEFT_BRACKET -> Glfw.KEY_LEFT_BRACKET
        KeyEvent.KEYCODE_BACKSLASH -> Glfw.KEY_BACKSLASH
        KeyEvent.KEYCODE_RIGHT_BRACKET -> Glfw.KEY_RIGHT_BRACKET
        KeyEvent.KEYCODE_GRAVE -> Glfw.KEY_GRAVE_ACCENT
        KeyEvent.KEYCODE_ESCAPE -> Glfw.KEY_ESCAPE
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Glfw.KEY_ENTER
        KeyEvent.KEYCODE_TAB -> Glfw.KEY_TAB
        KeyEvent.KEYCODE_DEL -> Glfw.KEY_BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> Glfw.KEY_DELETE
        KeyEvent.KEYCODE_INSERT -> Glfw.KEY_INSERT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Glfw.KEY_RIGHT
        KeyEvent.KEYCODE_DPAD_LEFT -> Glfw.KEY_LEFT
        KeyEvent.KEYCODE_DPAD_DOWN -> Glfw.KEY_DOWN
        KeyEvent.KEYCODE_DPAD_UP -> Glfw.KEY_UP
        KeyEvent.KEYCODE_PAGE_UP -> Glfw.KEY_PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> Glfw.KEY_PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> Glfw.KEY_HOME
        KeyEvent.KEYCODE_MOVE_END -> Glfw.KEY_END
        KeyEvent.KEYCODE_CAPS_LOCK -> Glfw.KEY_CAPS_LOCK
        KeyEvent.KEYCODE_SHIFT_LEFT -> Glfw.KEY_LEFT_SHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> Glfw.KEY_RIGHT_SHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> Glfw.KEY_LEFT_CONTROL
        KeyEvent.KEYCODE_CTRL_RIGHT -> Glfw.KEY_RIGHT_CONTROL
        KeyEvent.KEYCODE_ALT_LEFT -> Glfw.KEY_LEFT_ALT
        KeyEvent.KEYCODE_ALT_RIGHT -> Glfw.KEY_RIGHT_ALT
        else -> 0
    }

    fun mods(event: KeyEvent): Int {
        var mods = 0
        if (event.isShiftPressed) mods = mods or Glfw.MOD_SHIFT
        if (event.isCtrlPressed) mods = mods or Glfw.MOD_CONTROL
        if (event.isAltPressed) mods = mods or Glfw.MOD_ALT
        return mods
    }
}
