# Amethyst-iOS (vendored engine parts)

- Repository: https://github.com/AngelAuraMC/Amethyst-iOS
- Commit: `9212a1894865e7ac0466029e25ddb0d895544c76` (2026-08-10, "fix(JVM): append `-XX:-UseCompressedClassPointers` for all cases")
- License: GPL-3.0 (`LICENSE` in this folder). `Natives/jni.h` is from the Android Open Source Project (Apache-2.0, header kept).
- Copyright: Tran Hoang Khanh Duy and the PojavLauncher/Amethyst contributors.

## What is vendored here (compiled into `libtrsengine.dylib`)

| File | Purpose |
|---|---|
| `Natives/utils.m`, `utils.h` | JIT detection (`isJITEnabled`, `DeviceGetJITFlags`, TXM/iOS 26 helpers), entitlement lookup |
| `Natives/dyld_bypass_validation.m` | dyld library-validation bypass (loads the runtime-downloaded, unsigned JRE dylibs; needs JIT) |
| `Natives/dyld_patch_platform.m` | patches macOS-platform Mach-O files to iOS before loading |
| `Natives/main_hook.m` | hooks for `exit`/`abort`/`dlopen`/`open` (crash handling, resolv.conf, dyld bypass) |
| `Natives/input_bridge_v3.m` | GLFW input bridge + JNI natives of the LWJGL/GLFW shim |
| `Natives/egl_bridge.m`, `Natives/ctxbridges/*` | GL/EGL bridge (ANGLE/gl4es/MobileGlues via EGL, Zink via OSMesa) |
| `Natives/awt_xawt/*` | AWT stub library for Caciocavallo (`libawt_xawt.dylib`) |
| `Natives/environ.h`, `glfw_keycodes.h`, `JavaLauncher.h`, `ios_uikit_bridge.h`, `jni.h` | headers used by the files above |

`trs/trs_engine.m` ports `launchJVM()` from `Natives/JavaLauncher.m` and parts of `Natives/main.m`
(stdio redirect, default environment, resolv.conf); `trs/trs_shims.m` replaces Amethyst's launcher UI
classes that the engine code expects (`SurfaceViewController`, `PLLogOutputView`, …) and keeps the
clipboard/`showError` code from `Natives/ios_uikit_bridge.m`. Both keep the attribution in their header.

## Taken from the pinned checkout at build time (not copied into this repository)

`scripts/ios/build-engine.sh` fetches the commit above and uses, unchanged:

- headers `Natives/external/mesa` (Khronos/Mesa, MIT), `Natives/external/mach/mach_excServer.c/.h` (MIG output),
  submodule `Natives/external/fishhook` (Facebook, BSD-3-Clause), `Natives/external/gl4es` (tinygl4angle),
- submodule `Natives/external/MobileGlues` (LGPL-2.1) – built from source,
- `JavaApp/` (LWJGL/GLFW shim, launcher classes, Caciocavallo jars, gson, jsr305) – built with JDK 8,
- prebuilt libraries from `Natives/resources/Frameworks`: ANGLE (`libEGL`/`libGLESv2`, BSD-3-Clause),
  gl4es (`libgl4es_114`, MIT), Mesa OSMesa/glapi (MIT), MoltenVK (Apache-2.0), LWJGL natives (BSD-3-Clause),
  OpenAL Soft (LGPL-2.0), FreeType (FTL), shaderc (Apache-2.0),
- `Natives/resources/UniversalJIT26*.js` (StikDebug JIT scripts for iOS 26 / TXM devices).

Java runtimes (OpenJDK 8/17/21/25 for ios-aarch64, GPL-2.0 with Classpath Exception) are **not** bundled;
the app downloads them at runtime from `assets.angelauramc.dev` and checks the SHA-256 pinned in
`IOS_RUNTIMES` in `src/runtime.rs`.

## Local patches

All marked with `// TRS` in the source. `build-engine.sh` fails if any other vendored file differs from upstream.

1. `Natives/input_bridge_v3.m` – `nativeSetGrabbing` reports to `trs_engine_grab_changed()` instead of
   casting the window's root view controller to `SurfaceViewController` (in TRS the root is the launcher).
2. `Natives/main_hook.m` – `hooked_exit` calls `trs_engine_will_exit()` first (session end is stored for the
   next app start, because the process ends with the game).
3. `Natives/egl_bridge.m` – `pojavSwapBuffers` calls `trs_engine_first_frame()` (reports "running").
4. `patches/0001-javaapp-trs-engine-lib.patch` (applied to the checkout's `JavaApp`): `GLFW.java` and
   `UIKit.java` load the engine from `TRS_ENGINE_LIB` instead of `<BUNDLE_PATH>/AngelAuraAmethyst`.

## Updating

1. Change the commit in this file and in `scripts/ios/build-engine.sh` (`AMETHYST_COMMIT`), and in `NOTICE`.
2. Copy the files listed above from the new commit, re-apply patches 1–3 (`git diff` against upstream helps).
3. Check `JavaLauncher.m`/`main.m` for changes and port them to `trs/trs_engine.m`; check
   `patches/0001-*.patch` still applies (`git apply --check`).
4. If Amethyst publishes new JREs, update URLs/SHA-256/sizes in `IOS_RUNTIMES` (`src/runtime.rs`).
5. Run the "iOS engine" workflow (`.github/workflows/ios-engine.yml`).
