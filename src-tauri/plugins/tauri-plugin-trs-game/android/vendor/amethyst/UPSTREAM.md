# Amethyst-Android (vendored engine parts)

- Upstream: https://github.com/AngelAuraMC/Amethyst-Android (branch `v3_openjdk`)
- Commit: `330c6eae3164df64bdc4828e946a9e62cc5169e4` (2026-09-26)
- License: GNU LGPL-3.0-or-later (`LICENSE` in this folder). Files keep their upstream headers;
  `jni/jre_launcher.c` additionally carries Oracle's GPLv2 + Classpath Exception header.
- Amethyst itself is a fork of PojavLauncher (PojavLauncherTeam, LGPL-3.0).

## What is here

| Folder | Upstream path | Use |
| --- | --- | --- |
| `jni/` | `app_pojavlauncher/src/main/jni/` | JNI engine: `jre_launcher.c` (JLI_Launch), `egl_bridge.c` + `ctxbridges/` (EGL/GL bridge for the renderers), `input_bridge_v3.c` (GLFW input bridge), `stdio_is.c` (stdout/stderr → log, exit trap), `jvm_hooks/`, `native_hooks/` (exit/dlopen/chmod hooks via bytehook), `environ/`, AWT stub sources (`awt_bridge.c`, `awt_xawt/`, not built) |
| `java/` | `app_pojavlauncher/src/main/java/` | Android-side classes whose names the native code binds to (see below) |
| `jre_lwjgl3glfw/` | `jre_lwjgl3glfw/lwjgl-3.3.3/src`, `jre_lwjgl3glfw/lwjgl-3.4.1/src`, `jre_lwjgl3glfw/lwjgl-3.4.1/build.gradle` | Source of the GLFW stub that runs inside the game JVM. It is shipped prebuilt as `lwjgl-<v>-merged-modules.jar` (see `../../prebuilt.lock`); this source is the corresponding source of that jar |

The native libraries `libpojavexec.so` and `libexithook.so` are built from `jni/` by
`ndk-build` (Gradle `externalNativeBuild`) for `arm64-v8a` and `x86_64`.

## Local patches

1. `jni/Android.mk`, `jni/Application.mk` rewritten: `androidnsbypass` is built from
   `../androidnsbypass` instead of the Prefab package; the AWT bridge (`pojavexec_awt`,
   `awt_xawt`, `awt_headless`), `glxshim` and `linkerhook` (Zink/Turnip only) are not built; the
   `rm ../jniLibs/*/libawt_headless.so` side effect is removed; ABIs limited to `arm64-v8a x86_64`;
   `APP_PLATFORM` 24.
2. `java/` – only the classes the native code needs, reduced to their JNI surface:
   - `com/oracle/dalvik/VMLauncher.java`, `net/kdt/pojavlaunch/Logger.java`,
     `net/kdt/pojavlaunch/CriticalNativeTest.java`, `dalvik/annotation/optimization/CriticalNative.java`:
     unchanged.
   - `net/kdt/pojavlaunch/utils/JREUtils.java`, `net/kdt/pojavlaunch/Tools.java`: only the native
     methods (launch logic moved to `dev.theredstonee.trs.game.engine.JvmLauncher`).
   - `net/kdt/pojavlaunch/ExitActivity.java`: no activity; `showExitMessage` forwards to
     `dev.theredstonee.trs.game.engine.ExitBridge` (reports the exit to the launcher process).
   - `org/lwjgl/glfw/CallbackBridge.java`: custom-controls and launcher-activity code removed;
     JVM callbacks go to one `Listener` (also `notifyLauncher`, i.e. `SDL_Init` from the LWJGL stub);
     native method names/signatures unchanged. TRS additions: `onSdlRelativeMouse` (mouse grab
     reported by SDL) and the native `nativeSdlKey` (see `jni/trs_sdl.c`).
   - `net/kdt/pojavlaunch/Tools.java`: additionally `currentDisplayMetrics` (game window size for
     the SDL glue). `net/kdt/pojavlaunch/MinecraftGLSurface.java`: only the `sdlEnabled` switch.
   - `org/libsdl/app/*.java` (SDL3 android-project glue, zlib, as modified by Amethyst for
     `externalInitialize`): unchanged except – `SDLActivity`: unused `MainActivity` import removed,
     `setOrientation` is a no-op (the game activity stays landscape), `supportsRelativeMouse` /
     `setRelativeMouseEnabled` report the grab to the TRS engine instead of capturing the pointer;
     `SDLSurface`: unused `LauncherPreferences` import removed. `TrsSdlText.java` is a TRS file
     (text input through the package-private `SDLInputConnection`).
3. `jni/trs_sdl.c` (TRS, GPL-3.0-or-later, in `libpojavexec`): SDL3 helpers for Minecraft 26.3+ –
   replaces LWJGL's `DynamicLinkLoader.ndlsym` so `SDL_CreateWindow*` / `SDL_DestroyWindow` reuse
   the single Android window (reference counted, replaced when OpenGL/Vulkan flags differ) and
   `SDL_SetWindowSize` always uses the game surface size; pushes keys without an Android key code
   (F13+) as SDL events. `jni/input_bridge_v3.c` calls `installTrsSdlWindowHook` next to
   `installLwjglDlopenHook`; `jni/jvm_hooks/jvm_hooks.h` declares it.
4. `jni/Application.mk`: `APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true` (16 KB-aligned ELF segments).

## Prebuilt binaries (not in the repo, fetched with SHA-256 pinning – `../../prebuilt.lock`)

| File | From | License / source |
| --- | --- | --- |
| `MobileGlues-release.aar` → `libmobileglues.so` | Amethyst `app_pojavlauncher/libs/` @ 330c6eae | LGPL-2.1, https://github.com/MobileGL-Dev/MobileGlues |
| `krypton_wrapper-release.aar` → `libng_gl4es.so` (NG-GL4ES / Krypton Wrapper) | Amethyst `app_pojavlauncher/libs/` @ 474cbae769828d77551d4ecf73b79025b5157259 (last commit that shipped it) | MIT, https://github.com/BZLZHH/NG-GL4ES (based on ptitSeb/gl4es, MIT) |
| `openal-soft-release.aar` → `libopenal.so` | Amethyst `app_pojavlauncher/libs/` @ 330c6eae | LGPL-2.0-or-later, https://github.com/kcat/openal-soft |
| `lwjgl-3.3.3-natives-release.aar`, `lwjgl-3.4.1-natives-release.aar` (`liblwjgl*.so`, `libfreetype.so`; `libshaderc.so` only from 3.4.1 – Minecraft 26.2+ loads it; Apache-2.0, https://github.com/google/shaderc) | Amethyst `app_pojavlauncher/libs/` @ 330c6eae | BSD-3-Clause (LWJGL), FreeType License; Android fork https://github.com/PojavLauncherTeam/lwjgl3 |
| `SDL-release.aar` → `libSDL3.so` (SDL 3.4.x; `libSDL2.so` skipped) | Amethyst `app_pojavlauncher/libs/` @ 330c6eae (same file as FCL/Zalith Launcher 2) | zlib, https://github.com/libsdl-org/SDL |
| `spirv-cross-natives.aar` → `libspirv-cross-c-shared.so` | Amethyst `app_pojavlauncher/libs/` @ 330c6eae (same file as FCL/Zalith Launcher 2) | Apache-2.0, https://github.com/KhronosGroup/SPIRV-Cross |
| `components/lwjgl3/3.3.3/*.jar`, `components/lwjgl3/3.4.1/*.jar` | Amethyst `app_pojavlauncher/src/main/assets/components/lwjgl3/` @ 330c6eae | BSD-3-Clause (LWJGL); `*-merged-modules.jar` contains the LGPL-3.0 GLFW stub from `jre_lwjgl3glfw/`; `jsr305.jar` BSD-3-Clause |

Not taken over (yet): Caciocavallo (AWT for Forge installer GUIs / AWT mods), MioLibPatcher
(license not verifiable), Zink/Kopper, ANGLE, sdl2-compat, imgui-java, FFmpeg plugin,
custom controls, gamepad remapper. The LWJGL 3.4.1 stub jar at this commit does not contain
Amethyst's `SDLMouse` override; the mouse grab is taken from `SDLActivity.setRelativeMouseEnabled`.
