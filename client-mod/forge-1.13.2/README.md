# TRS Client – Minecraft 1.13.2 (Forge)

The 1.13.2 build of the TRS Client (1.13.2 has no Fabric API build, so vanilla instances get this Forge build).
Same look, menu and config file as the other builds (see `../README.md`). License: GPL-3.0-only, author: theredstonee.

## Features

| Module | 1.13.2 | Hook |
| --- | --- | --- |
| HUD: FPS, CPS, Keystrokes, Ping, Armor, Effects, Coordinates, Clock, Memory, Server, Packs, Toggle indicator | yes | `RenderGameOverlayEvent.Post` (ALL) |
| HUD editor (drag, snap, wheel = size, right click = reset, Shift = free) | yes | own `GuiScreen` |
| TRS menu (Right Shift), module toggles + settings, scrollable card list | yes | `ClientTickEvent` + own `GuiScreen` |
| Zoom (hold C, smooth) | yes | `EntityViewRenderEvent.FOVModifier`; the hand is excluded: `GameRenderer.renderHand` asks for the FOV right after `RenderWorldLastEvent` |
| Zoom mouse wheel | yes | Forge 1.13.2 has **no in-game scroll event** – vanilla moves the hotbar slot immediately; while zooming the slot change is detected at `ClientTickEvent`/`RenderTickEvent` START (before the slot is sent to the server), undone and used as zoom step |
| Slower mouse while zooming | yes | no mouse/turn event and `MouseHelper` registers its GLFW callbacks itself → the rotation applied by `MouseHelper.updatePlayerLook` (runs between the client tick and the render tick) is divided by the zoom factor at `RenderTickEvent` START |
| Fullbright | yes | gamma swapped from `RenderTickEvent` START until the first `FogColors` event (`LightTexture.updateLightmap` runs right before) |
| Toggle sprint / sneak | yes | presses counted at `ClientTickEvent` START, key held in `LivingUpdateEvent` of the own player |
| Custom crosshair + editor (incl. attack-cooldown bar) | yes | `RenderGameOverlayEvent.Pre` (CROSSHAIRS) cancelled |
| Hit color, Freelook, TRS title screen | hidden | would need coremods/mixins (hurt tint hard-coded in the entity renderer, no free-camera hook) |

## Build

Standalone Gradle build (not part of `../settings.gradle`):

```sh
./gradlew build                     # compiles, runs unit tests, reobfuscates (SRG), copies the jar to ../dist/
./gradlew runClient                 # dev client (game dir: ../run/forge-1.13.2)
./gradlew runClient -PtrsAutotest   # self-test: menu, test world, screenshots, quits
```

- **ForgeGradle 6.0.54** (`net.minecraftforge.gradle`) on its own **Gradle 8.14.3** wrapper (FG 6 does not run on
  Gradle 9; ModDevGradle/legacyforge only supports 1.17+). The Forge 1.13.2 userdev was republished in 2021 with
  config spec 2, which FG 6 handles. The Gradle daemon runs on **JDK 17** (`gradle/gradle-daemon-jvm.properties`,
  Gradle 8.14 cannot run on JDK 25); compiling/running uses a JDK 8 toolchain (foojay).
- Forge **1.13.2-25.0.223** (promotions: no "recommended", latest = 25.0.223), MCP **stable_47-1.13.2**
  (no Mojang mappings for 1.13.2). `mods.toml` accepts Forge `[25.0.0,)` and Minecraft `[1.13.2]`.
- Output: `build/libs/trsclient-forge-1.13.2-<version>.jar`, rewritten to SRG names by `reobfJar` (Forge 1.13.2 runs
  SRG in production), copied by `collectLauncherJars` (runs after `build`) to `../dist/trsclient-forge-1.13.2.jar`
  together with `../dist/builds-forge-1.13.2.json`.
- Entry point: `TrsClientMod` (`@Mod`) → `DistExecutor` → `TrsClient.bootstrap()` only on the client.
- Self-test screenshots: `../run/forge-1.13.2/screenshots/trsclient-1.13.2-*.png`.
- Production self-test through the launcher: set `JAVA_TOOL_OPTIONS=-Dtrsclient.autotest=true` for
  `src-tauri/target/debug/examples/launch.exe <data-dir> 1.13.2 forge <seconds>`.

## Shared code

`src/main/java/dev/theredstonee/trsclient/core/**` is the same **Java 8 copy** of `../common` as in `../legacy-1.7.10`
(Minecraft 1.13.2 runs on Java 8). Tests run against Gson 2.8.0 (the version 1.13.2 ships). When `common` changes,
sync the copy.
