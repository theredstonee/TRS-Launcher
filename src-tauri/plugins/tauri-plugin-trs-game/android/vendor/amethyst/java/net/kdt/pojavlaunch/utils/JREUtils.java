// Adapted from Amethyst-Android (LGPL-3.0-or-later), app_pojavlauncher/.../utils/JREUtils.java
// at commit 330c6eae3164df64bdc4828e946a9e62cc5169e4. TRS patch: only the native entry points
// remain here (the launch logic lives in dev.theredstonee.trs.game.engine.JvmLauncher);
// the AWT bridge natives are not built.
package net.kdt.pojavlaunch.utils;

import android.content.Context;

import androidx.annotation.Keep;

@Keep
public final class JREUtils {
    private JREUtils() {}

    public static native int chdir(String path);
    public static native boolean dlopen(String libPath);
    public static native void setLdLibraryPath(String ldLibraryPath);
    public static native void setupBridgeWindow(Object surface);
    public static native void releaseBridgeWindow();
    public static native void initializeHooks();
    public static native void setupExitMethod(Context context);

    static {
        System.loadLibrary("exithook");
        System.loadLibrary("pojavexec");
    }
}
