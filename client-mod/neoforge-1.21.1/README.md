# TRS Client – Minecraft 1.21.1 (NeoForge)

The NeoForge build of the TRS Client for modpacks (Create, ATM 10, …). Same features, look and config file as the
Fabric build (see `../README.md`): HUD (FPS, CPS, Keystrokes, Ping) with drag editor, Zoom (hold C), Fullbright,
Right-Shift menu, key bindings under **TRS Client**, "Config" button in the mod list opens the TRS menu.
Settings: `config/trsclient.json`. License: GPL-3.0-only, author: theredstonee.

Client-only (`@Mod(dist = CLIENT)`, `displayTest = "IGNORE_ALL_VERSION"`): servers do not need it.

| Feature | Hook |
| --- | --- |
| HUD | GUI layer via `RegisterGuiLayersEvent#registerAboveAll` |
| Zoom | `ViewportEvent.ComputeFov` (only when `usedConfiguredFov()`, so the hand is not zoomed) |
| Zoom scroll | `InputEvent.MouseScrollingEvent` (cancelled while zooming → hotbar stays) |
| CPS | `InputEvent.MouseButton.Pre` |
| Slower mouse while zooming | `CalculatePlayerTurnEvent` – sensitivity chosen so the turn is exactly divided by the zoom factor (`MouseSensitivity`, unit-tested) |
| Fullbright | the only Mixin: `LightTexture#updateLightTexture`, `@ModifyExpressionValue` on the gamma read (MixinExtras ships with NeoForge). The vanilla option is never changed. |
| Keys / tick / save | `RegisterKeyMappingsEvent`, `ClientTickEvent.Post`, `GameShuttingDownEvent` |

## Build

Standalone Gradle build (not part of `../settings.gradle`):

```sh
./gradlew build                     # builds, runs unit tests, copies the jar to ../dist/
./gradlew runClient                 # dev client (game dir: ../run/neoforge-1.21.1)
./gradlew runClient -PtrsAutotest   # self-test: menu, test world, screenshots, quits
```

- Gradle 9.5.1, ModDevGradle `net.neoforged.moddev` 2.0.147, NeoForge 21.1.251, Java 21 toolchain (foojay).
- Output: `build/libs/trsclient-neoforge-1.21.1-<version>.jar` (Mojang names = production names on NeoForge),
  copied by `collectLauncherJars` (runs after `build`) to `../dist/trsclient-neoforge-1.21.1.jar`
  together with `../dist/builds-neoforge.json`.
- Self-test screenshots: `../run/neoforge-1.21.1/screenshots/trsclient-neoforge-1.21.1-*.png`.

## Shared code

`../common/src/main/java` (modules, settings, config store, HUD layout math, CPS counter, zoom state) is added as a
**source directory** – no copy. Its unit tests (`../common/src/test/java`) run in this build too.
`hud/`, `screen/`, `ui/` are copies of the Fabric 1.21.1 classes (same Minecraft code); only `HudManager` adds a hook counter.

## Installing via the launcher

NeoForge 1.21.1 instance → put `trsclient-neoforge-1.21.1.jar` into `mods/`. No other dependencies (`requires: []`).
Declared ranges: `minecraft [1.21.1]`, `neoforge [21.1.0,)`.
