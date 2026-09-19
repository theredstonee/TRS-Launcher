# TRS Launcher

A free, non-commercial third-party launcher for **Minecraft: Java Edition** on Windows,
built with [Tauri 2](https://tauri.app) (Rust) and [Nuxt 4](https://nuxt.com).

> **Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.**

## Goals

- Manage multiple isolated game instances (own worlds, mods and settings each)
- All game versions, with Vanilla, Fabric, Quilt, Forge and NeoForge
- Automatic Java runtime management
- Mod and modpack installation from Modrinth and CurseForge

## Accounts & fair play

- Sign-in works **only with a legitimately owned Microsoft account**, using Microsoft's
  official OAuth 2.0 flow (authorization code + PKCE, device code as fallback).
- There is **no offline or "cracked" mode**, and ownership checks are never bypassed.
- Game files are **not redistributed** – they are downloaded from Mojang's official
  servers directly to the user's machine.
- Credentials never pass through the launcher. Tokens are stored locally and encrypted
  on the user's device and are never sent to any third-party server.

## Status

Early development.

1. ✅ Foundation: instances, settings, version manifest
2. Vanilla launch: version metadata, downloads with progress, Java runtimes, game log console
3. Microsoft sign-in
4. Fabric / Quilt
5. Forge / NeoForge
6. Modrinth / CurseForge, modpack import
7. Auto-updater, installer, polish

## Development

Requirements: Node.js + pnpm, Rust (stable, MSVC toolchain) with the Visual Studio
Build Tools ("Desktop development with C++"), and WebView2 (preinstalled on Windows 10/11).

```sh
pnpm install
pnpm app:dev      # Nuxt dev server + Tauri window with hot reload
```

| Command | Purpose |
|---|---|
| `pnpm app:build` | Release build + NSIS installer (`src-tauri/target/release/bundle`) |
| `pnpm typecheck` | Type-check TypeScript/Vue |
| `cargo test --workspace` (in `src-tauri`) | Rust tests |
| `cargo clippy --workspace --all-targets` (in `src-tauri`) | Rust lints |

Set `TRS_LAUNCHER_HOME` to override the data directory (default: `%APPDATA%\TRS-Launcher`).

## Architecture

```
app/                    Nuxt frontend (SPA, ssr: false)
  utils/backend.ts      typed wrappers around the Rust commands
  utils/schemas.ts      zod validation (mirrors the rules in the core)
src-tauri/
  src/                  Tauri app: commands, error mapping, plugins
  crates/core/          trs-core – UI-independent launcher core
    instance.rs         instances (instances/<id>/instance.json)
    settings.rs         global settings
    meta/               Mojang metadata (cached version manifest)
    paths.rs            directory layout
```

Principles:

- **Logic lives in `trs-core`**; the Tauri layer stays thin. The core validates every
  input itself – the frontend validates additionally for fast feedback.
- **The webview gets no file system, network or shell permissions.** Everything goes
  through dedicated commands (`src-tauri/capabilities/default.json`), under a strict CSP.
- **Errors:** only a stable `kind` and a user-facing message reach the frontend; details
  go to the log.
