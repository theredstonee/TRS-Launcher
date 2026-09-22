# TRS Client – Forge 1.14.4 bis 1.19.4

The TRS Client (see `../README.md`) for every Forge release of the "PoseStack era" – Minecraft versions that use
Mojang's official names in development and SRG names at runtime. Same look, menu, HUD modules and config file
(`config/trsclient.json`) as the Fabric build. License: GPL-3.0-only, author: theredstonee.

## Versions

One jar per Minecraft version (`trsclient-forge-<minecraft>.jar`, remapped to SRG + mixin refmap):

| Minecraft | Forge (compiled against) | Java | Notes |
| --- | --- | --- | --- |
| 1.14.4 | 28.2.26 (recommended) | 8 | no Mixin in Forge → event fallbacks, no freelook / hit color / slower mouse |
| 1.15.2 | 31.2.57 (recommended) | 8 | needs Forge ≥ 31.2.44 (first build with Mixin) |
| 1.16.2 | 33.0.61 (latest) | 8 | |
| 1.16.4 | 35.1.4 (recommended) | 8 | |
| 1.16.5 | 36.2.34 (recommended) | 8 | |
| 1.17.1 | 37.1.1 (recommended) | 16 | |
| 1.18 / 1.18.1 / 1.18.2 | 38.0.17 / 39.1.0 / 40.3.0 | 17 | |
| 1.19 / 1.19.1 / 1.19.2 | 41.1.0 / 42.0.9 / 43.5.0 | 17 | |
| 1.19.3 / 1.19.4 | 44.1.0 / 45.4.0 | 17 | |

Forge versions = `promotions_slim.json` "recommended", else "latest" (what the TRS Launcher installs), as of 2026-09-22.
`mods.toml` accepts every Forge build of the same Minecraft version (`[<major>,)`).

Not built: **1.16.1** (Loom cannot remap Minecraft 1.16.1 to Mojang names – conflicting method names),
**1.16.3** (Forge 34.1.0 pins Mixin 0.8.1, for which Loom has no patched Mixin build), 1.15/1.15.1
(only early "latest" Forge builds without Mixin), 1.14.2/1.14.3 (no Mojang mappings before 1.14.4).

## Features and hooks

| Feature | Hook |
| --- | --- |
| HUD | `RenderGameOverlayEvent.Post(ALL)` ≤ 1.18.2, `RenderGuiEvent.Post` ≥ 1.19 |
| Own crosshair | cancels `RenderGameOverlayEvent.Pre(CROSSHAIRS)` ≤ 1.16.5, `PreLayer(CROSSHAIR_ELEMENT)` 1.17–1.18.2, `RenderGuiOverlayEvent.Pre(CROSSHAIR)` ≥ 1.19 |
| TRS title screen | `GuiOpenEvent` ≤ 1.17.1, `ScreenOpenEvent` 1.18.x, `ScreenEvent.Opening` ≥ 1.19 |
| Keys | `ClientRegistry` (package moved 1.17 / 1.18) in `FMLClientSetupEvent`, `RegisterKeyMappingsEvent` ≥ 1.19 |
| Zoom | Mixin `GameRenderer#getFov` RETURN (only `useFovSetting`, so the hand is not zoomed) |
| CPS / zoom wheel / slower mouse / freelook turn | Mixin `MouseHandler#onPress`, `#onScroll`, `#turnPlayer` (+ `@Redirect LocalPlayer.turn`) |
| Freelook camera | Mixin `@Redirect Entity.getViewYRot/XRot` in `Camera#setup` |
| Fullbright | Mixin `@Redirect` of the gamma read in `LightTexture#updateLightTexture` (field `Options.gamma` ≤ 1.18.2, `Double.floatValue()` after `gamma()` ≥ 1.19) |
| Hit color | Accessor on `OverlayTexture.texture` (≥ 1.15) |
| 1.14.4 (no Mixin) | Zoom = `FOVModifier` (skipped after `RenderWorldLastEvent` = hand), CPS/wheel = `InputEvent`, Fullbright = gamma only between `RenderTickEvent` START and the first FOV event |

Forge ships no MixinExtras in these versions → only `@Inject`/`@Redirect`/`@Accessor`.

## Build

Standalone Gradle build (Gradle 9.5.1, needs a JDK 21+ to run Gradle; JDK 8/17 toolchains are downloaded via foojay).
Essential Loom `gg.essential.loom` 1.15.50 (architectury-loom fork) with `loom.platform=forge` and Mojang mappings,
Stonecutter 0.9.8. `configureondemand` is on – running a task of one version only configures that version.

```sh
./gradlew collectLauncherJars                    # all versions → ../dist/trsclient-forge-<mc>.jar + ../dist/builds-forge-mojmap-legacy.json
./gradlew :1.16.5:build                          # one version (remapped jar: versions/1.16.5/build/libs/)
./gradlew :1.16.5:runClient                      # dev client, game dir ../run/forge-1.16.5
./gradlew :1.16.5:runClient -PtrsAutotest        # self-test: title, menu, new test world, screenshots, quits
```

Self-test screenshots: `../run/forge-<mc>/screenshots/trsclient-<mc>-*.png`.

## Layout

```
src/                 ONE source tree for all versions (Stonecutter comments), checked in for 1.16.5
  ui/Gfx             drawing: no matrix ≤1.15.2 (GL matrix), PoseStack ≥1.16; items per era; scissor
  compat/Mc          everything else that moved (options → OptionInstance 1.19, camera type, player rotation, biomes, packs …)
  screen/TrsScreen   render(int…) ≤1.15.2 vs render(PoseStack…)
versions/<mc>/       gradle.properties (forge_version) + build output
```

`common` is plain Java 8 and is compiled into every version (`srcDir ../common/src/main/java`) – also into
1.14.4–1.16.5 (Java 8) and 1.17.1 (Java 16). The common unit tests run with the 1.19.4 node.

Switch the active version in `src/` with `./gradlew "Set active project to 1.19.4"` (switch back to 1.16.5 before committing).
Adding a version: add it to `settings.gradle` and create `versions/<mc>/gradle.properties` with `forge_version=...`.
