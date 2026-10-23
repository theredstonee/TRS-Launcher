# TRS Server Rules

Control [TRS Client](https://trs-launcher.theredstonee.de) features on your Minecraft server: lock modules off, force
them on, force or clamp single settings – globally, per world, or (on proxies) per backend server.

Players without the TRS Client are not affected. Optionally you can notify or kick players whose client does not
identify as TRS Client.

> **TRS team bypass – please read.** Members of the TRS team who hold the team permission `serverrules.bypass`
> (granted by the TRS team in the TRS account system, *not* by your server) can override these rules **in their own
> client** after an in-game confirmation ("This server locks *Minimap* – use it anyway as TRS team?"). The server is
> not told about it and cannot switch it off. It only affects that one team member's own client, never other players.
> This is documented on purpose – in this README, in the plugin description and at the top of the default config.

License: GPL-3.0 (same as the TRS Launcher repository). Bundles SnakeYAML (Apache-2.0, relocated).

## Platforms

| Jar | Platform | Versions | Java | Rules per |
| --- | --- | --- | --- | --- |
| `trs-server-rules-bukkit-<version>.jar` | Spigot, Paper, Purpur, **Folia** | 1.8.8 – 26.x | 8+ | world |
| `trs-server-rules-bungee-<version>.jar` | BungeeCord, Waterfall | current | 8+ | backend server |
| `trs-server-rules-velocity-<version>.jar` | Velocity | 3.x and newer | 17+ | backend server |
| `trs-server-rules-sponge-<version>.jar` | SpongeVanilla, SpongeForge | API 8 – 17 | 17+ | world |

Server mods for Fabric / NeoForge / Forge dedicated servers will follow (they reuse the same core, config format and
command).

## Install

1. Download the jar for your platform (GitHub release channel `server-plugin`, the TRS website, or the TRS Launcher).
2. Put it into the `plugins/` folder (Sponge: `mods/`) and restart the server.
3. Edit the generated config and run `/trsrules reload`.

| Platform | Config file |
| --- | --- |
| Spigot/Paper/Purpur/Folia | `plugins/TRSServerRules/config.yml` |
| BungeeCord/Waterfall | `plugins/TRSServerRules/config.yml` |
| Velocity | `plugins/trs-server-rules/config.yml` |
| Sponge | `config/trsserverrules/config.yml` |

**Proxy networks:** install it on the proxy (rules per backend server). You may additionally install it on backend
servers (rules per world): the proxy merges backend rules in, but **proxy rules take precedence** for the same
module/setting. If you use `missing-client: kick`, configure it in one place (usually the proxy).

## How it works

The TRS Client registers the plugin channels and sends a `hello` after joining. The plugin answers with the rules
of the player's current world/server (always, also when nothing is locked), and sends them again after a world or
server change and after `/trsrules reload`. Every rules message replaces the previous one; when the player
disconnects, the client drops all rules.

Channels (payload: UTF-8 JSON, max. 32 KiB): server → client `trsclient:rules` (1.13+) / `TRS|Rules` (1.12 and older),
client → server `trsclient:hello` / `TRS|Hello`. Both names are registered wherever the platform allows it
(ViaVersion setups with old and new clients). On Bukkit, rules are only sent on a channel the client registered.

```json
{"v":1,"modules":{"minimap":{"enabled":false},"zoom":{"settings":{"factor":{"max":4.0}}}},"message":"PvP world"}
```

## Config reference

```yaml
config-version: 1

modules:                       # whole modules, for everybody
  minimap: false               # false = forced off
  toggleSprint: true           # true  = forced on
                               # not listed = the player decides

settings:                      # single settings: <module>.<setting>
  zoom.factor: {max: 4}        # clamp a number ({min: a}, {max: b} or both)
  fullbright.level: {max: 200}
  cinematic.freecamFairPlay: true   # a bare value forces it (true/false, number, choice enum name)
  minimap.mode: {value: SMOOTH}     # same, long form

worlds:                        # proxies: "servers:" with backend server names
  pvp_arena:
    modules: {zoom: false, freelook: false}
    settings: {fullbright.level: {max: 100}}
    message: "PvP area: zoom and freelook are disabled."
  creative:
    modules: {minimap: free}   # "free" lifts a global lock in this world

message: ""                    # optional plain text the TRS Client shows once

missing-client:                # players without a TRS Client hello
  action: ignore               # ignore | notify | kick
  timeout-seconds: 10          # 1 – 600
  notify-message: "&cThis server is made for the &fTRS Client&c."   # & color codes, \n = new line
  kick-message: "&cThis server requires the &fTRS Client&c."

bypass-permission: trsrules.bypass   # players with it get no rules; "" disables
```

Details:

- **Resolution:** global rules, then the matching `worlds:`/`servers:` section on top. Entries of the section win per
  module and per single setting; `free` (or `default`) removes a global lock; a section `message` replaces the
  global one (`""` = none).
- **World names:** Bukkit uses folder names (`world`, `world_nether`, …). Sponge (and the later server mods) use world
  keys like `minecraft:the_nether`; a section named `the_nether` matches those too. Matching ignores case.
- **Settings:** a bare value or `{value: x}` forces a bool, number or choice (enum name, e.g. `SMOOTH`);
  `{min: a, max: b}` clamps a number. The nested form `zoom: {factor: {max: 4}}` works too.
- **Errors:** typos (unknown module ids, wrong values) are reported in the server log and skipped; unknown module ids
  are still sent (newer clients may know them). A YAML syntax error on `/trsrules reload` keeps the previous rules.
- **Bypass:** players with `bypass-permission` receive an empty rule set and are exempt from `missing-client`.

The generated default config lists **all module ids by category** (all commented out = nothing locked):

| Category | Module ids |
| --- | --- |
| HUD | `fps` `ping` `effects` `clock` `memory` `packs` `scoreboard` `tabPing` `bossBar` `titles` `warnings` `hungerOverlay` `pinnedNote` |
| PVP | `cps` `keystrokes` `armor` `toggleSprint` `toggleSneak` `reach` `combo` `speed` `crosshair` `hitColor` `oldAnimations` `lowFire` `shieldPosition` `hitboxes` `noHurtCam` `itemCounter` `hitFeedback` |
| WORLD | `coords` `server` `minimap` `worldMap` `freelook` `zoom` `fullbright` `autorefill` `blockOutline` `waypoints` `colors` `panorama` `notes` |
| CHAT | `chat` `autoGg` `textHotkeys` `social` `chatMentions` `chatHeads` `chatFilter` |
| REDSTONE | `redstoneSignal` `redstoneOverlay` `redstoneClock` `circuitLibrary` |
| PERFORMANCE | `fpsBoost` `dynamicFps` `borderlessFullscreen` `entityCulling` `particles` `worldDetails` `builtinOptimizations` `netOptimize` `lowLatency` `fastConnect` |
| MISC | `titleScreen` `menuStyle` `trsOnline` `capePhysics` `emotes` `clips` `cinematic` `halloween` `tooltips` `serverProfiles` `screenshots` `keySearch` `autoReconnect` `queueAlerts` `streamerMode` |

## Commands and permissions

| Command | Permission | Description |
| --- | --- | --- |
| `/trsrules reload` | `trsrules.admin` | Reload the config and re-send the rules to all TRS Client players |
| `/trsrules status` | `trsrules.admin` | Players tracked / with TRS Client, global rules |
| `/trsrules status <player>` | `trsrules.admin` | TRS Client version, bypass, world/server, effective rules, delivery |

| Permission | Default | Meaning |
| --- | --- | --- |
| `trsrules.admin` | op | use `/trsrules` |
| `trsrules.bypass` | nobody | no rules, exempt from `missing-client` (name configurable via `bypass-permission`) |

## Building

```sh
./gradlew build            # all jars → build/dist/
./gradlew :common:test     # unit tests of the shared core
```

Gradle downloads a JDK 21 automatically if needed (toolchains). Layout:

- `common/` – platform-independent core (Java 8): config model + YAML loading, resolution, protocol JSON, hello
  parsing, missing-client timeout, `/trsrules` command logic. A platform implements `RulesEngine.Bridge` for its
  player type and forwards join/quit/hello/world-change events – that is all the future server mods need.
- `bukkit/` – compiled against spigot-api 1.8.8 (Java 8 bytecode); the Folia scheduler bridge lives in its own source
  set compiled against paper-api and is only loaded on Folia.
- `bungee/`, `velocity/`, `sponge/` – one class each. Sponge is compiled against API 8 and compile-checked against
  API 12 and 17 during `check`.
