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
   - `org/lwjgl/glfw/CallbackBridge.java`: SDL, custom-controls and launcher-activity code removed;
     JVM callbacks go to one `Listener`; native method names/signatures unchanged.

## Prebuilt binaries (not in the repo, fetched with SHA-256 pinning – `../../prebuilt.lock`)

| File | From | License / source |
| --- | --- | --- |
| `MobileGlues-release.aar` → `libmobileglues.so` | Amethyst `app_pojavlauncher/libs/` @ 330c6eae | LGPL-2.1, https://github.com/MobileGL-Dev/MobileGlues |
| `krypton_wrapper-release.aar` → `libng_gl4es.so` (NG-GL4ES / Krypton Wrapper) | Amethyst `app_pojavlauncher/libs/` @ 474cbae769828d77551d4ecf73b79025b5157259 (last commit that shipped it) | MIT, https://github.com/BZLZHH/NG-GL4ES (based on ptitSeb/gl4es, MIT) |
| `openal-soft-release.aar` → `libopenal.so` | Amethyst `app_pojavlauncher/libs/` @ 330c6eae | LGPL-2.0-or-later, https://github.com/kcat/openal-soft |
| `lwjgl-3.3.3-natives-release.aar`, `lwjgl-3.4.1-natives-release.aar` (`liblwjgl*.so`, `libfreetype.so`; `libshaderc.so` skipped) | Amethyst `app_pojavlauncher/libs/` @ 330c6eae | BSD-3-Clause (LWJGL), FreeType License; Android fork https://github.com/PojavLauncherTeam/lwjgl3 |
| `components/lwjgl3/3.3.3/*.jar`, `components/lwjgl3/3.4.1/*.jar` | Amethyst `app_pojavlauncher/src/main/assets/components/lwjgl3/` @ 330c6eae | BSD-3-Clause (LWJGL); `*-merged-modules.jar` contains the LGPL-3.0 GLFW stub from `jre_lwjgl3glfw/`; `jsr305.jar` BSD-3-Clause |

Not taken over (yet): Caciocavallo (AWT for Forge installer GUIs / AWT mods), MioLibPatcher
(license not verifiable), Zink/Kopper, ANGLE, SDL, imgui-java, spirv-cross, FFmpeg plugin,
custom controls, gamepad remapper.
