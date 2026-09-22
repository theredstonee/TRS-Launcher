# TRS Client

The in-game client mod of the TRS Launcher (Lunar/Badlion style). Fabric, client-only, one jar per
Minecraft release from **1.20.1 to 26.3** (23 versions, see below).
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

## Supported versions

Every Minecraft release from 1.20.1 to 26.3 with Fabric API builds (checked against Mojang's version manifest
and meta.fabricmc.net): 1.20.1–1.20.6, 1.21–1.21.11, 26.1, 26.1.1, 26.1.2, 26.2, 26.3. No release in that range was skipped.

Each version gets its **own jar** whose `fabric.mod.json` depends on exactly that Minecraft version
(`trsclient-fabric-<minecraft>.jar`, requires Fabric API). Java: 17 for 1.20.1–1.20.4, 21 for 1.20.5–1.21.11, 25 for 26.x.

## Build

Requires a JDK 21+ to run Gradle (Gradle toolchains download JDK 17/21/25 for compiling/running if missing).

```sh
./gradlew build                      # builds every version + runs the unit tests of common/
./gradlew collectLauncherJars        # builds everything, writes dist/*.jar + dist/builds.json for the launcher
./gradlew :fabric:1.21.1:build       # a single version
./gradlew :fabric:1.21.1:runClient   # starts that Minecraft version with the mod (game dir: client-mod/run)
./gradlew :fabric:26.3:runClient -PtrsAutotest   # self-test: menu, test world, screenshots, quits
```

Self-test screenshots: `run/screenshots/trsclient-<minecraft>-*.png` (one test world per version).

### Launcher contract: `dist/`

`collectLauncherJars` writes (git-ignored) `dist/trsclient-fabric-<minecraft>.jar` and `dist/builds.json`:

```json
[{ "loader": "fabric", "minecraft": ["1.21.1"], "file": "trsclient-fabric-1.21.1.jar", "requires": ["fabric-api"] }, ...]
```

`minecraft` lists every exact game version the jar supports. Entries of other projects/loaders already present
in `builds.json` are kept (only Fabric entries for the built versions are replaced).

## Layout

```
common/                   version-independent Java (modules, settings, config, HUD layout math, CPS, zoom) + unit tests
fabric/                   Minecraft code, ONE source tree for all versions (Stonecutter)
  src/                    sources, checked in for the 1.21.1 state
  versions/<mc>/          per-version gradle.properties (Fabric API version) + build output
  build.gradle            build script run for every version (Loom, Java level, dependencies)
  stonecutter.gradle      Stonecutter controller + collectLauncherJars
```

### Multi-version with Stonecutter

[Stonecutter](https://stonecutter.kikugie.dev/) preprocesses `fabric/src` per version. Differences are written as
comments that Stonecutter toggles:

```java
//? if >=1.21.6 {
/*g.pose().pushMatrix();
*///?} else
g.pose().pushPose();
```

The version-specific code is kept in a few places:

- `ui/Gfx` – drawing (GuiGraphics → GuiGraphicsExtractor in 26.1, PoseStack → Matrix3x2fStack in 1.21.6)
- `compat/Mc` – screen/overlay/HUD access (moved to `Minecraft.gui` in 26.2)
- screens – render/input entry points (input events changed in 1.21.9, scroll in 1.20.2) delegate to shared logic
- mixins – FOV (`GameRenderer#getFov`, double until 1.21.1, float after; `Camera#calculateFov` in 26.x),
  lightmap (`LightTexture` → `LightmapRenderStateExtractor` in 26.x), mouse (`onPress` → `onButton` in 1.21.9)
- HUD registration – `HudRenderCallback` until 1.21.5, Fabric's `HudElementRegistry` from 1.21.6

`common/src/main/java` is compiled into every version jar with that version's Java level.
To edit code for another version in the IDE: `./gradlew "Set active project to 26.3"`, and switch back to 1.21.1
before committing. Minecraft 26.x is not obfuscated, so those versions use Loom without remapping/mappings.

Adding a version: add it to `settings.gradle` (`stonecutter { ... }`) and create `fabric/versions/<mc>/gradle.properties`
with `fabric_api_version=...`.
