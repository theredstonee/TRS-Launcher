# TRS Client – Legacy Forge (Minecraft 1.8.9 – 1.12.2)

The TRS Client for the MCP/LaunchWrapper era of Forge. Same features, look and config file
(`config/trsclient.json`) as the Fabric build (see `../README.md`). License: GPL-3.0-only, author: theredstonee.

One source tree (`src/`), one jar per target, built with [Stonecutter](https://stonecutter.kikugie.dev/)
(0.9.8). `src/` is checked in for **1.8.9**.

| Jar | Compiled against | Accepted / listed Minecraft versions |
| --- | --- | --- |
| `trsclient-forge-1.8.9.jar` | Forge 1.8.9-11.15.1.2318, MCP stable_22 | 1.8.9 |
| `trsclient-forge-1.9.jar` | Forge 1.9-12.16.1.1887, MCP stable_24 | 1.9 |
| `trsclient-forge-1.9.4.jar` | Forge 1.9.4-12.17.0.2317, MCP stable_26 | 1.9.4 |
| `trsclient-forge-1.10.2.jar` | Forge 1.10.2-12.18.3.2511, MCP stable_29 | 1.10, 1.10.2 |
| `trsclient-forge-1.11.2.jar` | Forge 1.11.2-13.20.1.2588, MCP stable_32 | 1.11, 1.11.2 |
| `trsclient-forge-1.12.2.jar` | Forge 1.12.2-14.23.5.2847 (last FG2 userdev), MCP stable_39 | 1.12, 1.12.1, 1.12.2 |

All jars are Java 8, reobfuscated to SRG names (`remapJar`) and need nothing but Forge (`requires: []`).
Every listed version was tested in the real launcher. 1.8.8 is **not** supported (its Forge lacks
`EntityViewRenderEvent.FOVModifier`); 1.9 needs its own jar (`World#getBiome` returns `BiomeGenBase` there).

## Features and hooks (no Mixins, no coremod)

| Feature | Hook |
| --- | --- |
| HUD (FPS, CPS, keystrokes, ping, armor, effects, coordinates, clock, memory, server, packs, toggle indicators) + HUD editor | `RenderGameOverlayEvent.Post` (ALL) |
| Custom crosshair + editor | cancel `RenderGameOverlayEvent.Pre` (CROSSHAIRS), draw in Post; attack-cooldown bar from 1.9 |
| Zoom (hold V, wheel, slower mouse) | `FOVModifier` (hand excluded via `RenderHandEvent`), `MouseEvent`, own `MouseHelper` |
| Fullbright | gamma 16 from `RenderTickEvent` START until the first `FogColors`/`FOVModifier` (right after `updateLightmap`) |
| Toggle sprint/sneak | `KeyBinding.setKeyBindState` at the end of each client tick |
| Freelook (hold Left Alt, module off by default) | `EntityViewRenderEvent.CameraSetup` yaw/pitch + mouse deltas diverted in the `MouseHelper`; the camera-collision ray still uses the player's facing, so the camera can clip into blocks behind you |
| TRS title screen (Mods button = Forge mod list) | `GuiOpenEvent` replaces `GuiMainMenu` |
| Resource-pack screen | works on `ResourcePackRepository` like vanilla `GuiScreenResourcePacks` |
| Reach / combo | `AttackEntityEvent` (display only, the attack is untouched) |
| Speed / minimap / waypoints (beam, distance, death point) | no hook – client tick, world FOV from `FOVModifier` + own projection, chunk map colours |
| Chat (timestamps, "(x3)", Ctrl+click copies a line) | `ClientChatReceivedEvent` (LOWEST) is cancelled and the line printed with an own id; the click comes from `GuiScreenEvent.MouseInputEvent.Pre` on `GuiChat` |
| Text hotkeys / Auto-GG send | module key bindings (`core.input.KeyPresses`) → `EntityPlayerSP#sendChatMessage`, rate limited |
| No hurt camera | `hurtTime` set to 0 for one frame in `RenderTickEvent` START and restored in END |
| Block outline colour / width | `DrawBlockHighlightEvent` is cancelled and the box drawn again |
| Hitboxes | `RenderManager#setDebugBoundingBox`; the colour is hard-coded in vanilla and does nothing here |
| Hit color | **not available** – the hurt tint is hard-coded in `RendererLivingEntity`; hidden from the menu |
| Low fire | **not available** – needs the fire overlay renderer; hidden from the menu |

Version differences live in `compat/Mc.java` (MCP renames: `theWorld/thePlayer` → `world/player` in 1.10,
`fontRendererObj` → `fontRenderer` in 1.11, `mcDataDir` → `gameDir` in 1.12; Forge event fields → getters
in 1.9; equipment slots from 1.9; `ItemStack.EMPTY` from 1.11; `setRecordPlaying` → `setOverlayMessage`
in 1.10). Screens extend `screen/TrsScreen`, drawing goes through `ui/Gfx` (same API as the Fabric tree).
Zoom sits on V: the old default C collides with vanilla's "save hotbar" key from 1.12, so an untouched
C binding is moved to V once (`KeyDefaults`). The waypoint and text-hotkey keys are module settings
(bound in the TRS menu, read through `core.input.KeyPresses`), not vanilla key bindings.

## Build

Standalone Gradle build (own wrapper, not part of `../settings.gradle`): Gradle 9.5.1, Essential Loom
`gg.essential.loom` 1.15.50, `architectury-pack200` 0.1.3, JDK 8 toolchain (foojay).

```sh
./gradlew collectLauncherJars --parallel --build-cache   # all jars + unit tests → ../dist/*.jar + ../dist/builds-legacy.json
./gradlew :1.12.2:build                                  # one version
./gradlew :1.12.2:runClient -PtrsAutotest                # self-test (game dir ../run/forge-<mc>), screenshots, quits
./gradlew "Set active project to 1.12.2"                 # edit another version in the IDE (switch back to 1.8.9 before committing)
```

Adding a version: add it to `settings.gradle` and create `versions/<mc>/gradle.properties`
(`forge_version`, `mcp_mappings`, `accepted_versions`, `launcher_versions`, optional `launcher_forge`).
The self-test also runs in production: put `-Dtrsclient.autotest=true` into the launcher's JVM arguments.

## Shared code

`../common` is compiled straight into this build (`srcDir ../common/src/main/java`): it is plain Java 8 and only
uses Gson calls that the bundled Gson 2.2.4 has. The unit tests of `common` run in the `common` project
(`../gradlew :common:test`).
