<div align="center">

[**English**](README.md) · [Deutsch](README.de.md) · [Español](README.es.md)

<img src="docs/logo.png" alt="TRS Launcher" width="96" height="96" />

# TRS Launcher

**A fast, modern Minecraft: Java Edition launcher for Windows, with a built-in client for FPS, HUD and PvP features.**

[![Latest release](https://img.shields.io/github/v/release/theredstonee/TRS-Launcher?include_prereleases&sort=semver&label=release&color=e0281e)](https://github.com/theredstonee/TRS-Launcher/releases)
[![Release build](https://img.shields.io/github/actions/workflow/status/theredstonee/TRS-Launcher/release.yml?label=build)](https://github.com/theredstonee/TRS-Launcher/actions/workflows/release.yml)
[![Downloads](https://img.shields.io/github/downloads/theredstonee/TRS-Launcher/total?color=ffb84d)](https://github.com/theredstonee/TRS-Launcher/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
<br />
[![Platform: Windows](https://img.shields.io/badge/platform-Windows%2010%2F11-0078D6?logo=windows&logoColor=white)](#installation)
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
- Multiple Microsoft accounts, switched from the title bar (tokens encrypted with Windows DPAPI)
- **Background tasks**: installs and downloads keep running while you use the launcher, with a tasks panel to pause, resume or cancel them and a history of finished tasks
- Server list with live player count and ping, one-click join
- **Skins & capes** with a 3D preview: keep your own skin library, switch model (classic/slim), pick any Mojang cape you own and apply all changes at once
- **Screenshot gallery** across all instances with a fullscreen viewer, copy to clipboard and recycle bin
- **News** on the start page: Minecraft patch notes, Mojang news, trending Modrinth projects and launcher releases
- Live game log with filters, crash diagnosis, file repair and log sharing via mclo.gs (tokens redacted)
- Worlds, play time, instance banners and duplication, a command palette (Ctrl+K)
- **Silent updates**: new versions are signed, download in the background and install when you restart from the title bar; running games keep running

## Installation

1. Download `TRS-Launcher_<version>_x64-setup.exe` from the [releases page](https://github.com/theredstonee/TRS-Launcher/releases).
2. Run it. It installs for your Windows user only and needs no administrator rights.
3. Sign in with the Microsoft account that owns Minecraft.

> [!TIP]
> The installer isn't code-signed yet, so Windows SmartScreen may show "Windows protected your PC". Choose **More info → Run anyway**.

Your data lives in `%APPDATA%\TRS-Launcher`. Step-by-step guides are in the [wiki](https://github.com/theredstonee/TRS-Launcher/wiki).

## Fair play

- Sign-in works **only with a legitimately owned Microsoft account**, using Microsoft's official OAuth 2.0 flow (authorization code + PKCE, device code as fallback).
- There is **no offline or "cracked" mode**, and ownership checks are never bypassed.
- Game files are **not redistributed**. They are downloaded from Mojang's official servers directly to your PC.
- Your password never passes through the launcher. Tokens stay on your PC, encrypted, and are never sent to any third-party server.

## Building from source

**Requirements:** Node.js 22+ with pnpm, Rust (stable, MSVC toolchain), the Visual Studio Build Tools ("Desktop development with C++") and WebView2 (preinstalled on Windows 10/11).

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

Pushing a tag like `v0.2.0` runs [`release.yml`](.github/workflows/release.yml). It builds and signs the installer, publishes a release and refreshes the update channel the built-in updater polls.

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
    import.rs servers.rs boost.rs hooks.rs sync.rs
  resources/client-mod/ Bundled TRS Client builds + builds.json (manifest with version + checksums)
client-mod/             TRS Client: common core + Fabric 1.14.4–26.3, Forge 1.7.10–26.3, NeoForge 1.20.2–26.3
```

Principles:

- **Logic lives in `trs-core`**, and the Tauri layer stays thin. The core validates every input itself.
- **The webview gets no file system, network or shell permissions.** Everything goes through dedicated commands, under a strict CSP.
- **Only a stable error kind and a readable message reach the UI.** Details go to the log.

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
