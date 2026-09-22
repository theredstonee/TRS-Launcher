# TRS Client – NeoForge (1.20.2 – 26.3)

The NeoForge build of the TRS Client: **one source tree, one jar per Minecraft version** (Stonecutter), for every
Minecraft version NeoForge exists for – 1.20.2 up to 26.3 (22 versions). Same features, look and config file
(`config/trsclient.json`) as the Fabric build (see `../README.md`). License: GPL-3.0-only, author: theredstonee.

Client-only: on a dedicated server the mod loads but does nothing (`displayTest = "IGNORE_ALL_VERSION"`).
The "Config" button in NeoForge's mod list opens the TRS menu. The title screen's "Mods" button opens NeoForge's mod list.

## Versions

`versions/<mc>/gradle.properties` holds `neo_version` = exactly the NeoForge version the launcher installs
(newest stable, else newest beta – checked against maven.neoforged.net, 2026-09-22):

| Minecraft | NeoForge | Java |
| --- | --- | --- |
| 1.20.2 / 1.20.3 / 1.20.4 | 20.2.93 / 20.3.8-beta / 20.4.251 | 17 |
| 1.20.5 / 1.20.6 | 20.5.21-beta / 20.6.141 | 21 |
| 1.21 – 1.21.11 | 21.0.167, 21.1.251, 21.2.1-beta, 21.3.97, 21.4.157, 21.5.98, 21.6.20-beta, 21.7.25-beta, 21.8.54, 21.9.16-beta, 21.10.64, 21.11.45 | 21 |
| 26.1 / 26.1.1 / 26.1.2 / 26.2 / 26.3 | 26.1.0.19-beta / 26.1.1.15-beta / 26.1.2.109 / 26.2.0.88 / 26.3.0.8-beta | 25 |

Each jar's `mods.toml` requires exactly its Minecraft version and `neoforge >= <major.minor>` of that line
(e.g. `[20.2,)`), so older NeoForge builds of the same Minecraft version work too.

**No reobfuscation anywhere:** every NeoForge version (from 20.2 on) runs with Mojang names in production –
verified: the universal jars of 20.2.93/20.4.251/20.6.141 reference Mojmap members, no SRG names. The plain `jar` is the
release jar; mixins need no refmap.

## Hooks

| Feature | Hook |
| --- | --- |
| Entry point | `@Mod("trsclient")`, constructor `(IEventBus, ModContainer, Dist)` – `@Mod(dist=…)` only exists since 20.5 |
| Keys | `RegisterKeyMappingsEvent` (+ `registerCategory` for the `KeyMapping.Category` record from 1.21.9) |
| HUD | `RenderGuiEvent.Post` (all versions; `GuiGraphicsExtractor` from 26.1) |
| Own crosshair | cancels the vanilla crosshair layer: `RenderGuiOverlayEvent.Pre` + `VanillaGuiOverlay.CROSSHAIR` ≤1.20.4, `RenderGuiLayerEvent.Pre` + `VanillaGuiLayers.CROSSHAIR` from 1.20.5 |
| Ticks | `TickEvent.ClientTickEvent` (phase START/END) ≤1.20.4, `ClientTickEvent.Pre/Post` from 1.20.5 |
| Config button | `ConfigScreenHandler.ConfigScreenFactory` ≤1.20.4, `IConfigScreenFactory` from 1.20.5 |
| Save on exit | `GameShuttingDownEvent` |
| Zoom, CPS, zoom scroll, slow mouse, freelook, fullbright, hit color, title screen | the same vanilla mixins as the Fabric build (NeoForge ships Mixin + MixinExtras ≥0.3.1): `FovMixin`, `MouseHandlerMixin`, `CameraMixin`, `LightmapMixin`, `OverlayTextureAccessor`, `TitleScreenMixin` |

All other classes (screens, HUD, `ui/Gfx`, `compat/Mc`, features) are copies of the Fabric tree with the same
Stonecutter conditions; only `TrsClient`, `TrsKeys`, `AutoTest` (tick hook, version string) and `TrsTitleScreen`
(NeoForge mod list instead of ModMenu – moved to `client.gui.modlist` in 26.2, opened via reflection) differ.

## Build

Standalone Gradle build (not part of `../settings.gradle`), Gradle 9.5.1, ModDevGradle `net.neoforged.moddev` 2.0.147,
Stonecutter 0.9.8, toolchains 17/21/25 via foojay. `src/` is checked in for the **1.21.1** state.

```sh
./gradlew collectLauncherJars                 # builds all 22 versions, runs the common unit tests,
                                              # writes ../dist/trsclient-neoforge-<mc>.jar + ../dist/builds-neoforge.json
./gradlew :1.20.4:build                       # a single version
./gradlew :1.21.1:runClient -PtrsAutotest     # self-test (title screen, menu, test world, screenshots, quits)
./gradlew "Set active project to 26.3"        # edit another version in the IDE (switch back to 1.21.1 before committing)
```

Game dirs: `../run/neoforge-<mc>/`, self-test screenshots `../run/neoforge-<mc>/screenshots/trsclient-neoforge-<mc>-*.png`.

### NeoForge 20.2.93 / 20.3.8-beta / 20.5.21-beta

These three were published without Gradle module metadata, so ModDevGradle cannot resolve them by itself.
`build.gradle` contains `OldNeoForgeMetadataRule`, a component metadata rule that creates the variants ModDevGradle
expects (moddev-bundle/-config = userdev jar, module path and libraries from the userdev `config.json`,
Minecraft libraries from `net.neoforged:minecraft-dependencies:<mc>`), modelled on ModDevGradle's own
`LegacyForgeMetadataTransform`, plus `NonStrictOldMinecraftRule` (strict library versions → required).
With that, compiling and `runClient` work like for every other version.
