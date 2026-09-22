# TRS Client

The in-game client mod of the TRS Launcher (Lunar/Badlion style). Fabric, Minecraft **1.21.1**, client-only.
License: GPL-3.0-only, author: theredstonee.

## Features

All features can be toggled in the TRS menu. Settings are stored in `config/trsclient.json`.

| Module | What it does |
| --- | --- |
| FPS | Frames per second |
| CPS | Left/right clicks within the last 1000 ms |
| Keystrokes (Tastenanzeige) | W A S D, left/right mouse button (optional CPS), space bar – lit while pressed |
| Ping | Latency to the current server from the player list (hidden in singleplayer) |
| Zoom | Hold key → FOV divided by the zoom factor (default ×4), smooth transition, mouse wheel adjusts the zoom, slower mouse while zooming |
| Fullbright | Maximum brightness; only overrides the gamma used for the lightmap, the vanilla brightness option is never changed |

HUD modules have text color, background and size settings and can be dragged in **HUD bearbeiten**
(snaps to screen edges and center; mouse wheel = size, right click = reset, Shift = no snapping).
Positions are stored as anchor + offset relative to the screen size, so they survive resolution/GUI-scale changes.

## Keys

Listed under **TRS Client** in the vanilla controls menu.

| Key | Action |
| --- | --- |
| Right Shift | Open the TRS menu |
| C (hold) | Zoom (note: vanilla also uses C for "save hotbar activator"; rebind if needed) |
| unbound | Toggle Fullbright (also switchable in the menu) |

## Build

Requires a JDK 21+ to run Gradle (Gradle toolchains download JDK 21 for compiling/running if missing).

```sh
./gradlew build          # builds the mod and runs the unit tests
./gradlew runClient      # starts Minecraft with the mod (game dir: client-mod/run)
./gradlew runClient -PtrsAutotest   # self-test: opens menu, loads a test world, takes screenshots, quits
```

Built jar: `fabric-1.21.1/build/libs/trsclient-fabric-1.21.1-<version>.jar` (requires Fabric Loader and Fabric API).
Self-test screenshots: `run/screenshots/trsclient-*.png`.

## Layout

```
common/          version-independent Java (modules, settings, config, HUD layout math, CPS, zoom) + unit tests
fabric-1.21.1/   Minecraft-facing code for 1.21.1 (HUD rendering, screens, keybindings, mixins)
```

More versions (1.8.9 via Legacy Fabric, 26.x) are planned as additional subprojects that reuse `common`.
