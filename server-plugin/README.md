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
| `trs-server-rules-fabric-<mc>-<version>.jar` | Fabric dedicated server (+ Fabric API) | 1.20.1, 1.21.1, 1.21.4, 1.21.11 | 17+ / 21+ | dimension |
| `trs-server-rules-neoforge-<mc>-<version>.jar` | NeoForge dedicated server | 1.21.1, 1.21.11 | 21+ | dimension |
| `trs-server-rules-forge-<mc>-<version>.jar` | Forge dedicated server | 1.20.1 | 17+ | dimension |

The server mods (one jar per Minecraft version, `<mc>` = the exact Minecraft version) are **server-side only** – see
[Server mods](#server-mods-fabric--neoforge--forge).

## Install

1. Download the jar for your platform (GitHub release channel `server-plugin`, the TRS website, or the TRS Launcher).
2. Put it into the `plugins/` folder (Sponge and the server mods: `mods/`) and restart the server.
   The Fabric mod also needs [Fabric API](https://modrinth.com/mod/fabric-api) on the server.
3. Edit the generated config and run `/trsrules reload`.

| Platform | Config file |
| --- | --- |
| Spigot/Paper/Purpur/Folia | `plugins/TRSServerRules/config.yml` |
| BungeeCord/Waterfall | `plugins/TRSServerRules/config.yml` |
| Velocity | `plugins/trs-server-rules/config.yml` |
| Sponge | `config/trsserverrules/config.yml` |
| Fabric / NeoForge / Forge | `config/trs-server-rules.yml` |

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
bypass-players: []             # names or UUIDs that get no rules (every platform)
bypass-op-level: 0             # server mods: operators with at least this OP level (1–4) get no rules; 0 = off
```

Details:

- **Resolution:** global rules, then the matching `worlds:`/`servers:` section on top. Entries of the section win per
  module and per single setting; `free` (or `default`) removes a global lock; a section `message` replaces the
  global one (`""` = none).
- **World names:** Bukkit uses folder names (`world`, `world_nether`, …). Sponge and the server mods use world /
  dimension ids like `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end` (or a modded dimension id);
  a section named `the_nether` matches `minecraft:the_nether` too. Matching ignores case.
- **Settings:** a bare value or `{value: x}` forces a bool, number or choice (enum name, e.g. `SMOOTH`);
  `{min: a, max: b}` clamps a number. The nested form `zoom: {factor: {max: 4}}` works too.
- **Errors:** typos (unknown module ids, wrong values) are reported in the server log and skipped; unknown module ids
  are still sent (newer clients may know them). A YAML syntax error on `/trsrules reload` keeps the previous rules.
- **Bypass:** players with `bypass-permission`, players listed in `bypass-players` (name or UUID) and – on the server
  mods – operators with at least `bypass-op-level` receive an empty rule set and are exempt from `missing-client`.
  Plugin platforms ignore `bypass-op-level` (give `trsrules.bypass` instead). A change of OP status or permissions
  takes effect with the next rules message (`/trsrules reload`, world change or rejoin).

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

**Server mods:** vanilla servers have no permission system. `/trsrules` needs **OP level 3** (like `/ban`). On Fabric
with a permissions mod that provides `fabric-permissions-api` (e.g. LuckPerms), `trsrules.admin` and the
`bypass-permission` are honoured as well. On NeoForge/Forge use `bypass-players` / `bypass-op-level` for the bypass.

## Server mods (Fabric / NeoForge / Forge)

- **Server-side only.** Install them on the dedicated server; players do not need them. Vanilla clients and clients
  without the TRS Client (Fabric, Forge, NeoForge) can join as before: Fabric announces the channels via
  `minecraft:register`, NeoForge registers both payloads as *optional* (version `1`), Forge 1.20.1 uses event channels
  that accept vanilla and absent remotes and marks the mod as server-only (`IGNORE_SERVER_VERSION`).
- **Never active on a client** – also not in single player or LAN worlds (rules there would be pointless): Fabric only
  has a `server` entrypoint (`"environment": "server"`), NeoForge loads it with `dist = DEDICATED_SERVER`, Forge
  returns early outside a dedicated server. So it cannot clash with the payload types the TRS Client registers itself.
- **Rules per dimension** (`worlds:` keys are dimension ids, see above). Rules are re-sent after a dimension change and
  after a respawn in another dimension.
- **Channels:** only the namespaced `trsclient:hello` / `trsclient:rules` (the supported versions are all 1.13+). Rules
  are sent only to players that sent a TRS Client hello; Fabric and NeoForge additionally wait until the client has
  registered `trsclient:rules` (`minecraft:register`).
- **Missing-client timers** run on the server tick thread (vanilla servers have no scheduler API).

## Building

```sh
./gradlew build                # all jars incl. server mods → build/dist/
./gradlew build -PnoMods       # plugins only (no Minecraft download/decompile)
./gradlew fabricMods           # only the Fabric mods (also: neoforgeMods, forgeMods)
./gradlew :common:test         # unit tests of the shared core
```

Gradle downloads a JDK 21 automatically if needed (toolchains). Layout:

- `common/` – platform-independent core (Java 8): config model + YAML loading, resolution, protocol JSON, hello
  parsing, missing-client timeout, `/trsrules` command logic. A platform implements `RulesEngine.Bridge` for its
  player type and forwards join/quit/hello/world-change events.
- `bukkit/` – compiled against spigot-api 1.8.8 (Java 8 bytecode); the Folia scheduler bridge lives in its own source
  set compiled against paper-api and is only loaded on Folia.
- `bungee/`, `velocity/`, `sponge/` – one class each. Sponge is compiled against API 8 and compile-checked against
  API 12 and 17 during `check`.
- `modcore/` – `common` + relocated SnakeYAML as one jar for the server mods.
- `mods/` – the server mods as **separate Gradle builds** (Loom, ModDevGradle and ForgeGradle do not share one build
  well), run as nested builds by the root tasks `fabricMods` / `neoforgeMods` / `forgeMods` (part of `dist`). They
  compile against `modcore` and copy its classes into each mod jar.
  - `mods/shared/` – loader-independent mod code: `ModServerRules` (the `RulesEngine.Bridge` for `ServerPlayer`,
    `/trsrules` via Brigadier, event entry points), `TickScheduler`, and `Mc` – one small file per Minecraft API
    range (`mc-1.20.1`, `mc-1.21` for 1.21.1/1.21.4, `mc-1.21.11`: `Identifier`, permission sets).
  - `mods/fabric/` – Loom, one subproject per Minecraft version (`versions/<mc>/gradle.properties`);
    `src/net-legacy` (1.20.1: channel ids) and `src/net-payload` (1.20.5+: `PayloadTypeRegistry` + raw-bytes codec).
  - `mods/neoforge/` – ModDevGradle, one subproject per Minecraft version (`RegisterPayloadHandlersEvent`).
  - `mods/forge/` – ForgeGradle 7, Minecraft 1.20.1; the Renamer produces the SRG jar Forge 1.20.1 runs with.
  - Calling a mod build directly (`./gradlew -p mods/fabric dist`) needs `./gradlew :modcore:shadowJar` first.
