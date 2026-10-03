# TRS Launcher for Android and iOS (companion app)

The launcher builds as a Tauri 2 mobile app. On the phone it is a **companion**: accounts (device-code login),
friends and chat, skins and capes, modpacks/sharing, news. Minecraft itself does **not** start on the phone yet –
a native game engine will come later as its own plugin.

## What the app reports (`app_info().capabilities`)

| Field            | Desktop                        | Android / iOS                         |
|------------------|--------------------------------|---------------------------------------|
| `platform`       | `windows` / `linux` / `macos`  | `android` / `ios`                     |
| `gameLaunch`     | `true`                         | `false` (until the engine plugin)     |
| `gameEngine`     | `false`                        | `false` – hook for the engine plugin  |
| `java`           | `true`                         | `false`                               |
| `clips`          | Windows only                   | `false`                               |
| `firewall`       | Windows only                   | `false`                               |
| `windowControls` | `true`                         | `false`                               |
| `trash`          | `true`                         | `false`                               |
| `updates`        | `auto` / `package` / `flatpak` | `mobile`                              |
| `pushSupported`  | `false`                        | `false` (no server push yet)          |

Desktop-only commands (game launch, repair, Java, clips, firewall, TRS Client/FPS mode, hosting join) return the
error kind `unsupported_on_mobile` (code `unsupportedOnMobile`) on the phone.

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

Without these secrets the release still builds the APK (uploaded as an unsigned workflow artifact) but does not
publish anything to the `mobile` channel. `TAURI_SIGNING_PRIVATE_KEY` / `_PASSWORD` (already used for the desktop
updater) sign `mobile.json`.
