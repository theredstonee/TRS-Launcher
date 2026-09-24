<div align="center">

[**English**](README.md) · [Deutsch](README.de.md) · [Español](README.es.md)

<img src="docs/logo.png" alt="TRS Launcher" width="96" height="96" />

# TRS Launcher

**A fast, modern Minecraft: Java Edition launcher for Windows and Linux, with a built-in client for FPS, HUD and PvP features.**

[![Latest release](https://img.shields.io/github/v/release/theredstonee/TRS-Launcher?include_prereleases&sort=semver&label=release&color=e0281e)](https://github.com/theredstonee/TRS-Launcher/releases)
[![Release build](https://img.shields.io/github/actions/workflow/status/theredstonee/TRS-Launcher/release.yml?label=build)](https://github.com/theredstonee/TRS-Launcher/actions/workflows/release.yml)
[![Downloads](https://img.shields.io/github/downloads/theredstonee/TRS-Launcher/total?color=ffb84d)](https://github.com/theredstonee/TRS-Launcher/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
<br />
[![Platform: Windows](https://img.shields.io/badge/platform-Windows%2010%2F11-0078D6?logo=windows&logoColor=white)](#windows)
[![Platform: Linux](https://img.shields.io/badge/platform-Linux-FCC624?logo=linux&logoColor=black)](#linux)
[![Tauri 2](https://img.shields.io/badge/Tauri-2-24C8DB?logo=tauri&logoColor=white)](https://tauri.app)
[![Rust](https://img.shields.io/badge/Rust-2024-000000?logo=rust&logoColor=white)](https://www.rust-lang.org)
[![Nuxt 4](https://img.shields.io/badge/Nuxt-4-00DC82?logo=nuxt&logoColor=white)](https://nuxt.com)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.5.2%E2%80%9326.x-62B47A)](#features)

[Download](https://github.com/theredstonee/TRS-Launcher/releases) ·
[Wiki](https://github.com/theredstonee/TRS-Launcher/wiki) ·
[Features](#features) ·
[Building](#building-from-source) ·
[Architecture](#architecture)

<img src="docs/screenshot-home.png" alt="TRS Launcher start page" width="860" />

</div>

> [!NOTE]
> TRS Launcher is in early development. Releases are published as pre-releases and update themselves automatically.

## Features

**Playing**
- Every Minecraft version from 1.5.2 to the latest 26.x, including snapshots
- **Fabric, Quilt, Forge and NeoForge** — Forge/NeoForge are installed with our own installer and processor runner
- Automatic Java: the right Mojang runtime is downloaded for each version
- **TRS Boost** — vanilla instances start with Fabric, the TRS Client and proven performance mods (Sodium, Lithium, FerriteCore, ImmediatelyFast, ModernFix, …). Versions without Fabric use Forge with the TRS Client. You can turn it off per instance for real vanilla
- Tuned JVM defaults (G1/ZGC by Java version) and the dedicated GPU on laptops
- Games keep running when you close the launcher and are picked up again on the next start

**TRS Client (in-game mod)**
- Bundled for **Fabric/Quilt 1.14.4–26.3, Forge 1.7.10–26.3 and NeoForge 1.20.2–26.3**, installed automatically in every matching instance
- In-game menu (Right Shift) with tiles, search, categories and per-module settings, including a colour picker with alpha and chroma
- HUD with FPS, CPS, keystrokes, ping, armour, effects, coordinates and more; the HUD editor snaps modules to the screen and to each other, and several layouts can be kept as profiles and switched by a key
- PvP displays (reach, combo, speed), custom crosshair, hit colour, 1.7 animations, low fire, block outline, hitboxes and no hurt cam
- **Minimap** and **waypoints** per world or server, with distance, light beam and a death point
- Chat improvements (timestamps, stacking of repeated messages, Ctrl+click to copy), zoom (V), fullbright and freelook
- A redstone-styled title screen and menu that take the launcher's theme and accent colour
- Fair play: no reach or hitbox changes, no auto-clicking; the minimap only shows loaded chunks, without cave view
- Updates through its own signed channel, without a launcher release

**Content**
- Browse and install mods, modpacks, resource packs, data packs and shaders from **Modrinth**, dependencies included, with a project page for description, gallery, versions and dependencies
- Pick any version, check for updates, read changelogs, enable or disable mods per instance
- Import instances from the **official launcher, Modrinth App, CurseForge, Prism Launcher, MultiMC** or any folder
- Export any instance as a **`.mrpack`** — mods that exist on Modrinth are linked, everything else is packed as overrides — and import pack files again

**TRS services (optional)**
- **TRS capes**: pick from the TRS cape collection (some of them animated), unlock capes with codes or upload your own, which the team reviews before others see it
- **Friends**: friend requests, blocking, who is online and what they are playing, and one click to join them on their server
- Everything is **off until you agree**; see [PRIVACY.md](PRIVACY.md#trs-services)

**Everything else**
- **Redstone look**: a live redstone circuit on the start page and behind every page, a lamp as the play button, dark, OLED, light or system theme and five accent colours
- Multiple Microsoft accounts, switched from the title bar (tokens encrypted with Windows DPAPI, on Linux with a key from the system keyring)
- **Background tasks**: installs and downloads keep running while you use the launcher, with a tasks panel to pause, resume or cancel them and a history of finished tasks
- Server list with live player count and ping, one-click join
- **Skins & capes** with a 3D preview: keep your own skin library, switch model (classic/slim), pick any Mojang cape you own and apply all changes at once
- **Screenshot gallery** across all instances with a fullscreen viewer, copy to clipboard and recycle bin
- **Clips & recording** like ShadowPlay/Medal (off by default): F9 in game saves the last 15–120 seconds, F10 starts and stops a recording — only the game window, hardware-encoded, with system sound (microphone optional). A Clips page plays, renames and deletes them, with a storage limit. Nothing leaves your PC ([details](#clips--recording))
- **News** on the start page: Minecraft patch notes, Mojang news, trending Modrinth projects and launcher releases
- Live game log with filters, crash diagnosis, file repair and log sharing via mclo.gs (tokens redacted)
- Worlds, play time, instance banners and duplication, a command palette (Ctrl+K)
- **Silent updates**: new versions are signed, download in the background and install when you restart from the title bar; running games keep running

## Installation

### Windows

1. Download `TRS-Launcher_<version>_x64-setup.exe` from the [releases page](https://github.com/theredstonee/TRS-Launcher/releases).
2. Run it. It installs for your Windows user only and needs no administrator rights.
3. Sign in with the Microsoft account that owns Minecraft.

> [!TIP]
> The installer isn't code-signed yet, so Windows SmartScreen may show "Windows protected your PC". Choose **More info → Run anyway**.

Your data lives in `%APPDATA%\TRS-Launcher`. Step-by-step guides are in the [wiki](https://github.com/theredstonee/TRS-Launcher/wiki).

### Linux

Every release has an **AppImage**, a **.deb** and an **.rpm** for x86_64 (built on Ubuntu 22.04, so any distribution with glibc 2.35 or newer works). You don't need Java — the launcher downloads the right Mojang runtime itself.

| Distribution | Install |
|---|---|
| **Arch Linux**, Manjaro, EndeavourOS | `yay -S trs-launcher-bin` (or `paru -S trs-launcher-bin`) from the AUR |
| **Debian, Ubuntu**, Linux Mint, Pop!_OS | `sudo apt install ./TRS-Launcher_<version>_amd64.deb` |
| **Fedora**, openSUSE | `sudo dnf install ./TRS-Launcher-<version>-1.x86_64.rpm` (openSUSE: `sudo zypper install …`) |
| **Any distribution** | `chmod +x TRS-Launcher_<version>_amd64.AppImage && ./TRS-Launcher_<version>_amd64.AppImage` |
| **Flatpak** | `flatpak install flathub dev.theredstonee.trslauncher` (once it is published on Flathub) |

- **Updates:** the AppImage updates itself like the Windows version. The .deb, .rpm, AUR and Flatpak packages are updated by your package manager; the launcher only tells you when a new version is out.
- Your data lives in `~/.local/share/TRS-Launcher` (Flatpak: `~/.var/app/dev.theredstonee.trslauncher/data/TRS-Launcher`).
- Sign-in keys are stored in the system keyring (GNOME Keyring, KWallet, KeePassXC …). Without a keyring they are kept in a file only your user can read, and the settings show a hint.
- Minecraft 1.12.2 and older (LWJGL 2) need the `xrandr` tool: `xorg-xrandr` (Arch), `x11-xserver-utils` (Debian/Ubuntu), `xrandr` (Fedora).
- Works on Wayland and X11 (the game itself runs through XWayland). With the proprietary NVIDIA driver the launcher turns off WebKit's DMA-BUF renderer to avoid a blank window.
- The AppImage needs FUSE 2 (`libfuse2`/`fuse2`); without it, run it with `--appimage-extract-and-run`.
- Not available on Linux yet: the Windows firewall helper (not needed) and clip recording. ARM64 has no Mojang Java runtime — set your own Java in the settings.

## Fair play

- Sign-in works **only with a legitimately owned Microsoft account**, using Microsoft's official OAuth 2.0 flow (authorization code + PKCE, device code as fallback).
- There is **no offline or "cracked" mode**, and ownership checks are never bypassed.
- Game files are **not redistributed**. They are downloaded from Mojang's official servers directly to your PC.
- Your password never passes through the launcher. Tokens stay on your PC, encrypted, and are never sent to any third-party server.

## Building from source

**Requirements:** Node.js 22+ with pnpm, Rust (stable, MSVC toolchain), the Visual Studio Build Tools ("Desktop development with C++") and WebView2 (preinstalled on Windows 10/11).

**On Linux** you need Node.js 22+ with pnpm, Rust (stable) and the WebKitGTK/GTK development packages:

```sh
# Debian/Ubuntu
sudo apt install build-essential curl file pkg-config libssl-dev libwebkit2gtk-4.1-dev libgtk-3-dev libayatana-appindicator3-dev librsvg2-dev libxdo-dev
# Arch
sudo pacman -S --needed base-devel webkit2gtk-4.1 gtk3 libayatana-appindicator librsvg openssl xdotool
# Fedora
sudo dnf install gcc-c++ openssl-devel webkit2gtk4.1-devel gtk3-devel libappindicator-gtk3-devel librsvg2-devel libxdo-devel
```

`pnpm tauri build --bundles appimage,deb,rpm` builds the Linux packages. The AUR and Flatpak recipes are in [`packaging/`](packaging/).

```sh
pnpm install
pnpm app:dev      # Nuxt dev server + Tauri window with hot reload
```

| Command | Purpose |
|---|---|
| `pnpm app:build` | Release build and NSIS installer (`src-tauri/target/release/bundle`) |
| `pnpm typecheck` | Type-check TypeScript/Vue |
| `pnpm test` | Frontend tests (Vitest) |
| `cargo test --workspace` (in `src-tauri`) | Rust tests |
| `cargo clippy --workspace --all-targets` (in `src-tauri`) | Rust lints |
| `./gradlew collectLauncherJars` (in `client-mod/…`) | Build the TRS Client jars for the launcher |

Set `TRS_LAUNCHER_HOME` to use a different data directory, which is handy for testing. The launch pipeline can also be tested without the UI:

```sh
cargo run -p trs-core --example launch -- <data-dir> 1.21.1 [vanilla|fabric|quilt|forge|neoforge] [seconds]
```

Development builds without a signed-in account start the game in its official **demo mode**. Release builds always require an account that owns the game.

### Releases

Pushing a tag like `v0.2.0` runs [`release.yml`](.github/workflows/release.yml). It builds and signs the Windows installer and the Linux AppImage, .deb and .rpm, publishes a release (with a ready-made AUR `PKGBUILD`) and, once both platforms are done, refreshes the update channel the built-in updater polls.

### TRS Client updates

The in-game TRS Client has its own update channel, so it can be updated without a launcher release. The GitHub release `client-mod` holds `client-mod.json` (mod version plus every build with SHA-256 and size), its minisign signature `client-mod.json.sig` and all jars. The launcher checks the channel at startup and at most every 30 minutes before a game starts (short timeout; offline it keeps using what it has), accepts only manifests signed with the updater key and newer than the bundled version, and downloads only the jar the instance being launched needs into `<data>/client-mod/<version>/`, verified against the manifest. On any error it falls back to the bundled jar.

```sh
node scripts/publish-client-mod.mjs --bump patch   # raise mod_version, then rebuild the jars (collectLauncherJars)
node scripts/publish-client-mod.mjs --dry-run      # merge client-mod/dist, sign, verify, update src-tauri/resources/client-mod
node scripts/publish-client-mod.mjs                # same, then upload to the client-mod release (needs gh)
```

The script signs with `%USERPROFILE%\.tauri\trs-launcher.key` (password from `trs-launcher.key.password`) and checks the signature against `plugins.updater.pubkey` before uploading. To check a produced manifest with the launcher's own verifier: `TRS_CLIENT_MOD_CHANNEL_DIR=client-mod/dist/channel TRS_CLIENT_MOD_DIST=client-mod/dist cargo test -p trs-core published_manifest -- --ignored`.

## Architecture

```
app/                    Nuxt 4 frontend (SPA, no SSR)
src-tauri/
  src/                  Tauri app: commands, task registry, error mapping, plugins
  crates/core/          trs-core: the UI-independent launcher core
    meta/ prepare.rs    Version metadata, libraries, natives, assets
    forge.rs loaders.rs Forge/NeoForge installer, Fabric/Quilt profiles
    launch.rs process.rs  Arguments, detached game processes, logs, crash diagnosis
    auth/               Microsoft → Xbox Live → Minecraft, encrypted account store
    modrinth.rs modpack.rs content.rs  Modrinth, modpacks, per-instance content
    modpack_export.rs   .mrpack export (Modrinth lookup by hash, overrides)
    skins.rs skin_sync.rs news.rs screenshots.rs  Minecraft profile/skins, news cache, screenshot gallery
    task.rs task_history.rs  Cancellable, pausable background tasks and their history
    trs_api/            Client for the optional TRS services (capes, friends, presence)
    client_mod.rs client_mod_update.rs  Bundled TRS Client builds and the signed update channel
    clips/              Clips & recording: FFmpeg (on demand), window capture, WASAPI sound, ring buffer, local link to the mod
    import.rs servers.rs boost.rs hooks.rs sync.rs
  resources/client-mod/ Bundled TRS Client builds + builds.json (manifest with version + checksums)
client-mod/             TRS Client: common core + Fabric 1.14.4–26.3, Forge 1.7.10–26.3, NeoForge 1.20.2–26.3
```

Principles:

- **Logic lives in `trs-core`**, and the Tauri layer stays thin. The core validates every input itself.
- **The webview gets no file system, network or shell permissions.** Everything goes through dedicated commands, under a strict CSP.
- **Only a stable error kind and a readable message reach the UI.** Details go to the log.

## Clips & recording

The **launcher** records, not the mod – that way it works the same in all TRS Client builds (1.7.10 to 26.3) and
costs the game nothing but a key binding.

- **Why FFmpeg:** FFmpeg 8.1+ has `gfxcapture`, a filter built on Windows Graphics Capture. It captures one window
  by its handle (never the screen, no yellow border, no cursor tricks) and hands D3D11 frames straight to the
  hardware encoders NVENC, AMF and Quick Sync (plus Media Foundation and libx264 as fallbacks) – no copies through
  the CPU. Writing this ourselves on Media Foundation would mean re-implementing capture, scaling, encoder
  fallbacks, segmenting and MP4 muxing. FFmpeg is **not bundled**: when clips are switched on, the launcher downloads
  one fixed build (Gyan's `ffmpeg-9.0.2-essentials`, GPL-3.0 like the launcher) over HTTPS from the permanent
  GitHub mirror, checks the SHA-256 of the ZIP and of `ffmpeg.exe` against values in the code
  (`clips/ffmpeg.rs`) and keeps only `ffmpeg.exe` + its licence in `<data>/tools/ffmpeg-9.0.2`. The hash of the exe
  is re-checked before use.
- **Encoder:** "Automatic" test-encodes a few frames once per session and uses the first that works: NVENC → AMF →
  QSV → Media Foundation → x264 (x264 runs at lower priority).
- **Sound:** FFmpeg has no WASAPI input, so the launcher captures the default output device in loopback mode (and
  the default microphone if enabled) with the `wasapi` crate, mixes them to 48 kHz stereo and pipes the PCM into
  FFmpeg's stdin. Picture and sound share one clock: video timestamps are moved onto the wall clock
  (`setpts` with `RTCTIME`), and every audio sample is placed by its WASAPI timestamp on the same clock; gaps
  (loopback delivers nothing while it is silent) become silence, so sound never drifts.
- **Ring buffer:** FFmpeg writes 2-second MPEG-TS segments (a keyframe at every segment start) into
  `<data>/cache/clip-buffer/`; the launcher deletes segments older than the clip length – RAM use stays flat.
  **F9** waits for the running segment to finish and joins the last *n* segments into an MP4 without re-encoding
  (`-c copy`, `+faststart`) in `<clip folder>/<instance>/<instance> <date> <time>.mp4`. **F10** keeps all
  segments from the start of the recording until F10 again (a recording stops by itself below 2 GB free space).
  Over the storage limit the oldest clips go to the recycle bin.
- **When:** only while a game started by this launcher runs and clips are on. The launcher finds the game window
  through the game's process tree; resizing is handled by FFmpeg (scaled into the chosen resolution), closing the
  game ends the session and any running recording is still saved.
- **Link to the mod:** before every start the launcher writes `config/trsclient/clips.json` into the instance:
  `{"version":1,"enabled":true,"port":…,"token":"<64 hex>"}` (or `enabled:false`). The server listens on
  **127.0.0.1 only**, on a random port; the token is new for every game start, compared in constant time, and
  invalid once the game ends. Line-based JSON (max. 1 KB per line): the mod sends `hello` with the token and then
  only `clip`/`record`; the launcher answers with the recording state and `saved`/`failed`. Nothing else is
  accepted, nothing leaves the PC.

## Code signing policy

Windows releases are signed so that Windows can verify who published them.

- Free code signing provided by [SignPath.io](https://about.signpath.io/), certificate by [SignPath Foundation](https://signpath.org/).
- Builds are made from this repository by [GitHub Actions](.github/workflows/release.yml). Every release needs manual approval before it is signed.

**Team roles**

| Role | Members |
|---|---|
| Committers and reviewers | [theredstonee](https://github.com/theredstonee) |
| Approvers | [theredstonee](https://github.com/theredstonee) |

**Privacy:** this program will not transfer any information to other networked systems unless specifically requested by the user or the person installing or operating it. The optional TRS services (capes, friends, online status) only connect after you agree in the launcher. See [PRIVACY.md](PRIVACY.md) ([Deutsch](PRIVACY.de.md) · [Español](PRIVACY.es.md)) for the services the launcher contacts and when.

## Acknowledgements

Parts of the process handling are adapted from [Polyfrost OneLauncher](https://github.com/Polyfrost/OneLauncher) (GPL-3.0-only). Game metadata comes from Mojang, and mod data comes from the [Modrinth API](https://docs.modrinth.com/).

## License

TRS Launcher is licensed under the [GNU General Public License v3.0](LICENSE).

<sub>TRS Launcher is not an official Minecraft product. It is not approved by or associated with Mojang or Microsoft.</sub>
