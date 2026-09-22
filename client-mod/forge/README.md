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
used as little as possible – every hook below is a plain Mixin (`@Inject`, `@Redirect`, `@ModifyVariable`,
`@ModifyArg`, `@Accessor`), which keeps one piece of code working across all 22 versions.

- `@Mod` constructor + `RegisterKeyMappingsEvent` (EventBus 6: `getModEventBus().addListener`,
  EventBus 7 / 1.21.6+: `RegisterKeyMappingsEvent.getBus(context.getModBusGroup())`)
- HUD: up to 1.20.4 `RenderGuiEvent.Post` (ForgeGui replaces `Gui#render`); from 1.20.6 `GuiMixin`
  (`Gui#render` → `Gui#extractRenderState` in 26.1 → `Hud#extractRenderState` in 26.2+, at every RETURN
  because Forge's ForgeLayeredDraw returns early)
- Client tick: `MinecraftMixin` (HEAD/TAIL of `Minecraft#tick`)
- Zoom `FovMixin` (@Inject RETURN), Fullbright `LightmapMixin` (@Redirect `Double.floatValue`), freelook
  `CameraMixin` + `MouseHandlerMixin` (@Redirect), crosshair `CrosshairMixin`, title screen `TitleScreenMixin`,
  hit color `OverlayTextureAccessor` – the same targets as the Fabric tree.
- PvP displays: `AttackMixin` (`MultiPlayerGameMode#attack` → reach/combo), no hurt camera `HurtCamMixin`
  (`GameRenderer#bobHurt`), 1.7 animations `OldAnimationsMixin` (@Redirect on
  `LocalPlayer#getAttackStrengthScale`, from 1.21.11 `#getItemSwapScale`; class `ItemInHandRenderer` →
  `FirstPersonHandsAndItems` in 26.3), low fire `LowFireMixin` (`ScreenEffectRenderer#renderFire` →
  `#submitFire` in 26.2+), block outline `BlockOutlineMixin`, hitbox colour `HitboxColorMixin`,
  line width `LineWidthMixin` (`RenderSystem#lineWidth`)
- Chat: `ChatMixin` (`ChatComponent#addMessage` + `#clearMessages`), `ChatComponentAccessor` (the message
  lists, needed for stacking and Ctrl+click), `ChatScreenMixin` (`ChatScreen#mouseClicked`).
  `ClientChatReceivedEvent` was deliberately *not* used: the mixin is one piece of code for EventBus 6 and 7
  and the accessor is needed for stacking anyway.

**`@ModifyArgs` must not be used on Forge.** Its generated helper class
`org.spongepowered.asm.synthetic.args.Args$1` cannot be found by Forge's module class loader – the game crashes
while `Minecraft` is being constructed (verified on 1.20.1). Use `@Redirect` or `@ModifyArg` instead.

## Per-version limits of the new features

| Feature | Available | Degrades to |
| --- | --- | --- |
| Hitbox toggle + colour | 1.20 – 1.21.8 | from 1.21.9 `EntityRenderDispatcher#renderHitbox`/`setRenderHitBoxes` are gone (hitboxes only via the vanilla debug entry); the module has no effect, the mixin is not registered |
| Outline/hitbox line width | 1.20 – 1.21.10 | `RenderSystem#lineWidth` no longer exists from **1.21.11**; width stays vanilla, the mixin is not registered |
| 1.7 animations, "swing while using an item" | 1.20 – 26.2 | 26.3 keeps the swing state privately (`swinging`/`swingingArm`/`swingTime` gone from `LivingEntity`); only the "no cooldown dip" half works there |
| Motion blur | – | not implemented (neither on Fabric); the module exists but does nothing |
| Everything else (reach/combo/speed, chat, waypoints, minimap, low fire, block outline colour, no hurt camera) | 1.20 – 26.3 | – |

## Verified in-game (2026-09-22)

- Dev self-test (all screenshots checked): 1.20.1, 1.20.4 (SRG), 1.21.1, 26.3.
- Dev self-test of the PvP/chat/waypoint/minimap features: 1.20.1 (SRG branches), 1.21.1, 26.3 – the
  screenshots `trsclient-<mc>-waypoints/-waypoint-liste/-chat.png` show the minimap with terrain and
  coordinates, the waypoint with beam and distance, the waypoint list and the chat with timestamp and
  "(x3)" stacking.
- Production through the launcher (`launch.exe … forge`, `TRSCLIENT_AUTOTEST=1`): 1.20.1 reobf jar (mod loads,
  refmapped mixins work: TRS title screen + menu; world part stopped by the demo account's DemoIntroScreen, which the
  self-test now closes), 1.21.1 plain jar (full self-test incl. HUD, zoom, fullbright, freelook).
- All other versions: compiled only – mixin targets are not checked at compile time (the 26.1.x targets
  `Gui#extractRenderState`/`Gui#extractCrosshair`/`Minecraft#setScreen` were checked in the Forge 26.1.2 sources).

## Build

```sh
./gradlew :collectLauncherJars         # all versions → ../dist/trsclient-forge-<mc>.jar + ../dist/builds-forge.json
                                       # (leading ':' – otherwise Gradle configures every version project)
./gradlew :collectLauncherJars -PtrsVersions=1.20.1,1.21.1   # only some versions
./gradlew :1.21.1:build                # one version
./gradlew :1.21.1:runClient            # dev client, game dir client-mod/run/forge-<mc>
./gradlew :1.21.1:runClient -PtrsAutotest   # self-test (menu, world, screenshots, quits)
```

The self-test can also run in a production instance: environment variable `TRSCLIENT_AUTOTEST=1`.
Screenshots: `<game dir>/screenshots/trsclient-<mc>-*.png`.
