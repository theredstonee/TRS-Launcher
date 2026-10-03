# TRS game plugin – iOS (Sideload, JIT)

Minecraft Java runs **inside the app process**, using the engine parts of
[Amethyst-iOS](https://github.com/AngelAuraMC/Amethyst-iOS) (GPL-3.0). Only for sideloading
(AltStore/SideStore/TrollStore) – the App Store forbids JIT and downloaded code.

```
Rust (src/)                                Swift (ios/Sources/TrsGame/)          native (ios/Engine/, CMake)
runtime.rs  IOS_RUNTIMES, ZIP+tar.xz, SHA-256
mobile.rs   launch → ios::tauri_bridge ─┐
ios/engine.rs probe → heap → JIT → args ─┴▶ TrsGamePlugin.probe/launch ─────▶ libtrsengine.dylib (dlopen)
ios/args.rs   JVM argv/env                 GameViewController (landscape)        trs_engine.m  JLI_Launch thread
ios/session.rs Swift events → trs-game://  JitHelpView (DE/EN, polls 1 s)        vendor/amethyst-ios  GL/EGL, input,
               ◀── Channel (EngineEventOut)                                        JIT, dyld bypass, exit hooks
```

## Flow

1. `prepare_runtime(n)` (shared with Android): Java `8/17/21/25` for ios-aarch64 (built by Amethyst)
   is downloaded into `<app data>/engine/runtimes/jre-<n>` and checked against the SHA-256 in
   `IOS_RUNTIMES` (`src/runtime.rs`). The files are ZIPs with one `.tar.xz` inside. Never part of the IPA.
2. `launch(spec)` → `ios::engine::launch` → Swift `probe`: engine present? JIT? memory, entitlements,
   screen, paths.
3. `-Xmx` from `os_proc_available_memory()`, physical RAM and the largest contiguous mapping
   (`src/ios/memory.rs`) – sideload re-signing often strips `increased-memory-limit` /
   `extended-virtual-addressing`, so nothing is assumed. The profile value is an upper limit.
4. JVM arguments like Amethyst's `launchJVM` + the contract properties
   `-Dtrs.mobile=ios -Dtrs.touch=true -Dtrs.overlay.version=1 -Dtrs.safeInsets=l,t,r,b`.
   Main class is `dev.theredstonee.trs.ios.TrsBoot` (puts the game classpath into Pojav's class loader,
   then calls the real main class). LWJGL comes from the engine (Amethyst's GLFW shim incl. lwjglx),
   `org.lwjgl`/text2speech/twitch jars of the version are skipped. `renderer: auto` stays Amethyst's auto.
5. Swift presents `GameViewController` (full screen, landscape, home indicator hidden). Without JIT it shows
   the help screen (SideStore + StikDebug, AltServer, TrollStore, jailbreak; iOS 26/TXM: StikDebug with
   `UniversalJIT26.js`) and refuses to start; `trs_engine_launch` checks JIT again natively.
6. Events: `jitWaiting/jitReady/starting/running/log/exited/cancelled/failed` → `trs-game://state|log`.

**The JVM cannot be unloaded.** When the game ends, the app process ends too (like Amethyst). The session
end is written to `Documents/trs-last-session.json`; read it on the next start with
`tauri_plugin_trs_game::ios::session::take_last_session(<Documents>)`. A second launch in the same
process returns `game.restartRequired`. `runJava` (headless JVM for Forge processors) and `stop` are not
possible on iOS and are rejected.

Logs: `Documents/trs-engine.log` (visible in the Files app because the IPA sets `UIFileSharingEnabled`).

## Building (macOS only)

```sh
# 1. Engine (Amethyst at the pinned commit, CMake, MobileGlues, Java part) → engine folder
JAVA8_HOME=$(/usr/libexec/java_home -v 1.8) scripts/ios/build-engine.sh build/engine-ios
# 2. Unsigned TRS app from Tauri (mobile-core job): TRS.app or an unsigned .ipa
# 3. Put the engine into the app and fake-sign with the entitlements (ldid)
scripts/ios/package-ipa.sh "<TRS.app or unsigned.ipa>" build/engine-ios build/TRS-Launcher.ipa
# 4. AltStore/SideStore entry (entitlements + privacy keys are read from the IPA)
node scripts/ios/altstore-entry.mjs app --version 0.18.0 --url https://…/TRS-Launcher.ipa \
  --ipa build/TRS-Launcher.ipa --info-plist info.json --entitlements entitlements.json
```

CI: `.github/workflows/ios-engine.yml` (reusable). It builds the engine, compiles this plugin for
`aarch64-apple-ios` (Rust + this Swift package via swift-rs) and, with `app-artifact`, packages the IPA:

```yaml
ios-engine:
  needs: launcher-ios
  uses: ./.github/workflows/ios-engine.yml
  with:
    app-artifact: launcher-ios-app   # artifact containing TRS.app or an unsigned .ipa
    version: ${{ needs.launcher-ios.outputs.version }}
    download-url: https://github.com/<owner>/<repo>/releases/download/v<version>/TRS-Launcher.ipa
```

The Swift package does **not** link the engine; `Engine.swift` loads `Frameworks/libtrsengine.dylib` at
runtime. A launcher build without the engine still works (`game.engineMissing`).

## Hooks for other modules

- Touch overlay: set `GameViewController.overlayFactory = { input in MyOverlayView(input) }`; it gets a
  `GameInput` (GLFW keys, coordinates in view points) and `Notification.Name.trsGameGrabChanged`.
- Hardware keyboard (UIKey → GLFW, `KeyMapping.swift`) and mouse (`GCMouse`) are handled here.
  Game controllers are not mapped yet.

## Entitlements (`trs-game.entitlements`)

`get-task-allow` (needed for JIT), `com.apple.developer.kernel.increased-memory-limit`,
`com.apple.developer.kernel.extended-virtual-addressing`. Free Apple accounts usually lose the last two
when AltStore/SideStore re-sign – the app detects this at runtime.

## Not verified yet (needs the macOS CI and a real device)

Everything native/Swift was written without a Mac. Verified here: the Rust logic (unit tests, also
`cargo clippy --target aarch64-apple-ios`), `TrsBoot` (compiled for Java 8, run against Pojav's class
loader on desktop Java), the JavaApp patch (`git apply --check`), the iOS JRE archives (all four
downloaded, hashed and unpacked by the shared installer), shell scripts (shellcheck) and the workflow
(actionlint). Unverified: Objective-C/Swift compilation, CMake on the iPhoneOS SDK, MobileGlues build,
the JLI `main()` redirect, JIT/TXM behaviour, rendering, input and exit handling on a device.
