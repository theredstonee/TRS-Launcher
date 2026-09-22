# TRS Client – Forge 1.20 – 26.3

One Stonecutter source tree (`src/`) for every Minecraft version from 1.20 upward for which MinecraftForge exists.
Standalone Gradle build (own wrapper, Gradle 9.5.1) – not part of `client-mod/settings.gradle`.
Replaces the former single-version project `client-mod/forge-1.20.1`.

## Targets

Forge version = `recommended` promotion, else `latest` (files.minecraftforge.net `promotions_slim.json`, 2026-09-22) –
the same version the launcher installs. `mods.toml` accepts every Forge build of the same major (`[<major>,)`) and
exactly that Minecraft version.

| Minecraft | Forge | Java | Runtime names | Jar |
| --- | --- | --- | --- | --- |
| 1.20 | 46.0.14 | 17 | SRG | reobf (`-srg`) |
| 1.20.1 | 47.4.10 | 17 | SRG | reobf |
| 1.20.2 | 48.1.0 | 17 | SRG | reobf |
| 1.20.3 | 49.0.2 | 17 | SRG | reobf |
| 1.20.4 | 49.2.0 | 17 | SRG | reobf |
| 1.20.6 | 50.2.0 | 21 | Mojang | plain |
| 1.21 | 51.0.33 | 21 | Mojang | plain |
| 1.21.1 | 52.1.0 | 21 | Mojang | plain |
| 1.21.3 | 53.1.0 | 21 | Mojang | plain |
| 1.21.4 | 54.1.14 | 21 | Mojang | plain |
| 1.21.5 | 55.1.0 | 21 | Mojang | plain |
| 1.21.6 | 56.0.9 | 21 | Mojang | plain |
| 1.21.7 | 57.0.3 | 21 | Mojang | plain |
| 1.21.8 | 58.1.0 | 21 | Mojang | plain |
| 1.21.9 | 59.0.5 | 21 | Mojang | plain |
| 1.21.10 | 60.1.0 | 21 | Mojang | plain |
| 1.21.11 | 61.2.0 | 21 | Mojang | plain |
| 26.1 | 62.0.9 | 25 | unobfuscated | plain |
| 26.1.1 | 63.0.2 | 25 | unobfuscated | plain |
| 26.1.2 | 64.1.0 | 25 | unobfuscated | plain |
| 26.2 | 65.1.0 | 25 | unobfuscated | plain |
| 26.3 | 66.0.2 | 25 | unobfuscated | plain |

1.20.5 and 1.21.2 have no Forge release and are not covered.

Runtime names were read from each installer's `install_profile.json`: up to 1.20.4 the installer produces
`MC_SRG` (MCPConfig names), from 1.20.6 it renames Minecraft to Mojang's names (`MC_OFF`), from 26.1 Minecraft is
not obfuscated at all.

## Tooling

- **ForgeGradle 7.0.40** (`net.minecraftforge.gradle`, runs on Gradle 9) for all versions: it builds the Forge
  Minecraft artifact with Mojang names ("mavenizer", runs at *configuration* time, first time several minutes per
  version, then cached in `~/.gradle/caches/minecraftforge`). Therefore `org.gradle.configureondemand=true`.
- **Renamer 1.1.7** (`net.minecraftforge.renamer`) for 1.20 – 1.20.4: `renameJar` remaps the jar to SRG and runs the
  Mixin annotation processor (0.8.7) → `trsclient.refmap.json`. The `MixinConfigs` manifest attribute is set for all.
- ModDevGradle legacyforge only works for Forge 1.20/1.20.1 (NFRT fails on the 1.20.2+ userdev), ForgeGradle 6 does
  not run on Gradle 9 – hence FG7 everywhere.
- Stonecutter 0.9.8, source checked in for **1.21.1** (same as the Fabric tree, so files can be copied 1:1).

## Hooks (mostly Mixins, no MixinExtras)

Forge ships MixinExtras only from 1.21.10 and replaced its EventBus in 1.21.6 (EventBus 7), so the Forge API is
used as little as possible:

- `@Mod` constructor + `RegisterKeyMappingsEvent` (EventBus 6: `getModEventBus().addListener`,
  EventBus 7 / 1.21.6+: `RegisterKeyMappingsEvent.getBus(context.getModBusGroup())`)
- HUD: up to 1.20.4 `RenderGuiEvent.Post` (ForgeGui replaces `Gui#render`); from 1.20.6 `GuiMixin`
  (`Gui#render` → `Gui#extractRenderState` in 26.1 → `Hud#extractRenderState` in 26.2+, at every RETURN
  because Forge's ForgeLayeredDraw returns early)
- Client tick: `MinecraftMixin` (HEAD/TAIL of `Minecraft#tick`)
- Zoom `FovMixin` (@Inject RETURN), Fullbright `LightmapMixin` (@Redirect `Double.floatValue`), freelook
  `CameraMixin` + `MouseHandlerMixin` (@Redirect), crosshair `CrosshairMixin`, title screen `TitleScreenMixin`,
  hit color `OverlayTextureAccessor` – the same targets as the Fabric tree.

## Build

```sh
./gradlew collectLauncherJars          # all versions → ../dist/trsclient-forge-<mc>.jar + ../dist/builds-forge.json
./gradlew :1.21.1:build                # one version
./gradlew :1.21.1:runClient            # dev client, game dir client-mod/run/forge-<mc>
./gradlew :1.21.1:runClient -PtrsAutotest   # self-test (menu, world, screenshots, quits)
```

The self-test can also run in a production instance: environment variable `TRSCLIENT_AUTOTEST=1`.
Screenshots: `<game dir>/screenshots/trsclient-<mc>-*.png`.
