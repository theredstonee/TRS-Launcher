# TRS Client – Minecraft 1.8.9 (Forge)

The PvP-classic build of the TRS Client. Same features, look and config file as the modern Fabric build
(see `../README.md`): HUD (FPS, CPS, Keystrokes, Ping) with drag editor, Zoom (hold C), Fullbright,
Right-Shift menu, key bindings under **TRS Client**. Settings: `config/trsclient.json`.
License: GPL-3.0-only, author: theredstonee.

## Why Forge (and not Legacy Fabric)

- The TRS Launcher already installs Forge 1.8.9 – the mod works there without launcher changes.
- 1.8.9 PvP players already use Forge (Patcher, Skyblock mods, …); the jar can sit next to them.
- The mod is a plain `@Mod` without Mixins or coremods – everything goes through Forge events,
  so it does not clash with other mods that ship their own Mixin/tweaker.

| Feature | Hook |
| --- | --- |
| HUD | `RenderGameOverlayEvent.Post` (ALL) |
| Zoom | `EntityViewRenderEvent.FOVModifier` (hand excluded via `RenderHandEvent`), updated once per frame in `RenderTickEvent` |
| Zoom scroll / CPS | `MouseEvent` (wheel event is cancelled while zooming → hotbar stays) |
| Slower mouse while zooming | own `MouseHelper` subclass in `Minecraft.mouseHelper` (only if no other mod replaced it) |
| Fullbright | gamma is set to 16 at `RenderTickEvent` START and restored at the first `FogColors`/`FOVModifier` event, i.e. right after `updateLightmap`. Menus and `options.txt` never see the changed value; disabled inside Video Settings. |

## Build

Standalone Gradle build (not part of `../settings.gradle`, because Legacy Forge needs its own Loom and Java 8):

```sh
./gradlew build                     # builds, runs unit tests, copies the jar to ../dist/
./gradlew runClient                 # dev client (game dir: ../run)
./gradlew runClient -PtrsAutotest   # self-test: menu, test world, screenshots, quits
```

- Gradle 9.5.1 (runs on the installed JDK 21+), Essential Loom `gg.essential.loom` 1.15.50
  (fork of architectury-loom with Legacy-Forge support), `architectury-pack200` 0.1.3,
  Forge `1.8.9-11.15.1.2318-1.8.9`, MCP `stable_22`. Compiles and runs with a JDK 8 toolchain
  (downloaded automatically via foojay if missing).
- Output: `build/libs/trsclient-forge-1.8.9-<version>.jar` (SRG names, ready for production Forge),
  copied by `collectLauncherJars` (runs after `build`) to `../dist/trsclient-forge-1.8.9.jar`
  together with `../dist/builds-legacy.json`.
- Self-test screenshots: `../run/screenshots/trsclient-1.8.9-*.png`.

## Shared code

`src/main/java/dev/theredstonee/trsclient/core/**` is a **copy** of `../common` (modules, settings, config
store, HUD layout math, CPS counter, zoom state). `common` targets Java 21 (`sealed` classes), which
the Java 8 runtime of 1.8.9 cannot load. Only change in the copy: `Setting` is not `sealed`.
The copied unit tests are converted to Java 8 and run against Gson 2.2.4 (the version Minecraft 1.8.9 ships).
Legacy-only addition: `core/input/MouseScaler` (+ test). When `common` changes, sync the copy.

## Installing via the launcher

Forge 1.8.9 instance (any 11.15.1.x build) → put `trsclient-forge-1.8.9.jar` into `mods/`. No other
dependencies (`requires: []`).
