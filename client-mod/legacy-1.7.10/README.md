# TRS Client – Minecraft 1.7.10 (Forge)

The 1.7.10 build of the TRS Client. Same look, menu and config file as the other builds (see `../README.md`).
License: GPL-3.0-only, author: theredstonee.

## Features

| Module | 1.7.10 | Hook |
| --- | --- | --- |
| HUD: FPS, CPS, Keystrokes, Ping, Armor, Effects, Coordinates, Clock, Memory, Server, Packs, Toggle indicator | yes | `RenderGameOverlayEvent.Post` (ALL) |
| HUD editor (drag, snap, wheel = size, right click = reset, Shift = free) | yes | own `GuiScreen` |
| TRS menu (Right Shift), module toggles + settings, scrollable card list | yes | `ClientTickEvent` + own `GuiScreen` |
| Zoom (hold C, smooth, mouse wheel, slower mouse) | yes | FOV option swapped for one frame (see below), `MouseEvent` (wheel, cancelled while zooming), own `MouseHelper` |
| Fullbright | yes | gamma swapped from `RenderTickEvent` START until the first `FogColors` event (= right after `updateLightmap`) |
| Toggle sprint / sneak | yes | presses counted at `ClientTickEvent` START, key held in `LivingUpdateEvent` of the own player (right before `onLivingUpdate` reads it) |
| Custom crosshair + editor | yes (no attack-cooldown bar – 1.7.10 has no attack cooldown) | `RenderGameOverlayEvent.Pre` (CROSSHAIRS) cancelled |
| Hit color, Freelook, TRS title screen | hidden | would need coremods/ASM (no overlay texture, no camera events) |

- 1.7.10 has **two event buses**: tick events (`TickEvent.*`) only arrive on `FMLCommonHandler.instance().bus()`,
  render/input events on `MinecraftForge.EVENT_BUS`.
- There is no `EntityViewRenderEvent.FOVModifier` in Forge 10.13 (added in 1.8). Zoom therefore divides
  `gameSettings.fovSetting` from `RenderTickEvent` START to END – only while no screen is open, so the options
  screen and `options.txt` never see the zoomed value. The hand keeps its 70° (vanilla passes `useFOVSetting=false`).
- `Minecraft.getDebugFPS()` does not exist (field is private) – FPS is parsed from `mc.debug` ("123 fps, …").
- Ping: `NetHandlerPlayClient.playerInfoList` (`GuiPlayerInfo` by player name).
- Coordinates use `boundingBox.minY` (the client player's `posY` is at eye height in 1.7.10).

## Build

Standalone Gradle build (not part of `../settings.gradle`):

```sh
./gradlew build                     # compiles, runs unit tests, reobfuscates, copies the jar to ../dist/
./gradlew runClient                 # dev client (game dir: ../run/forge-1.7.10)
./gradlew runClient -PtrsAutotest   # self-test: menu, test world, screenshots, quits
```

- Gradle 9.5.1 (needs **JDK 25** to run Gradle – RetroFuturaGradle 2.x requirement), GTNH
  **RetroFuturaGradle 2.0.4** (`com.gtnewhorizons.retrofuturagradle`, maven `https://nexus.gtnewhorizons.com/repository/public/`),
  Forge **1.7.10-10.13.4.1614** (promotions: recommended = latest), MCP **stable_12**. Compiles/runs with a JDK 8
  toolchain (foojay downloads it if missing).
- Output: `build/libs/trsclient-forge-1.7.10-<version>.jar` (reobfuscated to SRG by `reobfJar`, the `-dev` jar has MCP names),
  copied by `collectLauncherJars` (runs after `build`) to `../dist/trsclient-forge-1.7.10.jar` together with
  `../dist/builds-legacy-1.7.10.json`.
- Mod version constant: RFG `injectTags` generates `dev.theredstonee.trsclient.Tags.VERSION` (used in `@Mod(version=…)`).
- Self-test screenshots: `../run/forge-1.7.10/screenshots/trsclient-1.7.10-*.png`.
- Production self-test through the launcher: set `JAVA_TOOL_OPTIONS=-Dtrsclient.autotest=true` for
  `src-tauri/target/debug/examples/launch.exe <data-dir> 1.7.10 forge <seconds>`.

## Shared code

`../common` is compiled straight into this build (`srcDir ../common/src/main/java`): it is plain Java 8 and only
uses Gson calls that the bundled Gson 2.2.4 has. Pack and title screens are not built for 1.7.10; the modules that
do not exist here are listed in `TrsClient.UNSUPPORTED` and the menu hides them. The unit tests of `common` run
in the `common` project (`../gradlew :common:test`).
