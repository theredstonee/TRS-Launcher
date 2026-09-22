# TRS Client

The in-game client mod of the TRS Launcher (Lunar/Badlion style). Client-only, one jar per loader and
Minecraft release: **Fabric 1.14.4–26.3**, **Forge 1.7.10–26.3**, **NeoForge 1.20.2–26.3** (see below).
License: GPL-3.0-only, author: theredstonee.

## Projects

Each loader family is its own Gradle build (own wrapper, own README with hooks and pitfalls); all of them put
their jars and a `builds-<project>.json` into `dist/`, which the launcher bundles as
`src-tauri/resources/client-mod/builds.json`.

| Directory | Loader / versions | Tooling | Rebuild |
| --- | --- | --- | --- |
| `fabric/` (this build) | Fabric 1.14.4–26.3 (39) | Loom + Stonecutter | `./gradlew collectLauncherJars` |
| `neoforge/` | NeoForge 1.20.2–26.3 (22, incl. beta-only versions) | ModDevGradle + Stonecutter | `./gradlew collectLauncherJars` |
| `forge/` | Forge 1.20–26.3 (22) | ForgeGradle 7 + Stonecutter | `./gradlew :collectLauncherJars` |
| `forge-mojmap-legacy/` | Forge 1.14.4, 1.15.2, 1.16.2, 1.16.4, 1.16.5, 1.17.1, 1.18–1.18.2, 1.19–1.19.4 | Essential Loom + Stonecutter | `./gradlew collectLauncherJars` |
| `forge-1.13.2/` | Forge 1.13.2 | ForgeGradle 6 (Gradle 8.14) | `./gradlew build` |
| `legacy/` | Forge 1.8.9, 1.9, 1.9.4, 1.10(.2), 1.11(.2), 1.12–1.12.2 | Essential Loom + Stonecutter | `./gradlew collectLauncherJars` |
| `legacy-1.7.10/` | Forge 1.7.10 | RetroFuturaGradle | `./gradlew build` |

`common/` is shared by the modern builds as a source folder; the Java-8/MCP builds (`legacy*`, `forge-1.13.2`,
`forge-mojmap-legacy` ≤1.17.1) keep a copy of it that has to be kept in sync.

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
| Rüstung | Worn armor + held item with durability (number or percent, colored green→red) |
| Trank-Effekte | Active effects with level and remaining time, in the effect color |
| Koordinaten | Position, facing direction and biome |
| Uhrzeit / Speicher | Real-time clock (24 h/12 h, optional seconds) / JVM memory usage |
| Server-Adresse / Aktive Resourcepacks | Current server (hidden in singleplayer) / enabled packs |
| Toggle-Sprint / Toggle-Schleichen | Press once to keep sprinting/sneaking, HUD indicator while active (inactive if vanilla's own toggle option is on) |
| Fadenkreuz | Own crosshair (cross, cross+dot, dot, T, circle, circle+dot; color, size, gap, thickness, outline, attack cooldown) with an editor |
| Treffer-Farbe | Color/opacity of the hurt tint of entities (recolors the overlay texture) |
| Freelook | Hold Left Alt to orbit the camera without turning the player. Off by default – some servers forbid it |
| Startbildschirm | TRS title screen (pixel wordmark, Einzelspieler/Mehrspieler/Einstellungen/Mods*/TRS-Menü/Beenden, quick-join strip with the first 4 servers of servers.dat); link "Klassischer Titelbildschirm"; disable the module to always get the vanilla one |

*Mods only if ModMenu is installed. The TRS menu also has a **Resourcepacks** screen (search, filter all/enabled/available,
toggle, priority ▲/▼, open folder; applied with one reload).

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
| Left Alt (hold) | Freelook (module must be enabled) |

## Supported versions

1.14.4, 1.15.2, 1.16.2–1.16.5, 1.17, 1.17.1, 1.18–1.18.2, 1.19–1.19.4, 1.20.1–1.20.6, 1.21–1.21.11,
26.1, 26.1.1, 26.1.2, 26.2, 26.3 (checked against Mojang's version manifest and meta.fabricmc.net).

Not built: 1.14–1.14.3 (no official Mojang mappings), 1.15, 1.15.1 and 1.16 (their newest Fabric API on Modrinth,
which the launcher installs, has no `fabric-lifecycle-events-v1`) and 1.16.1 (rendering/text API predates 1.16.2).
On older versions some features degrade: 1.14 has no hit color, the pack search hint is a suggestion text before 1.19.3,
vanilla toggle sprint/sneak only exists from 1.15.

Each version gets its **own jar** whose `fabric.mod.json` depends on exactly that Minecraft version
(`trsclient-fabric-<minecraft>.jar`, requires the Fabric API modules it uses). Bytecode: Java 8 for 1.14–1.16,
16 for 1.17, 17 for 1.18–1.20.4, 21 for 1.20.5–1.21.11, 25 for 26.x. `common` is plain Java 8 for that reason.

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
