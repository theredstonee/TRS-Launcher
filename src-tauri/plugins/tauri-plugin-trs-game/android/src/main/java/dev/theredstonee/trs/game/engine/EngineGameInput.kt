package dev.theredstonee.trs.game.engine

import org.lwjgl.glfw.CallbackBridge

/**
 * [GameInput] über die native Eingabe-Brücke (input_bridge_v3.c). Nutzt das Spiel SDL3
 * (Minecraft 26.3+), gehen die Eingaben zusätzlich an SDL ([SdlHost]).
 */
internal class EngineGameInput(private val keyboard: (Boolean) -> Unit) : GameInput {
    /** Spielauflösung / View-Größe. */
    @Volatile var scaleX = 1f
    @Volatile var scaleY = 1f
    @Volatile private var mods = 0

    override fun sendKey(glfwKey: Int, scancode: Int, down: Boolean, mods: Int) {
        val bit = when (glfwKey) {
            Glfw.KEY_LEFT_SHIFT, Glfw.KEY_RIGHT_SHIFT -> Glfw.MOD_SHIFT
            Glfw.KEY_LEFT_CONTROL, Glfw.KEY_RIGHT_CONTROL -> Glfw.MOD_CONTROL
            Glfw.KEY_LEFT_ALT, Glfw.KEY_RIGHT_ALT -> Glfw.MOD_ALT
            else -> 0
        }
        if (bit != 0) this.mods = if (down) this.mods or bit else this.mods and bit.inv()
        CallbackBridge.sendKey(glfwKey, scancode, mods or this.mods, down)
        if (SdlHost.active) SdlHost.key(glfwKey, down)
    }

    override fun sendChar(codepoint: Int) {
        // Zeichen außerhalb der BMP als Surrogatpaar (GLFW-Stub nimmt char).
        for (c in Character.toChars(codepoint)) CallbackBridge.sendChar(c, mods)
        if (SdlHost.active) SdlHost.text(codepoint)
    }

    override fun sendMouseButton(button: Int, down: Boolean) {
        CallbackBridge.sendMouseButton(button, mods, down)
        if (SdlHost.active) SdlHost.mouseButton(button, down, CallbackBridge.mouseX, CallbackBridge.mouseY, isGrabbed())
    }

    override fun moveMouseRelative(dx: Float, dy: Float) {
        CallbackBridge.sendCursorPos(CallbackBridge.mouseX + dx * scaleX, CallbackBridge.mouseY + dy * scaleY)
        if (SdlHost.active) {
            if (isGrabbed()) SdlHost.mouseDelta(dx * scaleX, dy * scaleY) else SdlHost.mouseMove(CallbackBridge.mouseX, CallbackBridge.mouseY)
        }
    }

    override fun moveMouseAbsolute(x: Float, y: Float) {
        CallbackBridge.sendCursorPos(x * scaleX, y * scaleY)
        if (SdlHost.active && !isGrabbed()) SdlHost.mouseMove(x * scaleX, y * scaleY)
    }

    override fun scroll(dx: Float, dy: Float) {
        CallbackBridge.sendScroll(dx.toDouble(), dy.toDouble())
        if (SdlHost.active) SdlHost.scroll(dx, dy)
    }

    override fun isGrabbed(): Boolean = CallbackBridge.isGrabbing()

    override fun showKeyboard(show: Boolean) = keyboard(show)
}
