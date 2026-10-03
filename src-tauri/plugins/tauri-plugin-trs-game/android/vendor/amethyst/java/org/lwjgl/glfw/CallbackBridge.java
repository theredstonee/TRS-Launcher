// Adapted from Amethyst-Android (LGPL-3.0-or-later), app_pojavlauncher/.../org/lwjgl/glfw/CallbackBridge.java
// at commit 330c6eae3164df64bdc4828e946a9e62cc5169e4. TRS patch: SDL integration, custom-controls
// and launcher-activity references removed; the JVM-side callbacks (grab state, clipboard,
// launcher notifications) are forwarded to a single Listener set by the TRS engine. The native
// method names/signatures are unchanged (input_bridge_v3.c registers them).
package org.lwjgl.glfw;

import android.util.DisplayMetrics;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import dalvik.annotation.optimization.CriticalNative;

@Keep
public class CallbackBridge {
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    /** Engine side of the callbacks that come from the game JVM. */
    public interface Listener {
        void onGrabStateChanged(boolean grabbing);
        @Nullable String onClipboard(int type, @Nullable String text);
        float androidDpi();
    }

    private static volatile boolean isGrabbing = false;
    private static volatile @Nullable Listener listener;

    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static volatile float mouseX, mouseY;

    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;

    public static void setListener(@Nullable Listener l) {
        listener = l;
    }

    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        nativeSendCursorPos(x, y);
    }

    public static void sendKey(int glfwKey, int scancode, int mods, boolean down) {
        nativeSendKey(glfwKey, scancode, down ? 1 : 0, mods);
    }

    public static void sendChar(char codepoint, int mods) {
        nativeSendCharMods(codepoint, mods);
        nativeSendChar(codepoint);
    }

    public static void sendMouseButton(int button, int mods, boolean down) {
        nativeSendMouseButton(button, down ? 1 : 0, mods);
    }

    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        return isGrabbing;
    }

    // Called from JRE side
    @Keep
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        Listener l = listener;
        return l == null ? null : l.onClipboard(type, copy);
    }

    // Called from JRE side via jni (SDL integration is not used by TRS).
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        return false;
    }

    // Called from JRE side
    @Keep
    private static void onDirectInputEnable() {
    }

    // Called from JRE side
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        isGrabbing = grabbing;
        Listener l = listener;
        if (l != null) l.onGrabStateChanged(grabbing);
    }

    @Keep // Used to implement glfwGetWindowContentScale for imgui-java
    private static float getAndroidDPI() {
        Listener l = listener;
        if (l != null) return l.androidDpi();
        DisplayMetrics metrics = new DisplayMetrics();
        metrics.setToDefaults();
        return metrics.density;
    }

    public static FloatBuffer createGamepadAxisBuffer() {
        ByteBuffer axisByteBuffer = nativeCreateGamepadAxisBuffer();
        // NOTE: hardcoded order (also in jre_lwjgl3glfw CallbackBridge)
        return axisByteBuffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);
    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    public static native void nativeSetWindowAttrib(int attrib, int value);
    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();

    static {
        System.loadLibrary("pojavexec");
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        sGamepadAxisBuffer = createGamepadAxisBuffer();
    }
}
