# TRS Client

The in-game client mod of the TRS Launcher (Lunar/Badlion style). Client-only, one jar per loader and
Minecraft release: **Fabric 1.14.4–26.3**, **Forge 1.7.10–26.3**, **NeoForge 1.20.2–26.3** (see below).
License: GPL-3.0-only, author: theredstonee.

## Projects

Each loader family is its own Gradle build (own wrapper, own README with hooks and pitfalls); all of them put
their jars and a `builds-<project>.json` into `dist/`. `scripts/publish-client-mod.mjs` merges them into the
signed update channel and into `src-tauri/resources/client-mod/builds.json` (see "Publishing" below).

| Directory | Loader / versions | Tooling | Rebuild |
| --- | --- | --- | --- |
| `fabric/` (this build) | Fabric 1.14.4–26.3 (39) | Loom + Stonecutter | `./gradlew collectLauncherJars` |
| `neoforge/` | NeoForge 1.20.2–26.3 (22, incl. beta-only versions) | ModDevGradle + Stonecutter | `./gradlew collectLauncherJars` |
| `forge/` | Forge 1.20–26.3 (22) | ForgeGradle 7 + Stonecutter | `./gradlew :collectLauncherJars` |
| `forge-mojmap-legacy/` | Forge 1.14.4, 1.15.2, 1.16.2, 1.16.4, 1.16.5, 1.17.1, 1.18–1.18.2, 1.19–1.19.4 | Essential Loom + Stonecutter | `./gradlew collectLauncherJars` |
| `forge-1.13.2/` | Forge 1.13.2 | ForgeGradle 6 (Gradle 8.14) | `./gradlew build` |
| `legacy/` | Forge 1.8.9, 1.9, 1.9.4, 1.10(.2), 1.11(.2), 1.12–1.12.2 | Essential Loom + Stonecutter | `./gradlew collectLauncherJars` |
| `legacy-1.7.10/` | Forge 1.7.10 | RetroFuturaGradle | `./gradlew build` |

`common/` is shared by the modern builds as a source folder; the Java-8/MCP builds (`legacy*`, `forge-1.13.2`,
`forge-mojmap-legacy` ≤1.17.1) keep a copy of it that has to be kept in sync.

## Features

All features can be toggled in the TRS menu. Settings are stored in `config/trsclient.json`.

| Module | What it does |
| --- | --- |
| FPS | Frames per second |
| CPS | Left/right clicks within the last 1000 ms |
| Keystrokes (Tastenanzeige) | W A S D, left/right mouse button (optional CPS), space bar – lit while pressed |
| Ping | Latency to the current server from the player list (hidden in singleplayer) |
| Zoom | Hold the key (V; changeable under *Taste* in the TRS menu or in the controls) → FOV divided by the strength (×2–×10, default ×4), smooth zoom in/out (switchable), mouse wheel changes the zoom while held, mouse sensitivity drops proportionally to the zoom, optional *Filmische Kamera* only while zooming. The spyglass (1.17+) wins: the TRS zoom ends at once and the wheel stays with the game |
| Fullbright | Maximum brightness; only overrides the gamma used for the lightmap, the vanilla brightness option is never changed |
| Rüstung | Worn armor + held item with durability (number or percent, colored green→red) |
| Trank-Effekte | Active effects with level and remaining time, in the effect color |
| Koordinaten | Position, facing direction and biome |
| Uhrzeit / Speicher | Real-time clock (24 h/12 h, optional seconds) / JVM memory usage |
| Server-Adresse / Aktive Resourcepacks | Current server (hidden in singleplayer) / enabled packs |
| Toggle-Sprint / Toggle-Schleichen | Press once to keep sprinting/sneaking (each module on/off). HUD element (movable in the HUD editor) shows `[Sprinting (Toggled)]`, `[Sprinting (Key held)]`, `[Flying (Boost)]`, `[Sneaking (Toggled)]` … Options: *Sprinten nur vorwärts*, *Flug-Boost* in creative flight (×1.5–×5, the previous speed is restored, a speed set by the server is adopted), *Zustand merken* (otherwise death, respawn, dimension and world change reset it; default: sprint remembered, sneak not). Menus never leave a key stuck – Minecraft releases the keys, the toggle resumes when the menu closes. Inactive if vanilla's own toggle option is on |
| Fadenkreuz | Own crosshair (cross, cross+dot, dot, T, circle, circle+dot; color, size, gap, thickness, outline, attack cooldown) with an editor |
| Treffer-Farbe | Color/opacity of the hurt tint of entities (recolors the overlay texture) |
| Freelook | Hold (or, with *Umschalten statt Halten*, toggle) Left Alt to orbit the camera in third person – *Perspektive* behind or in front – while the character keeps walking and looking ahead; releasing restores the previous view. The player is never turned, so the server receives exactly the rotation it would get without freelook. Off by default – some servers forbid it; *Aus auf diesen Servern* (host incl. subdomains, `*.example.net` = only subdomains, empty by default) switches it off there with a hint in the action bar |
| Reichweite / Combo / Geschwindigkeit | Distance of the last hit (display only – the reach itself is untouched), hits in a row (ends on own damage or a pause) and blocks per second |
| 1.7-Animationen | Hand stays up during the attack cooldown, swing animation while using an item (visual only, no packet) |
| Niedriges Feuer / Kein Schadens-Wackeln | Fire overlay pulled down / no camera tilt when taking damage |
| Block-Umrandung / Hitboxen | Colour + opacity + line width of the block outline / F3+B hitboxes with colour |
| Chat-Verbesserungen | Timestamps, repeated messages collapsed to "(x3)", Ctrl+click copies a line |
| Auto-GG | Sends a configurable text after typical end-of-game messages. Off by default, at most once per minute, only triggers on messages that are not player chat |
| Text-Hotkeys | Four bindable keys send a fixed text or command. Off by default, at most one message per second (3 per 10 s) |
| Wegpunkte | Own markers per world/server (`config/trsclient-waypoints.json`): name, colour, in-world label with distance and light column, show/hide, automatic death waypoint |
| Minimap | Top-down map of the loaded chunks (map colours, height shading), rotating or north-up, zoom, waypoints, coordinates. Player dots are off by default and only ever show players the game already knows (normal render range) – no radar, no cave mode |
| TRS-Online-Funktionen | TRS badge (a pixel redstone block) in front of the names of TRS users in the tab list and on name tags, TRS capes (own and other players', HD and animated), in-game presence for friends. Talks to the TRS API (see below); switchable as a whole, per badge place and for capes |
| Umhang-Physik | Every rendered cape (Mojang, OptiFine, TRS, own and other players') moves like cloth instead of a rigid plank: swings when walking, turning, jumping and falling, rests on the back and bends at the hips when sneaking. Settings like WaveyCapes: *Stil* (glatt / blockig = vanilla-like steps), *Wind* (aus / Wellen / Böen) + *Windstärke*, *Bewegung* (Vanilla / schwingend / Dungeons = calm and floaty), *Schwerkraft*, *Anhebung beim Laufen*, *Steifheit*, *Detailstufe* (grid size, range and number of simulated capes), *Für* (nur eigener / alle Spieler). The settings page shows a live preview of your own player (turns by itself, drag to turn; alternates standing/walking) and a *Zurücksetzen* button; the settings are part of the HUD profiles. Defaults = the original behaviour. Elytras stay vanilla |
| Farben | Colour grading of the game image: *Sättigung* (0–200 %), *Kontrast*, *Helligkeit*, *Dynamik* (vibrance) and *Farbtemperatur*, applied right after the world and hand are drawn – HUD and menus keep their colours. Off by default, part of the HUD profiles |
| Emotes | Hold **G** (rebindable in the vanilla controls) for the emote wheel in the redstone style, point at an emote with the mouse, release to play it – other TRS players see it too. All 11 emotes of the TRS API (Winken, Klatschen, Jubeln, Verbeugen, Facepalm, Schulterzucken, Daumen hoch, Tanzen, Salutieren, Luftgitarre, Redstone-Tanz) are animated; locked ones are shown dark with a lock. Settings: *Kamera beim eigenen Emote* (unverändert / 3. Person von hinten / von vorn), *Emotes anderer Spieler zeigen* |
| Signalstärke *(Redstone)* | Look at dust, a repeater, comparator, piston, lamp, observer, lever, button, plate, daylight detector, target, door, dispenser, hopper … → HUD panel with the block name, signal strength 0–15 as a 15-segment bar + number, repeater delay (and "locked"), comparator mode and **output** (recomputed from its inputs – the client never receives it), piston extended/retracted, the input strength of consumers, and the comparator output of containers you have opened (the client only knows a chest's content while it is open; otherwise it is left out) |
| Signal-Overlay *(Redstone)* | Signal strength as a number above every piece of redstone dust within 4–16 blocks, grey (0) → bright red (15), smaller further away. Toggle key **F6** (F8 on Forge 1.7.10/1.8.9, where F6/F7 are the stream keys). Only loaded chunks, by default only dust in sight (line-of-sight check), cached: a budget of 4 096 block reads per tick searches the cube, known dust is re-read every tick |
| Takt-Messer *(Redstone)* | For the component you look at (keeps measuring after you look away, up to 32 blocks): frequency in Hz, period in redstone ticks (and game ticks), pulse length, and a 5-second oscilloscope. Shown only while the component switches |
| Startbildschirm | TRS title screen: animated redstone circuit on deepslate, glowing pixel wordmark, buttons as redstone lamps (Einzelspieler/Mehrspieler/Einstellungen/TRS-Menü/Mods*/Beenden; keyboard: Tab/arrows + Enter, narrated where the version has a narrator); link "Klassischer Titelbildschirm"; setting *Animierter Hintergrund* switches to a still image; disable the module to always get the vanilla one. Servers are only reached through Mehrspieler |

*Mods only if ModMenu is installed. The TRS menu also has a **Resourcepacks** screen (search, filter all/enabled/available,
toggle, priority ▲/▼, open folder; applied with one reload).

## TRS API: badges, TRS capes, presence

The client talks to the TRS API (`https://trs-launcher.theredstonee.de`, contract: `api/API.md` on the `trs-api`
branch) on its own; the old address `https://api.theredstonee.de` stays reachable and cape URLs on either host are
accepted (`OnlineConfig.isApiUrl`), every other host is refused. The logic is version independent in
`common/core/online` and `common/core/cape`:

- **Login like a Minecraft server:** `POST /v1/auth/challenge` → Mojang `session/minecraft/join` with the game's own
  access token and UUID (the `serverId` is passed unhashed) → `POST /v1/auth/verify`. The bearer token only lives in
  memory; a `401` logs in again once. Offline/demo accounts (no real token) never contact Mojang. Failed logins back
  off (30 s, 2 min, 10 min, 30 min), a `banned` account switches everything off for the session.
- **Off switch:** the launcher writes `config/trsclient/trs-api.json` (`{"version":1,"enabled":true|false}`) before
  every start; `false` means the mod makes no API call at all. Without the file the API is on; in game the module
  *TRS-Online-Funktionen* switches it off as well.
- **Lookup:** UUIDs from the tab list and the render distance are batched (≤ 100 per call, at most one call every 2 s),
  cached for 5 minutes (also "not a TRS user"), players who re-join the tab list are asked again after 30 s, and
  `429`/`Retry-After` pauses all lookups. Fair play: the only information shown is "uses TRS" and the cape.
- **Presence:** `POST /v1/presence` every 60 s while the game runs (`in-game` with version and loader); the server
  address is only sent when `GET /v1/me` says `shareServer` is on. Nothing is sent on quit (presence expires).
- **Capes:** the PNG is downloaded once per URL (the API's `?v=` changes with the content) into
  `config/trsclient/capes/<id>.png` with its `.etag`; a changed URL asks with `If-None-Match`. Only URLs of the API
  host are loaded, the PNG is decoded without AWT (`PngDecoder`), split into its vertical frames (64·scale × 32·scale
  each, scale 1–4) and every frame becomes its own texture, so cape **and elytra** use the vanilla UVs; the frame shown
  is `floor(now / frameTimeMs) % frames` (wall clock – every player sees the same frame). A TRS cape replaces the
  Mojang/OptiFine cape only when one is set; unused textures are released after 3 minutes.
- Networking runs in two daemon threads; the game thread only hands over work and reads results. Nothing in these
  features may crash the game: errors are logged at most once a minute and the vanilla cape is drawn instead.
- HTTPS works down to Mojang's Java 8u51 (`jre-legacy`, checked against the live API).

Per loader only a thin layer is needed: `online/OnlineHooks` (Fabric, NeoForge, Forge, Forge-Mojmap-legacy – one file,
Stonecutter branches for 1.14.4–26.3) plus the mixins `CapeTextureMixin` (TRS texture: `getCloakTextureLocation`
until 1.20.1, `getSkin()` → `PlayerSkin` from 1.20.2, `ClientAsset.ResourceTexture` from 1.21.9), `CapeLayerMixin`
(physics), `TabBadgeMixin` (`getNameForDisplay`) and `NameTagBadgeMixin` (`renderNameTag` until 1.21.1,
`extractRenderState` → `nameTag` from 1.21.2). The badge is a glyph of the mod's own bitmap font `trsclient:badge`
(a private-use character, so no resource pack can collide); before 1.16 (no fonts per text style) it is a dark red
`■`. Forge 1.8.9–1.12.2 has no mixins: `online/LegacyOnline` writes the TRS cape into the player's tab list entry
(cape and, from 1.9, elytra read it from there), prefixes the tab list display name and the name tag
(`PlayerEvent.NameFormat`) and replaces the vanilla `LayerCape` of both player renderers with `ClothCapeLayer`.
Private fields are found by type there, so the SRG-named release jars need no field names.

**Test against a local mock:** `-PtrsApi=http://127.0.0.1:<port>` on `runClient` points the API *and* the Mojang join at
a local server (plain HTTP is only accepted for localhost); the autotest then waits for the own TRS cape and takes
screenshots in third-person view (`cape-stand`, `cape-frame` = next animation frame, `cape-walk`, `cape-jump`,
`cape-sneak`, `cape-tab` with the badge).

## Emotes

The logic is version independent in `common/core/emote` (definitions, playback, pose maths) and
`common/core/ui/wheel/EmoteWheel` (the wheel); per loader only the model hook and the screen remain:

- **Wheel:** holding the key opens `EmoteWheelScreen`; the physical key is polled every frame (a screen stops the key
  bindings) and releasing it plays the emote the mouse points at (direction from the centre, dead zone in the
  middle). A short tap keeps the wheel open (then click or press again), Esc closes. Unlocked emotes are lamps,
  locked ones dark stones with a lock; without consent, account or connection the wheel only shows a hint.
- **API** (contract `api/API.md` §12/§13, same login as the badges): `GET /v1/me/cosmetics` → unlocked emotes
  (after login, every 10 min, when the wheel opens and the list is older than 60 s), `POST /v1/emotes/play`
  (the animation starts locally right away; at most one emote every 2 s, `429`/`Retry-After` extends the wait,
  `403` stops it and reloads the list). Other players' emotes come from the SSE stream
  `GET /v1/events/players?uuids=` (own UUID + visible TRS users from the lookup, ≤ 200; a new list opens a new stream
  at most every 10 s and closes the old one after its `hello`; reconnect with backoff 1 s … 60 s, `401` logs in again).
  The stream only runs while the module and *Emotes anderer Spieler zeigen* are on; the own echo is ignored.
  `trs-api.json` with `enabled:false` means no emote request at all.
- **Animation:** keyframes per channel (torso lean/twist/roll around the hips, whole-body offset, head on top of the
  look direction, arms/legs, shoulders) in `Emotes`, eased in/out; loops repeat their cycle until `durationMs`.
  `EmoteRig` turns a frame into the six model parts in Minecraft's Z·Y·X order (neck and shoulders follow the torso),
  limbs an emote does not use keep their vanilla pose. An emote ends when the player moves (> 0.05 blocks/tick after
  a 250 ms grace), sneaks, attacks or starts another one. During the own emote the camera switches to third person
  (option) and back afterwards if the player did not change it.
- **Model hook:** `HumanoidModel#setupAnim` TAIL (`EmoteModelMixin`, player and armor models); until 1.21.1 the
  entity is known there and the rest pose of a touched model is restored at HEAD (vanilla does not reset every
  channel), from 1.21.2 the render state is mapped to the player in `extractRenderState` (`EmoteStateMixin`).
  Forge 1.8.9–1.12.2 (no mixins) replaces the vanilla `ModelPlayer`/armor `ModelBiped` of the player renderers with
  subclasses whose `setRotationAngles` applies the pose (`LegacyEmotes`, fields found by type).

## Umhang-Physik

`core/cape/ClothSim` is a verlet cloth (10 × 16 cells near, 5 × 8 further away) in the player's body frame
(x left, y down, z back, in model pixels), pinned at the shoulders. Free points keep their momentum in the world: when
the body moves or turns, they are shifted/rotated the opposite way (spread over 4 sub-steps per tick), so the cape
trails when starting to run, swings when turning and flutters when jumping or falling (vertical inertia damped, it never
rises more than 2 px above the shoulders). Gravity is tilted with the torso when sneaking, quadratic air drag (*Wind*)
lifts it when running, the body and legs are a half-space it cannot enter (the leg that swings back pushes it out),
and long-range tethers keep it from stretching. It is simulated in the client tick and drawn interpolated.

Level of detail (`CapePhysics`, *Detailstufe* hoch): own player always fine, others fine up to 16 blocks (at most 8),
coarse up to 40 blocks, at most 24 simulated capes; everyone else keeps the rigid vanilla cape (mittel/niedrig: smaller
grids, 12/8 and 32/24 blocks, 16/10 capes).

The settings live in `core/cape/CapeSettings` and map onto `ClothSim.Params`: *Wind* switches the flutter/sway
(Wellen) and adds gusts (Böen: smooth pseudo-random `gust(t)` per player phase that lifts the hem even while
standing), *Bewegung* sets inertia, turn inertia, damping and wave speed (Vanilla = follows the body closely,
Dungeons = heavily damped, slow waves, lighter and floatier), *Schwerkraft* scales gravity, *Anhebung beim Laufen*
scales the quadratic air drag, *Steifheit* scales the bend/shear constraints. *Blockig* simulates one column of
16 strips and `ClothMesh#emitBlocky` draws every strip as its own flat box with a flat normal, so bends show as
vanilla-like steps.

The live preview (`MenuHost#drawPlayerPreview`) draws the own player turned around the vertical axis: an own copy of
`renderEntityInInventory` up to 1.19.3, `InventoryScreen.renderEntityInInventory` with a rotation quaternion
1.19.4–1.21.10, the extracted render state via `GuiGraphics#submitEntityRenderState` on 1.21.11 and
`GuiGraphicsExtractor#entity` on 26.x (`online/PlayerPreview`, one file for the Mojmap trees), an own copy of
`drawEntityOnScreen` with a pre-rotated GL matrix on Forge 1.8.9–1.12.2. While the preview is open the own cape is
simulated as if walking every other 3 seconds.

## Farben

`core/render/ColorGrade` turns the sliders into one affine 3×4 colour matrix (brightness → contrast around mid grey
→ saturation with Rec. 709 luma → temperature) plus a vibrance step, and holds the GLSL sources; `apply()` is the
same maths on the CPU for the unit tests. The pass runs right after `GameRenderer#renderLevel` (world + hand drawn,
no HUD/menu yet):

- Fabric, NeoForge, Forge 1.15.2–26.3 (`mixin/ColorGradeMixin` + `render/ColorPass`, one file for the Mojmap trees):
  own raw OpenGL pass – the main target's colour texture (`colorTextureId` ≤1.15, `getColorTextureId()` 1.16–1.21.4,
  `GlTexture#glId()` from 1.21.5, `com.mojang.renderpearl` on 26.3) is copied into an own texture and written back
  through an own program (GLSL 1.20 up to 1.16, 1.50 core from 1.17), own VAO/VBO and FBO. Every GL state it touches
  is queried before and restored after, so Minecraft's state cache stays valid.
- Forge 1.8.9–1.12.2 (`render/ColorPass`): an own `ShaderGroup` with the program
  `assets/minecraft/shaders/program/trsclient_color` (before 1.11 programs must be in the minecraft namespace) and
  vanilla `blit`, run from `RenderGameOverlayEvent.Pre` (ALL) or, with F1, at the end of the render tick. `ClothMesh` emits outer face, inner
face and all four edges with the vanilla cape UVs (the fractions are the same for every HD scale).

## Menu, HUD editor and profiles

The menu (Right Shift) shows every module as a tile with icon, full name (two lines if needed) and switch in a 2–4
column grid that grows with the window: a search field, the category tabs **HUD / PvP / Chat / Welt / Redstone / Sonstiges**,
and a click on a tile (or its gear) opens that module's settings page. Settings are typed and drawn by the same code
everywhere: switch, slider, colour picker (hue/saturation field, opacity and **Chroma**, plus the brand palette),
dropdown and key binding.

Everything is drawn in the **redstone style** (`core/ui/Redstone`): deepslate surfaces with pixel edges, redstone
dust lines whose signal strength fades, lamps that light up (hover, focus, primary buttons), enabled tiles glow like
powered blocks; the launcher accent tints dust and glow. The title screen lives in `core/ui/title` (`TitleUi` +
`CircuitScene`, per version only a small `TitleHost`); its background is a procedural circuit (torches, dust,
repeaters with their delay, lamps, pistons) that is rebuilt only when the window size changes and switches itself to
a sparser version if drawing it gets expensive (always sparse on Forge 1.8.9–1.12.2). On 1.20–1.21.1 the TRS screens
draw inside `GuiGraphics#drawManaged`, otherwise every rectangle would be a draw call of its own.

**HUD bearbeiten** drags the modules around: they snap to the screen edges, the screen centre and to the edges and
centres of the other modules, and the guide line that is being used lights up. Holding Shift moves freely, the mouse
wheel changes the size and a right click resets a module. A click selects a module and opens a small panel with size,
background opacity, text shadow, text colour (with chroma) and **Zurücksetzen**.

### Einstellungs-Typen für neue Module

A setting is one of `BoolSetting`, `NumberSetting`, `ColorSetting`, `ChoiceSetting`, `KeySetting` and – new –
**`TextSetting`** (free text such as the Auto-GG message; stored in the `texts` map of `ModuleConfig`).
`SettingsPanel` draws it as an inline field: a click opens it, typing writes straight into the setting
(`typeChar`/`typeKey`, routed from `ModMenu`). Module keys (waypoints, text hotkeys) are `KeySetting`s and are
read with `core.input.KeyPresses` + `compat.Keys#isDown`, so they need no vanilla key binding.

Positions are stored as anchor + offset relative to the screen size, so they survive resolution/GUI-scale changes.

**HUD profiles** are complete HUD layouts (e.g. PvP, Bauen, Aufnahme): a profile keeps on/off, position, size and
look of every HUD module, while the other modules stay shared. Profiles are created, renamed, deleted and switched
under **Profile** in the menu or in the editor's top bar; the key *HUD-Profil wechseln* (unbound by default) cycles
through them in game.

The colours come from the launcher: before a start it writes `config/trsclient/launcher-theme.json` (theme and accent)
into the instance, and the menu and the editor use that accent. Without the file the dark theme with Redstone red applies.

## Keys

Listed under **TRS Client** in the vanilla controls menu.

| Key | Action |
| --- | --- |
| Right Shift | Open the TRS menu |
| V (hold) | Zoom (V is free in every vanilla version; C is "save hotbar activator" from 1.12 on) |
| Left Alt (hold/toggle) | Freelook (module must be enabled) |
| unbound | Toggle Fullbright (also switchable in the menu) |
| unbound | Switch the HUD profile (cycles) |
| G (hold) | Emote wheel (release to play; tap = click mode) |
| F6 (Forge 1.7.10/1.8.9: F8) | Toggle the redstone signal overlay |

The waypoint keys (**B** create, **N** list) and the four text hotkeys are settings of their modules and are
rebound in the TRS menu, not in the vanilla controls screen. Zoom and freelook are vanilla bindings: their *Taste*
row in the TRS menu is linked to the same binding (`KeySetting.link`, stored in `options.txt`), so both places show
and change one key.


## Languages

The whole client (menu, modules, settings, HUD texts, title screen, HUD editor, profiles, packs, waypoints, chat
messages, key binding names) is translated like the launcher: **English** (default and fallback), **German** and
**Spanish** complete, **French, Polish, Portuguese (Brazil), Turkish, Dutch** as beta (machine quality).

- Source of truth: flat UTF-8 JSON files `common/src/main/resources/assets/trsclient/i18n/<code>.json`, bundled into
  every jar (all builds add `common/src/main/resources`). Placeholders are `{0}`, `{1}` …
- `core/i18n/I18n`: one merged table per language (English + language), lookup is a single map access; module and
  setting labels cache their text per language (`I18n.generation()`), so drawing a frame allocates nothing extra.
  Missing key → English → the key itself.
- Language: `"language"` in `config/trsclient/launcher-theme.json` (written by the launcher before every start);
  without it the language selected in Minecraft (`lang:` in `options.txt`, all regional variants such as `es_mx`,
  `de_at`, `fr_ca` map to the base language), otherwise English. Both files are re-checked (timestamps only) when the
  TRS menu or the title screen opens.
- Key binding names/category for the vanilla controls screen come from Minecraft's own lang files
  (`assets/trsclient/lang/*.json`, `.lang` for Forge 1.7.10–1.12.2; `legacy` renames them to `xx_XX.lang` below 1.11).
  They are generated from the i18n files: `node client-mod/scripts/gen-lang.mjs` (after changing a `key.trsclient.*`
  text).
- Tests: `I18nTest` (every language has all keys of English – the coverage is printed like in the launcher –,
  placeholders match, every key used in the code of all loader trees exists, fallback, language detection) and
  `TextFitTest` (names fit the menu tiles, rail, title buttons and the HUD-editor panel in the default 854×480
  window, measured with Minecraft's glyph widths; beta languages are only reported).

## Redstone-Werkzeuge

Category **Redstone** in the menu. The logic is version independent in `common/core/redstone`: `RedstoneTools`
(one call per client tick), `RedstoneReadout` (what the panel shows), `ComparatorMath` (container signal
`⌊fill·14⌋+1`, compare/subtract, rear input through a solid block, comparator chains), `FrequencyMeter` (rising
edges → Hz/period, averaged pulse length, 200-tick history), `SignalCache` + `LineOfSight` (overlay), `SignalColors`,
`RedstonePanels`/`SignalOverlay` (drawn through `Canvas`, the overlay with the same own projection as the waypoints –
no world rendering, no mixin). Per loader only a small adapter `compat/RedstoneProbe` (`RedstoneWorld`: probe a block,
dust power, vanilla `getSignal`/direct signal, comparator input override, conductor/opaque) and `hud/RedstoneHuds`:
one identical file for the Mojmap trees (1.14.4–26.3; the only branch is `getAnalogOutputSignal` with a direction from
1.21.9), block IDs + state property names for Forge 1.8.9–1.12.2, block + metadata for 1.7.10, MCP names for 1.13.2.
Fair on every server: only block states the client already has are read, nothing is sent.

## Fair play

The client never automates anything and never shows more than the game already knows:
reach/combo/speed only *display* what happened, hitboxes and the block outline are the vanilla
shapes (only colour/width change), the minimap reads loaded chunks only (no cave mode, no
entity radar; player dots are off by default), Auto-GG and text hotkeys are off by default and
rate limited so they can never flood a chat. Freelook and Auto-GG are forbidden on some servers –
they stay off until you turn them on, and freelook has its own server list on which it switches itself off.
Freelook never turns the player (no extra rotation packets), the fly boost only works in creative flight.

The logic of the comfort modules is version independent in `common`: `core/input/MovementToggles` (toggle state
machine, death/world reset, fly boost via `FlyBoost`, HUD status `ToggleStatus`), `core/camera/FreelookState`
(hold/toggle, pitch clamp, perspective) + `ServerList`, `core/zoom/ZoomState#frame` (curve `1 - e^(-14·t)`,
spyglass, proportional mouse) and `core/util/FlagOverride` (cinematic camera). Per loader only the key state,
the camera/FOV hooks that already existed and the fly speed are touched.

Configs written before the key moved are migrated once: if zoom is still on the old default C, it moves to V –
a key the player bound themselves is never touched.

## Supported versions

1.14.4, 1.15.2, 1.16.2–1.16.5, 1.17, 1.17.1, 1.18–1.18.2, 1.19–1.19.4, 1.20.1–1.20.6, 1.21–1.21.11,
26.1, 26.1.1, 26.1.2, 26.2, 26.3 (checked against Mojang's version manifest and meta.fabricmc.net).

Not built: 1.14–1.14.3 (no official Mojang mappings), 1.15, 1.15.1 and 1.16 (their newest Fabric API on Modrinth,
which the launcher installs, has no `fabric-lifecycle-events-v1`) and 1.16.1 (rendering/text API predates 1.16.2).
On older versions some features degrade: 1.14 has no hit color, the pack search hint is a suggestion text before 1.19.3,
vanilla toggle sprint/sneak only exists from 1.15.

### What is missing where

| Feature | Not available on | Why |
| --- | --- | --- |
| Niedriges Feuer, Block-Umrandung, Hitbox-Farbe | Fabric 1.14.4 | no `ScreenEffectRenderer`, the outline is drawn with fixed GL calls |
| Linienstärke der Umrandung | Fabric/Forge 1.14.4 and 1.21.11+ | `RenderSystem.lineWidth` does not exist there |
| Hitboxen (an/aus + Farbe) | 1.21.9+ | the toggle moved into the debug-screen entries and a separate renderer |
| Hitbox-**Farbe** | Forge 1.15.2–1.16.5 | there `renderHitbox` only routes the eye line through `renderLineBox`, the box itself goes through a private method |
| 1.7-Animationen: Schlag beim Benutzen | 26.3 | the swing state is no longer a public field |
| 1.7-Animationen: Hand bleibt oben | Forge 1.8.9–1.12.2 | `ItemRenderer.equippedProgress` is private and there is no hook |
| Wegpunkte je Dimension | 1.14.4, 1.15.2 | no `Level#dimension()`; waypoints are then valid in every dimension of that world |
| Treffer-Farbe, niedriges Feuer, Reichweite/Combo, Chat-Tools, Auto-GG, Kein Schadens-Wackeln, Block-Umrandung | Forge 1.14.4 | that build has no Mixin at all – the modules are hidden in the menu |
| TRS-Startbildschirm | Forge 1.13.2 and 1.7.10 | not ported – the vanilla title screen stays; the menu has the redstone style there too |
| Alle neuen Module | Forge 1.13.2 and 1.7.10 | not ported yet (see "Open") |
| Freelook | Forge 1.7.10, 1.13.2, 1.14.4 | no camera hook (no Mixin there) – hidden in the menu; zoom and toggle sprint/sneak incl. fly boost work |
| TRS-Online-Funktionen, Umhang-Physik | Forge 1.13.2 and 1.7.10 | not ported – hidden in the menu |
| Umhang-Physik (incl. settings and live preview) | Fabric/Forge 1.14.4 | the cape is still drawn with fixed GL calls there – the cape stays rigid (TRS capes and badges work on Fabric 1.14.4) |
| TRS-Umhang, TRS-Abzeichen | Forge 1.14.4 | no Mixin in that build – only login and presence |
| Emotes | Forge 1.14.4, 1.13.2 and 1.7.10 | no model hook there (no Mixin / not ported) – hidden in the menu |
| Farben | Forge 1.14.4, 1.13.2 and 1.7.10 | no hook after the world pass (no Mixin / not ported) – hidden in the menu |
| Farben | 26.2+ with the Vulkan backend | the pass is OpenGL; with Vulkan there is no GL texture – hidden in the menu (OpenGL is the default) |
| Abzeichen als Pixel-Redstone-Block | 1.14.4, 1.15.2, Forge 1.8.9–1.12.2 | no per-text font – a dark red `■` instead |
| TRS-Umhang über OptiFine | Forge 1.8.9–1.12.2 with OptiFine | OptiFine's own cape getter wins there |
| Signalstärke: Türen, Falltüren, Zauntore, Notenblöcke | Forge 1.7.10 | no "powered" bit in their metadata – not recognised as components |
| Signal-Overlay: FOV of sprint/speed | Forge 1.7.10 | the FOV modifier is private there – numbers sit slightly off while sprinting |
| Bewegungsunschärfe | all | not implemented (see "Open") – copying the frame needs a different path per render era |

### Open

- **Bewegungsunschärfe** (motion blur): needs a copy of the previous frame. Up to 1.21.4 that is a framebuffer
  blit, from 1.21.5 it goes through the new `GpuDevice`/`CommandEncoder` and in 26.x again differently, so it needs
  three separate implementations; vanilla's own `phosphor` post effect only exists up to 1.20.6. Not built yet.
- **Forge 1.13.2 and 1.7.10**: the new modules are not ported. Both builds still compile and run with everything
  that existed before.

Each version gets its **own jar** whose `fabric.mod.json` depends on exactly that Minecraft version
(`trsclient-fabric-<minecraft>.jar`, requires the Fabric API modules it uses). Bytecode: Java 8 for 1.14–1.16,
16 for 1.17, 17 for 1.18–1.20.4, 21 for 1.20.5–1.21.11, 25 for 26.x. `common` is plain Java 8 for that reason.

## Build

Requires a JDK 21+ to run Gradle (Gradle toolchains download JDK 17/21/25 for compiling/running if missing).

```sh
./gradlew build                      # builds every version + runs the unit tests of common/
./gradlew collectLauncherJars        # builds everything, writes dist/*.jar + dist/builds.json for the launcher
./gradlew :fabric:1.21.1:build       # a single version
./gradlew :fabric:1.21.1:runClient   # starts that Minecraft version with the mod (game dir: client-mod/run)
./gradlew :fabric:26.3:runClient -PtrsAutotest   # self-test: menu, test world, screenshots, quits
```

Self-test screenshots: `run/screenshots/trsclient-<minecraft>-*.png` (one test world per version).

### Launcher contract: `dist/`

`collectLauncherJars` writes (git-ignored) `dist/trsclient-fabric-<minecraft>.jar` and `dist/builds.json`:

```json
[{ "loader": "fabric", "minecraft": ["1.21.1"], "file": "trsclient-fabric-1.21.1.jar", "requires": ["fabric-api"] }, ...]
```

`minecraft` lists every exact game version the jar supports. Entries of other projects/loaders already present
in `builds.json` are kept (only Fabric entries for the built versions are replaced).

### Publishing (update channel)

The launcher updates the TRS Client on its own, without a launcher release: it polls the GitHub release
`client-mod` (at start and at most every 30 minutes before a game starts), verifies the signed
`client-mod.json` and downloads only the jar an instance needs. To ship a new version:

```sh
node scripts/publish-client-mod.mjs --bump patch   # raises mod_version in every gradle.properties
# rebuild: collectLauncherJars in each project, one after another (they share dist/)
node scripts/publish-client-mod.mjs --dry-run      # writes + signs + verifies dist/channel/client-mod.json
node scripts/publish-client-mod.mjs                # the same, then uploads to the client-mod release
```

The manifest is `{ "version": "0.3.0", "builds": [{ loader, minecraft[], file, requires[], sha256, size }] }`,
signed with the Tauri updater key (`tauri signer sign`). The same file, unsigned, plus the jars are written to
`src-tauri/resources/client-mod/`, so the next launcher release bundles that state (commit it). Launchers only
accept a channel version that is newer than the bundled one.

## Adding modules and settings

New modules are registered in `common` (`core/module/TrsModules`) and rendered per version; the menu picks them up
automatically. The API for that:

| Piece | What it does |
| --- | --- |
| `registry.register(new Module(id, name, description, defaultOn))` | a module; `.category(Category.PVP)` and `.icon("sword")` set its tab and tile icon (icons: `core/ui/Icons`) |
| `new HudModule(id, name, description, defaultOn, position)` | a module drawn in the HUD; brings text colour, text shadow, background, background opacity and size |
| `module.add(new BoolSetting(key, label, default))` | switch |
| `… new NumberSetting(key, label, default, min, max, step, prefix[, suffix])` | slider |
| `… new ColorSetting(key, label, defaultArgb[, alphaEditable])` | colour picker; `argb()` already includes chroma |
| `… new ChoiceSetting<>(key, label, EnumType.class, default)` | dropdown (enum implements `ChoiceSetting.Option`) |
| `… new KeySetting(key, label[, "key.keyboard.v"])` | key binding, stored as the version-neutral vanilla key name |

Names and labels in the code are only English fallbacks: every module, setting and choice option also needs its
translation keys (`module.<id>`, `module.<id>.desc`, `setting.<module>.<key>`, `setting.<module>.<key>.<option>`,
text placeholders `….hint`) in the language files – see "Languages" below. `I18nTest` fails if a key is missing.

The version-specific side only implements `core/ui/Canvas` (see `ui/GfxCanvas`) and `core/ui/menu/MenuHost`
(open screens, sounds, key names, HUD elements for the editor); menu, settings pages, profiles and the HUD editor
live in `core/ui` and are shared by every loader and Minecraft version.

## Layout

```
common/                   version-independent Java (modules, settings, config, HUD layout math, CPS, zoom,
                          the whole interface in core/ui) + unit tests
fabric/                   Minecraft code, ONE source tree for all versions (Stonecutter)
  src/                    sources, checked in for the 1.21.1 state
  versions/<mc>/          per-version gradle.properties (Fabric API version) + build output
  build.gradle            build script run for every version (Loom, Java level, dependencies)
  stonecutter.gradle      Stonecutter controller + collectLauncherJars
```

### Multi-version with Stonecutter

[Stonecutter](https://stonecutter.kikugie.dev/) preprocesses `fabric/src` per version. Differences are written as
comments that Stonecutter toggles:

```java
//? if >=1.21.6 {
/*g.pose().pushMatrix();
*///?} else
g.pose().pushPose();
```

The version-specific code is kept in a few places:

- `ui/Gfx` – drawing (GuiGraphics → GuiGraphicsExtractor in 26.1, PoseStack → Matrix3x2fStack in 1.21.6)
- `compat/Mc` – screen/overlay/HUD access (moved to `Minecraft.gui` in 26.2)
- screens – render/input entry points (input events changed in 1.21.9, scroll in 1.20.2) delegate to shared logic
- mixins – FOV (`GameRenderer#getFov`, double until 1.21.1, float after; `Camera#calculateFov` in 26.x),
  lightmap (`LightTexture` → `LightmapRenderStateExtractor` in 26.x), mouse (`onPress` → `onButton` in 1.21.9)
- HUD registration – `HudRenderCallback` until 1.21.5, Fabric's `HudElementRegistry` from 1.21.6

`common/src/main/java` is compiled into every version jar with that version's Java level.
To edit code for another version in the IDE: `./gradlew "Set active project to 26.3"`, and switch back to 1.21.1
before committing. Minecraft 26.x is not obfuscated, so those versions use Loom without remapping/mappings.

Adding a version: add it to `settings.gradle` (`stonecutter { ... }`) and create `fabric/versions/<mc>/gradle.properties`
with `fabric_api_version=...`.
