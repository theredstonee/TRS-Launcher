package dev.theredstonee.trs.game.engine

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.Surface
import android.view.ViewGroup
import net.kdt.pojavlaunch.MinecraftGLSurface
import net.kdt.pojavlaunch.Tools
import org.libsdl.app.SDL
import org.libsdl.app.SDLActivity
import org.libsdl.app.TrsSdlText
import org.libsdl.app.SDLSurface
import org.lwjgl.glfw.CallbackBridge

/**
 * SDL3 im Spielprozess (Minecraft 26.3+ nutzt SDL statt GLFW). Ablauf wie Amethyst-Android:
 * Die Spielfläche wird vorab als SDL-Fläche angemeldet; ruft das Spiel `SDL_Init`, meldet der
 * LWJGL-Stub das über `CallbackBridge.notifyLauncher` – dann lädt Android `libSDL3.so` (JNI_OnLoad
 * im Dalvik-VM) und verbindet die Java-Seite. Eingaben gehen danach zusätzlich an SDL.
 */
internal object SdlHost {
    private const val TAG = "TrsGameSdl"

    /** Fläche + Layout für SDL (Hauptthread, vor dem JVM-Start). */
    fun prepare(activity: Activity, layout: ViewGroup, surface: Surface, width: Int, height: Int) {
        size(width, height)
        if (SDLActivity.getSDLSurface() == null) {
            SDL.initialize()
            SDL.setContext(activity)
            SDLActivity.externalInitialize(SDLSurface(activity), layout, surface)
        } else {
            SDLSurface.setNativeSurface(surface)
        }
    }

    /** Spielauflösung (Fensterpixel); SDL bekommt sie beim Einschalten bzw. bei Änderungen. */
    fun size(width: Int, height: Int) {
        Tools.currentDisplayMetrics.widthPixels = width
        Tools.currentDisplayMetrics.heightPixels = height
        if (MinecraftGLSurface.sdlEnabled) SDLActivity.getSDLSurface()?.nativeResize(width, height)
    }

    /** `SDL_Init` im Spiel (Thread des Spiels, an Dalvik angehängt). */
    @Synchronized
    fun enable(): Boolean {
        if (MinecraftGLSurface.sdlEnabled) return true
        return try {
            System.loadLibrary("SDL3")
            SDL.setupJNI()
            MinecraftGLSurface.sdlEnabled = true
            SDLActivity.getSDLSurface()?.nativeResize(CallbackBridge.windowWidth, CallbackBridge.windowHeight)
            Log.i(TAG, "SDL eingeschaltet (${CallbackBridge.windowWidth}x${CallbackBridge.windowHeight})")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "SDL nicht ladbar", t)
            false
        }
    }

    val active: Boolean get() = MinecraftGLSurface.sdlEnabled

    // --- Eingaben (nur wenn aktiv) -------------------------------------------------------

    private var buttons = 0

    fun key(glfwKey: Int, down: Boolean) {
        val android = HardwareKeys.toAndroid(glfwKey)
        if (android != 0) {
            if (down) SDLActivity.onNativeKeyDown(android) else SDLActivity.onNativeKeyUp(android)
            return
        }
        // Tasten ohne Android-Code (F13–F25, z. B. TRS-Menü): direkt als SDL-Ereignis.
        val scancode = HardwareKeys.sdlScancode(glfwKey)
        if (scancode != 0) CallbackBridge.nativeSdlKey(scancode, down)
    }

    fun text(codepoint: Int) {
        TrsSdlText.commit(String(Character.toChars(codepoint)))
    }

    fun mouseButton(glfwButton: Int, down: Boolean, x: Float, y: Float, grabbed: Boolean) {
        val bit = when (glfwButton) {
            Glfw.MOUSE_LEFT -> MotionEvent.BUTTON_PRIMARY
            Glfw.MOUSE_RIGHT -> MotionEvent.BUTTON_SECONDARY
            Glfw.MOUSE_MIDDLE -> MotionEvent.BUTTON_TERTIARY
            3 -> MotionEvent.BUTTON_BACK
            4 -> MotionEvent.BUTTON_FORWARD
            else -> return
        }
        buttons = if (down) buttons or bit else buttons and bit.inv()
        val action = if (down) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_UP
        // Gefangen: SDL rechnet relativ – keine Bewegung mitschicken.
        if (grabbed) SDLActivity.onNativeMouse(buttons, action, 0f, 0f, true)
        else SDLActivity.onNativeMouse(buttons, action, x, y, false)
    }

    fun mouseMove(x: Float, y: Float) = SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, x, y, false)

    fun mouseDelta(dx: Float, dy: Float) = SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, dx, dy, true)

    fun scroll(dx: Float, dy: Float) = SDLActivity.onNativeMouse(0, MotionEvent.ACTION_SCROLL, dx, dy, false)
}
