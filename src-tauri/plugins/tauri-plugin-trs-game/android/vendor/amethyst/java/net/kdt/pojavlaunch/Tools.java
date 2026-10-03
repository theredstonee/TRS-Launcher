// Adapted from Amethyst-Android (LGPL-3.0-or-later), app_pojavlauncher/.../Tools.java
// at commit 330c6eae3164df64bdc4828e946a9e62cc5169e4. TRS patch: only the JNI entry points
// that libpojavexec exports for this class are kept, plus the display size the SDL glue reads.
package net.kdt.pojavlaunch;

import android.util.DisplayMetrics;

import androidx.annotation.Keep;

@Keep
public final class Tools {
    private Tools() {}

    /** Game window size for the SDL glue (org.libsdl.app.SDLSurface); set by the TRS engine. */
    public static final DisplayMetrics currentDisplayMetrics = new DisplayMetrics();

    /** Address of the Dalvik JavaVM (passed to the game JVM as DALVIK_JAVAVM). */
    public static native long getJavaVMPointer();

    /** Global JNI reference of an object as a number string (DALVIK_APPLICATION). */
    public static native String jObjectToString(Object object);

    @Keep
    public static final class SDL {
        private SDL() {}

        public static native void initializeControllerSubsystems();
    }

    static {
        System.loadLibrary("pojavexec");
    }
}
