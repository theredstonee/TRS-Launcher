// Adapted from Amethyst-Android (LGPL-3.0-or-later), app_pojavlauncher/.../MinecraftGLSurface.java
// at commit 330c6eae3164df64bdc4828e946a9e62cc5169e4. TRS patch: only the SDL switch that the SDL
// Java glue (org.libsdl.app) reads is kept; the TRS engine (GameActivity/SdlHost) sets it.
package net.kdt.pojavlaunch;

import androidx.annotation.Keep;

@Keep
public final class MinecraftGLSurface {
    private MinecraftGLSurface() {}

    /** True once the game JVM initialized SDL (Minecraft 26.3+ or SDL controller mods). */
    public static volatile boolean sdlEnabled = false;
}
