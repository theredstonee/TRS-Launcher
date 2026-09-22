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
| Zoom (hold C, wheel, slower mouse) | `FOVModifier` (hand excluded via `RenderHandEvent`), `MouseEvent`, own `MouseHelper` |
| Fullbright | gamma 16 from `RenderTickEvent` START until the first `FogColors`/`FOVModifier` (right after `updateLightmap`) |
| Toggle sprint/sneak | `KeyBinding.setKeyBindState` at the end of each client tick |
| Freelook (hold Left Alt, module off by default) | `EntityViewRenderEvent.CameraSetup` yaw/pitch + mouse deltas diverted in the `MouseHelper`; the camera-collision ray still uses the player's facing, so the camera can clip into blocks behind you |
| TRS title screen (Mods button = Forge mod list) | `GuiOpenEvent` replaces `GuiMainMenu` |
| Resource-pack screen | works on `ResourcePackRepository` like vanilla `GuiScreenResourcePacks` |
| Hit color | **not available** – the hurt tint is hard-coded in `RendererLivingEntity`; hidden from the menu |
| PvP HUD: reach, combo, speed | `AttackEntityEvent` (reach = eye → `objectMouseOver.hitVec`, combo confirmed through `hurtTime`), speed from the player position per client tick – all display only |
| Chat: timestamps, stacking "(xN)", Ctrl+click copy | `ClientChatReceivedEvent` (LOWEST): the event is cancelled and the line re-printed through `GuiNewChat.printChatMessageWithOptionalDeletion` with our own line id, so a repeat can replace the previous line via `deleteChatLine`. The original component is appended as a child, so colors, links and hover texts survive. Copy: `GuiScreenEvent.MouseInputEvent.Pre` over `GuiChat` + `GuiNewChat.getChatComponent` |
| Auto-GG, 4 text hotkeys (both off by default) | `EntityPlayerSP.sendChatMessage`, rate limited (`core/util/RateLimiter`): Auto-GG at most once a minute, hotkeys at most once a second / three per ten seconds |
| Waypoints (per world/server, light column, distance, death waypoint, list + edit screen) | own projection (`core/render/Projection`) drawn in the normal 2D HUD pass – no world rendering, no mixin. File: `config/trsclient-waypoints.json` |
| Minimap | `core/minimap` + `compat/MapSampler` (map color of the topmost block, only for chunks the client already has), drawn as horizontal colour runs |
| Hitboxes | `RenderManager.setDebugBoundingBox` (like F3+B) |
| Block outline (colour/width) | `DrawBlockHighlightEvent` cancelled, box drawn with `GL11.GL_LINES` (immediate mode looks identical in 1.8.9–1.12.2 and survives all three tessellator rewrites) |
| No hurt-camera tilt | `hurtTime` set to 0 for the duration of one frame (`RenderTickEvent` START → END) – in third person your own model also loses the red flash for that frame |
| 1.7 animations | only the "swing while using an item" half (`isSwingInProgress`/`swingProgressInt`). "Hand stays up" would need the private `ItemRenderer.equippedProgress` – not available |
| Motion blur, low fire | **not available** – both need the renderer itself (second frame buffer / fire overlay quads); hidden from the menu |

Version differences live in `compat/Mc.java` (MCP renames: `theWorld/thePlayer` → `world/player` in 1.10,
`fontRendererObj` → `fontRenderer` in 1.11, `mcDataDir` → `gameDir` in 1.12; Forge event fields → getters
in 1.9; equipment slots from 1.9; `ItemStack.EMPTY` from 1.11; `setRecordPlaying` → `setOverlayMessage`
in 1.10), plus `compat/ChatCompat.java` (`IChatComponent`/`ChatComponentText` → `ITextComponent`/`TextComponentString`
in `util.text` from 1.9, `ChatType` from 1.12), `compat/MapSampler.java` (map color: `Block#getMapColor(IBlockState)`
until 1.10.2, `IBlockState#getMapColor()` in 1.11, `IBlockState#getMapColor(IBlockAccess, BlockPos)` from 1.12;
`getChunkFromChunkCoords` → `getChunk` in 1.12) and `compat/BlockOutline.java` (`MovingObjectPosition` →
`RayTraceResult`, selection box from the `IBlockState` from 1.9).
Screens extend `screen/TrsScreen`, drawing goes through `ui/Gfx` (same API as the Fabric tree), text fields
through `ui/TextField` (own widget, LWJGL-2 key codes).
The C zoom key collides with vanilla's "save hotbar" key from 1.12 – rebind if needed.
New key bindings: waypoint at your position **B**, waypoint list **N**, four text hotkeys (unbound).

## Build

Standalone Gradle build (own wrapper, not part of `../settings.gradle`): Gradle 9.5.1, Essential Loom
`gg.essential.loom` 1.15.50, `architectury-pack200` 0.1.3, JDK 8 toolchain (foojay).

```sh
./gradlew collectLauncherJars --parallel --build-cache   # all jars + unit tests → ../dist/*.jar + ../dist/builds-legacy.json
./gradlew :1.12.2:build                                  # one version
./gradlew :1.12.2:runClient -PtrsAutotest                # self-test (game dir ../run/forge-<mc>), screenshots, quits
                                                         # (covers waypoints, minimap, chat timestamps/stacking,
                                                         #  waypoint list and text input; Ctrl+click copy,
                                                         #  Auto-GG and the text hotkeys need a real player)
./gradlew "Set active project to 1.12.2"                 # edit another version in the IDE (switch back to 1.8.9 before committing)
```

Adding a version: add it to `settings.gradle` and create `versions/<mc>/gradle.properties`
(`forge_version`, `mcp_mappings`, `accepted_versions`, `launcher_versions`, optional `launcher_forge`).
The self-test also runs in production: put `-Dtrsclient.autotest=true` into the launcher's JVM arguments.

## Shared code

`src/main/java/dev/theredstonee/trsclient/core/**` is a Java-8 **copy** of `../common` (no `sealed`,
records → classes, no switch expressions / `List.of`); tests are converted too and run against Gson 2.2.4.
Legacy-only: `core/input/MouseScaler`. When `common` changes, sync the copy.

Known rough edge from the shared defaults: the default HUD positions of "Geschwindigkeit" (`TOP_LEFT`, 0.345)
and "Reichweite" (`CENTER_LEFT`, −0.12) land on the same line at GUI scale 2 – move one of them in the HUD editor.
