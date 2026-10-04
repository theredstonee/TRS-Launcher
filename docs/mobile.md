# TRS Launcher for Android and iOS

The launcher builds as a Tauri 2 mobile app: accounts (device-code login), friends and chat, skins and capes,
modpacks/sharing, news – and Minecraft Java through the built-in game engine (plugin
`src-tauri/plugins/tauri-plugin-trs-game`, Android: own process `:trsgame`; iOS: in the app process, sideload + JIT)
with the touch overlay (`docs/touch-mode.md` for the TRS Client side).

## What the app reports (`app_info().capabilities`)

| Field            | Desktop                        | Android / iOS                         |
|------------------|--------------------------------|---------------------------------------|
| `platform`       | `windows` / `linux` / `macos`  | `android` / `ios`                     |
| `gameLaunch`     | `true`                         | `true` (through the game engine)      |
| `gameEngine`     | `false`                        | `true`                                |
| `java`           | `true`                         | `false`                               |
| `clips`          | Windows only                   | `false`                               |
| `firewall`       | Windows only                   | `false`                               |
| `windowControls` | `true`                         | `false`                               |
| `trash`          | `true`                         | `false`                               |
| `updates`        | `auto` / `package` / `flatpak` | `mobile`                              |
| `pushSupported`  | `false`                        | `true` (UnifiedPush / poll, see below) |

Desktop-only commands (Java, repair, clips, firewall, TRS Client/FPS mode, hosting join, moving the data folder or
an instance) return the error kind `unsupported_on_mobile` (code `unsupportedOnMobile`) on the phone.

## Game start on the phone

`launch_instance` → `mobile_game::launch`: the core prepares everything like on the desktop
(`Launcher::prepare_mobile_launch` → `GameLaunchSpec` with `touchProfile` from `controls::resolve` and
`controlsDir`), the plugin downloads the pinned Java runtime once and starts the engine. The session is then attached
to the core's game manager (`GameManager::attach_engine`, PID 0): `trs-game://state` / `trs-game://log` become the
normal `game-event`s (`started`, `logs`, `exited` with play time, crash diagnosis and crash helper), "Stop" ends the
engine session. Only one game runs at a time. iOS ends the app with the game: the engine writes
`Documents/trs-last-session.json`, and the next app start books the play time (`engine-sessions.json` remembers the
running session).

Touch overlay (Android): the engine loads the overlay named in the manifest meta-data
`dev.theredstonee.trs.game.OVERLAY_PROVIDER` – the plugin registers `dev.theredstonee.trsgame.overlay.TouchOverlayProvider`
(the built-in `FallbackOverlay` is only used if it is missing). In game menus the overlay shows "Edit controls". The
TRS Client's keyboard requests come from `config/trsclient/touch-state.json` (polled) and its `[TRS-Touch]` log lines.

### Minecraft 26.x (SDL3, SPIRV-Cross, shaderc)

26.2 loads SPIRV-Cross and shaderc at start, 26.3 additionally replaces GLFW with SDL3
(`NativeLibrariesBootstrap`: spvc, SDL, OpenGL, shaderc …). The engine ships `libSDL3.so` and
`libspirv-cross-c-shared.so` (prebuilt.lock, same builds as Amethyst/FCL/Zalith) and `libshaderc.so`
in the LWJGL 3.4.1 natives, and points LWJGL at them (`-Dorg.lwjgl.spvc|shaderc|sdl.libname`).
The core marks SDL versions (`usesSdl`, version has `org.lwjgl:lwjgl-sdl`); for those the game activity
registers its surface with the SDL Java glue (`SdlHost`, `org.libsdl.app` from Amethyst). `SDL_Init`
in the game reaches the engine through the LWJGL stub (`notifyLauncher`), which loads SDL3 into the
Android VM. Input goes to the GLFW bridge and to SDL; the mouse grab comes from
`SDLActivity.setRelativeMouseEnabled`. Android allows one SDL window: `jni/trs_sdl.c` hands
Minecraft's second window (GL context probe) the first one and sizes it to the game surface.
26.3 gets `preferredGraphicsBackend:"vulkan"` in `options.txt` unless the player chose a backend:
MobileGlues cannot translate the 26.3 shaders to GLSL ES (FCL/Zalith also limit their GL renderers
to 26.3-snapshot-3), and Minecraft falls back to OpenGL when Vulkan lacks
`VK_KHR_dynamic_rendering` / `VK_KHR_push_descriptor`.
Emulator limits: the emulator's Vulkan (gfxstream, also with lavapipe/SwiftShader) lacks those
extensions, and 26.x on OpenGL hangs in `glGenTextures` on gfxstream – 26.x cannot be played in the
emulator, test on a phone.

iOS: Amethyst-iOS (9212a189) has no SDL3 glue, only GLFW. 26.3 on iOS needs an SDL3 UIKit build
whose window wraps the engine's view, the same `notifyLauncher`/window reuse and the Vulkan path via
MoltenVK; the Java-side flags (`-Dorg.lwjgl.spvc|shaderc.libname`, `preferredGraphicsBackend`) are
the same. Until then 26.3 does not start on iOS (26.2 and older use GLFW as before).

### 16 KB memory pages

Our own engine libraries are built 16 KB-aligned (`APP_SUPPORT_FLEXIBLE_PAGE_SIZES`). The prebuilt
renderers, LWJGL natives, SDL3/SPIRV-Cross, bytehook (1.1.x needs shadowhook, no x86_64) and the Java
runtimes are 4 KB-aligned – on 16 KB devices Android runs the app in page size compatibility mode
(`pageSizeCompat`, shows a one-time notice), like FCL and Zalith. Verified on the Android 16 16 KB
emulator with the minified release build (1.21.11 reaches a world).

If the game process dies without a game log, the launcher adds Android's exit reason and, for native
crashes, the signal and the crashing thread's stack (tombstone, Android 12+) to the log (`CrashInfo`).
If the launcher process itself was gone (Android frees background apps when memory is low), the
running session stays in `engine-sessions.json`; at the next start `finish_android_sessions` asks the
plugin (`sessionEnd`): still running → check again every 5 s, otherwise the end is booked with the
tail of `cache/game-<session>.log` plus Android's exit reason. A low-memory kill shows up in the crash
helper as "out of memory". The heap is `min(wanted, RAM/2, 3/4 of the memory free at start)`
(at least 1 GB, `-Xms` at most 512 MB), logged as `[TRS] Arbeitsspeicher: …`.

Platform differences in the Rust core:

- **TLS:** desktop keeps `native-tls`. Android/iOS use rustls with *ring*; Android checks certificates against the
  Mozilla roots (`webpki-root-certs` – the Android system verifier would need an extra Java component), iOS against
  the system trust store (rustls-platform-verifier). All clients come from `trs_core::net::client_builder()`.
- **Token key:** Android Keystore (`android-native-keyring-store`), iOS data-protection Keychain
  (`apple-native-keyring-store`, this device only, after first unlock). Both report `tokenProtection: "keyring"`;
  fallback is a key file with mode 0600 in the app sandbox (`"file"`).
- **Data:** the app's private data directory (`app_data_dir`), not `TRS-Launcher` in the user profile.
- **Not running on mobile:** clips, TRS Link, Discord, TRS presence, firewall, TRS Client channel.
- **Links:** `trs-launcher://pack/<code>` and `trs-launcher://web-login/<token>` (custom scheme on both platforms).

## Push notifications (API §33)

Plugin `src-tauri/plugins/tauri-plugin-trs-push` (no webview permissions – only Rust calls it), core
`trs_core::trs_api::push`, app glue `src-tauri/src/push.rs`, settings page Settings → Notifications
(`MobilePushSettings.vue`).

- **Android: UnifiedPush** via `org.unifiedpush.android:connector` (Apache-2.0, Codeberg). The connector creates the
  Web Push key pair (P-256 + 16-byte auth secret; private key sealed with an Android Keystore AES key) and decrypts
  messages (RFC 8291, `aes128gcm`). `TrsPushService` (Java) receives endpoints and messages even when the app is
  closed and shows them with one notification channel per category; a tap opens `trs-launcher://notify/<route>`,
  which the app maps to its page. The connector is built with Kotlin 2.2 while the app uses 1.9: its kotlin-stdlib is
  excluded (the app's 2.0 stdlib covers its bytecode) and only Java classes call it.
- **No distributor installed:** the settings explain ntfy (F-Droid / Play) or offer polling: a `poll` device plus
  `PushPollWorker` (WorkManager, every 15 min) that loads the Rust core via JNI (`nativePoll`) and shows new entries.
- **iOS:** `poll` device; `BGAppRefreshTask` (`dev.theredstonee.trslauncher.push-poll`, `UIBackgroundModes: fetch`)
  calls `trs_push_poll_json()` from the Rust library and posts local notifications.
- **Lifecycle:** registered after sign-in/consent and re-synced on every start and resume (new app version, language,
  settings, new session = new device); logout deletes the device first. While the app is in the background the
  realtime stream is closed (`trs_live_pause`), otherwise the server would think the app is open; in the foreground
  the stream carries `?pushDevice=<id>`.
- **No double hints:** a push payload's `id` is the event id of the realtime stream. When the app comes back to the
  foreground it hands the ids already shown as system notifications (Android: plugin, iOS: polled ids) to the core
  before reconnecting; replayed events with those ids arrive as `trs-live` with `quiet: true` – the UI updates its
  state but shows no toast. Live events while the app is open keep their toasts.
- **ntfy not connected yet:** ntfy hands out an endpoint even before its app was opened once, but its server refuses
  messages (`507`). After registering, the app sends one small probe to the endpoint (undecryptable, dropped by the
  app); on `507` the settings show "open ntfy once" with a button, and the next sync after returning re-checks.
- **iOS tap:** local notifications carry the route in `userInfo`; a small delegate (forwarding everything else to the
  notification plugin) opens `trs-launcher://notify/<route>`.
- `trs-push.json` holds the switches and, per account, the device id plus a SHA-256 fingerprint of what the server
  knows – the endpoint itself (a secret) only lives in the plugin's private preferences.

## Building locally (Android, Windows)

Requirements: Android SDK (`%LOCALAPPDATA%\Android\Sdk`, with `cmdline-tools`), NDK 28.2.13676358, JDK 21 (the
Android Studio JBR works), Rust targets `aarch64-linux-android` (phones) and `x86_64-linux-android` (emulator).

```powershell
sdkmanager --install "ndk;28.2.13676358"
rustup target add aarch64-linux-android x86_64-linux-android
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:NDK_HOME     = "$env:ANDROID_HOME\ndk\28.2.13676358"
$env:JAVA_HOME    = "C:\Program Files\Android\Android Studio\jbr"
pnpm tauri android build --apk --debug --target x86_64   # emulator
pnpm tauri android build --apk --target aarch64           # phone (unsigned release)
```

The game engine plugin needs NDK r27d (`ndk;27.3.13750724`) in addition and downloads its prebuilt parts
(`android/prebuilt.lock`, SHA-256 pinned) on the first Gradle build. Only 64-bit ABIs are packaged (arm64-v8a,
x86_64) and native libraries are installed extracted (`useLegacyPackaging`), because the engine loads them from
`nativeLibraryDir`. The emulator needs `-gpu host` (SwiftShader crashes in the renderer's Vulkan probe).

On Windows, `tauri android build` links the Rust library into `gen/android/app/src/main/jniLibs` with a symbolic
link; without Developer Mode (or admin rights) that fails with "A required privilege is not held by the client".
Either enable Developer Mode or copy the library yourself
(`<target>/<triple>/debug/libtrs_launcher_lib.so` → `jniLibs/<abi>/`) and run `gradlew assembleX86_64Debug` in
`src-tauri/gen/android`.

The Gradle project is checked in under `src-tauri/gen/android` (minSdk 26). Our own Kotlin code lives there:
`TrsMobilePlugin.kt` (APK installation via `PackageInstaller`). Permissions: `INTERNET`, `POST_NOTIFICATIONS`,
`REQUEST_INSTALL_PACKAGES`.

## iOS

The Xcode project (`src-tauri/gen/apple`) can only be generated on macOS, so it is **not** checked in: CI runs
`scripts/ios-project.sh` (`tauri ios init` + icons) and `scripts/ios-unsigned-ipa.sh` (Xcode archive without code
signing, zipped as `Payload/`). The IPA is unsigned on purpose – AltStore/SideStore sign it with the user's Apple ID
when installing.

## Update channel `mobile`

GitHub release `mobile` (like `updater` and `client-mod`):

- `mobile.json` – `{ version, notes, pubDate, android: { url, sha256, size }, ios: { url, sha256, size, altstore } }`
- `mobile.json.sig` – minisign signature from `tauri signer sign` with the **same key** as the desktop updater and
  the TRS Client channel; the signed comment must contain `file:mobile.json`.
- `TRS-Launcher-<version>.apk` / `.ipa`
- `altstore.json` – AltStore/SideStore source (v2). iOS users add
  `https://github.com/theredstonee/TRS-Launcher/releases/download/mobile/altstore.json` as a source.

The app checks the channel with `mobile_update_check` (signature against the embedded public key, files only from
this repo's releases, SHA-256 + size). On Android `mobile_update_install` downloads the APK as a task (progress,
cancel) into the app's private folder, checks it again and hands it to the `PackageInstaller`; Android always asks
the user. The first time, Android asks to allow "Install unknown apps" for the launcher
(`status: "permissionRequired"` – the settings page is already open, try again afterwards).

`scripts/mobile-channel.mjs` writes and signs `mobile.json` and `altstore.json`; the release workflow uploads them.

## Release secrets (Android signing)

The release job signs the APK with your own upload key. Create it **once** and keep a backup – Android only installs
updates signed with the same key.

```bash
keytool -genkeypair -v -keystore trs-launcher-release.jks -alias trs-launcher \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=TheRedstonee, O=TRS Launcher"
# Base64 for the GitHub secret (one line):
base64 -w0 trs-launcher-release.jks > keystore.b64          # Linux/macOS/Git Bash
# PowerShell: [Convert]::ToBase64String([IO.File]::ReadAllBytes("trs-launcher-release.jks")) > keystore.b64
```

Repository → Settings → Secrets and variables → Actions:

| Secret                      | Value                                           |
|-----------------------------|-------------------------------------------------|
| `ANDROID_KEYSTORE_BASE64`   | content of `keystore.b64`                       |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password from `keytool`                |
| `ANDROID_KEY_ALIAS`         | `trs-launcher` (the `-alias` above)             |
| `ANDROID_KEY_PASSWORD`      | key password (same as the keystore by default)  |

Gradle signs the release APK itself (`signingConfig` in `src-tauri/gen/android/app/build.gradle.kts`) when
`ANDROID_KEYSTORE_PATH` points to the keystore and `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` and
`ANDROID_KEY_PASSWORD` are set – the release job decodes the secret into a temporary file for that; locally you set
the same variables. Without these secrets the release still builds the APK (uploaded as an unsigned workflow artifact) but does not
publish anything to the `mobile` channel. `TAURI_SIGNING_PRIVATE_KEY` / `_PASSWORD` (already used for the desktop
updater) sign `mobile.json`.
