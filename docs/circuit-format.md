# Circuit format (TRS Client circuit library)

The circuit library of the TRS Client is served by the TRS API. The mod ships **no circuits** – only the block
catalogue it validates against (`client-mod/common/src/main/resources/assets/trsclient/circuits/blocks.json`).
The 25 built-in circuits are source data for the API in `data/circuits/<id>.json` (+ `data/circuits/index.json`
for the order). This document is the contract between the API and the mod.

Reference implementation (parser/validator): `client-mod/common/src/main/java/dev/theredstonee/trsclient/core/circuit/Circuit.java`
(`Circuit.parse`), block rules in `BlockCatalog.java`, simulation tests in `sim/CircuitSimTest.java`.

## 1. API routes used by the mod

| Route | Auth | Purpose |
|---|---|---|
| `GET /v1/circuits/index` | none | List of all circuits. Sent with `If-None-Match: <etag of last index>`; `304` = nothing to do. |
| `GET /v1/circuits/{id}?rev={rev}` | none | One circuit in exactly the format below. Immutable per `rev` (cache forever). |
| `POST /v1/circuits/submissions` | `Bearer <token>` | Submit a circuit from the game. |
| `GET /v1/me/circuit-submissions` | `Bearer <token>` | Own submissions with status. |

### Index (`200`)

```json
{
  "version": "<hash of the whole index>",
  "circuits": [
    { "id": "not_gate", "rev": "3f9a…", "updatedAt": "2026-09-27T12:00:00Z", "minVersion": "1.8", "maxVersion": null }
  ]
}
```

- Response header `ETag` should be set (the mod falls back to `"<version>"`).
- `id`: `^[a-z0-9_]{1,48}$`. `rev`: `^[A-Za-z0-9._-]{1,64}$`. `updatedAt` ≤ 40 chars.
- `minVersion`/`maxVersion` (optional): Minecraft versions like `1.21` / `1.20.6` / `26.3`; the stricter of index and
  circuit (`since`/`until`) wins.
- Order of the array = order in the library. At most **200** entries are used.

### Client behaviour

- Once per game start, in a background thread (never the render thread): 1 index request + only the circuits whose
  `rev` changed or that are missing locally. Removed ids are deleted locally.
- Cache: `config/trsclient/circuits/index.json` (etag, version, entries) + `config/trsclient/circuits/<id>.json`
  (the raw circuit response), written atomically. Every circuit is validated before it is cached; invalid ones are
  skipped (an old valid revision is kept and the next start retries).
- Limits: circuit response ≤ **256 KB**, index ≤ 256 KB.
- No internet and no cache → the library is empty and shows “will be loaded at the next start with internet”.
- If the player disabled the TRS online features (launcher `trs-api.json` `enabled:false` or the “TRS Online” module),
  the mod makes no request.

### Submission

Request body:

```json
{ "circuit": { …circuit in the format below… }, "name": "My clock", "category": "clocks",
  "description": "Short text", "lang": "en" }
```

- `circuit.id` is a slug of the name (`^[a-z0-9_]{1,48}$`) – the server may assign its own id.
- `circuit.texts` contains the same name/description under `lang` (`en` or `de` from the form).
- `circuit.category` = `category`. `difficulty` 1, `server` "ok", no `tests` (the team adds them on review).
- Answers: `201 { "id": "…", "status": "pending" }`; errors as `{ "error": { "code": "…" } }`:
  `409 circuit_duplicate`, `429 rate_limited` (with `Retry-After`), `403 sanctioned` (upload ban),
  `400 invalid_circuit`, `401 unauthorized`.

`GET /v1/me/circuit-submissions` → `{ "submissions": [ { "id", "name", "category", "status", "reason", "createdAt" } ] }`
(a bare array is accepted too). `status`: `pending` | `approved` | `rejected`; `reason` only for `rejected`.

## 2. Circuit JSON

```json
{
  "format": 1,
  "id": "not_gate",
  "category": "basics",
  "difficulty": 1,
  "server": "ok",
  "since": "1.8",
  "until": null,
  "texts": {
    "en": { "name": "NOT gate (inverter)", "desc": "…", "note": "…" },
    "de": { "name": "NICHT-Gatter (Inverter)", "desc": "…" }
  },
  "palette": {
    "A": "lever[face=floor]@A",
    "-": "redstone_wire",
    "#": "solid",
    "t": "redstone_wall_torch[facing=east]",
    "L": "redstone_lamp@Q"
  },
  "layers": [
    ["A-#tL"]
  ],
  "tests": [
    { "truth": { "in": ["A"], "out": ["Q"], "rows": ["0:1", "1:0"] } }
  ]
}
```

| Field | Type | Rules |
|---|---|---|
| `format` | int | optional, currently `1`; a higher value is rejected by old mods. |
| `id` | string | `^[a-z0-9_]{1,48}$`, must equal the index id. |
| `category` | string | `basics`, `clocks`, `memory`, `pulse`, `doors`, `farms`, `displays`. |
| `difficulty` | int | 1 (easy) – 3 (tricky), default 1. |
| `server` | string | `ok` (runs reliably on Java servers) or `note` (then `texts.*.note` explains why). |
| `since` | string | optional minimum Minecraft version (digits and dots, 2–4 parts). Raised automatically to the newest block (`observer` 1.11, `copper_bulb` 1.21). Default `1.8`. |
| `until` | string | optional maximum Minecraft version (inclusive). |
| `texts` | object | language (`en`, `de`, `es`, `pt-BR` …, at most 12) → `name` (≤ 64), `desc` (≤ 1200), `note` (≤ 300). No control characters except `\n`, no `§`. Fallback: exact language → base language → `en` → first language. |
| `palette` | object | ≤ 64 entries; key = one printable ASCII character except `.` and space; value = block spec (below). |
| `layers` | array | `layers[y][z]` = string along x. 1–16 layers, ≤ 16 rows, ≤ 16 characters; `.` and space = air (not checked). |
| `tests` | array | optional, ≤ 16 simulation tests (section 5). |

### Coordinates and directions

- `x` = east (+X), `y` = up, `z` = south (+Z); `layers[0]` is the bottom layer, row 0 is the north side.
- Size = the largest extents of `layers`, at most **16 × 16 × 16**, at least one block.
- Directions are Minecraft's: `north`/`south`/`east`/`west`/`up`/`down`.

## 3. Block specs

`<block>[<prop>=<value>,…]<flags>@<marker>`

- `<block>`: a key of the block catalogue (modern id without `minecraft:`), or `solid` = **any full, conductive
  block** (stone, planks, wool …; material list shows stone).
- `<flags>` (optional, after the brackets): `~` = movable (pistons move it; a piston head at this spot counts as
  correct), `?` = display/simulation only (not checked, not in the material list, e.g. flowing water, grown cane).
- `@<marker>` (optional): `^[A-Za-z0-9]{1,8}$`, names an input/output for the simulation tests.

Allowed properties (anything else is rejected – no NBT, no free strings):

| Property | Values | Blocks |
|---|---|---|
| `facing` | `north` `south` `east` `west` `up` `down` | repeater, comparator, wall torch, observer, piston, sticky piston, hopper, dropper, lever/button on a wall |
| `delay` | `1`–`4` | repeater |
| `mode` | `compare` `subtract` | comparator |
| `face` | `floor` `wall` `ceiling` | lever, button |
| `inverted` | `true` `false` | daylight detector |

Meaning of `facing` (identical to the Minecraft block state):

- repeater/comparator: direction **towards the input** (signal flows the other way);
- observer: the side it watches (output on the back);
- piston/dropper/hopper: push/output direction;
- wall torch, wall lever/button: direction away from the wall it hangs on.

### Block catalogue (`blocks.json`)

| Key | Material item (modern / 1.8.9–1.12.2) | Since | Checked props | Also accepted |
|---|---|---|---|---|
| `solid` | stone | 1.8 | – | any conductive full block |
| `glass` | glass | 1.8 | – | white_stained_glass, tinted_glass; legacy glass, stained_glass |
| `sand` | sand | 1.8 | – | red_sand |
| `soul_sand` | soul_sand | 1.8 | – | |
| `sugar_cane` | sugar_cane / reeds | 1.8 | – | legacy reeds |
| `water` | water_bucket | 1.8 | – | bubble_column; legacy water, flowing_water |
| `redstone_wire` | redstone | 1.8 | – | |
| `repeater` | repeater | 1.8 | facing, delay | legacy unpowered_repeater, powered_repeater |
| `comparator` | comparator | 1.8 | facing, mode | legacy unpowered_comparator, powered_comparator |
| `redstone_torch` | redstone_torch | 1.8 | – | legacy redstone_torch/unlit_redstone_torch with facing=up |
| `redstone_wall_torch` | redstone_torch | 1.8 | facing | legacy redstone_torch/unlit_redstone_torch with facing≠up |
| `lever` | lever | 1.8 | face | legacy facing up_x/up_z → floor, down_x/down_z → ceiling, else wall |
| `stone_button` | stone_button | 1.8 | face | legacy facing up → floor, down → ceiling, else wall |
| `stone_pressure_plate` | stone_pressure_plate | 1.8 | – | oak/polished_blackstone pressure plate; legacy wooden_pressure_plate |
| `redstone_lamp` | redstone_lamp | 1.8 | – | legacy lit_redstone_lamp |
| `redstone_block` | redstone_block | 1.8 | – | |
| `piston`, `sticky_piston` | piston, sticky_piston | 1.8 | facing | |
| `observer` | observer | 1.11 | facing | |
| `hopper` | hopper | 1.8 | facing | |
| `chest` | chest | 1.8 | – | trapped_chest, barrel |
| `furnace` | furnace | 1.8 | – | legacy lit_furnace |
| `dropper` | dropper | 1.8 | facing | |
| `copper_bulb` | copper_bulb | 1.21 | – | all oxidation and waxed variants |
| `daylight_detector` | daylight_detector | 1.8 | inverted | legacy daylight_detector_inverted → inverted=true |
| `oak_sign` | oak_sign / sign | 1.8 | – | all standing signs; legacy standing_sign |
| `glowstone` | glowstone | 1.8 | – | |

Checking against the world (green/red/grey): the block key must match (aliases and legacy names are translated
first), then each *checked* property that both sides have must be equal. `solid` only needs a conductive block.

## 4. Rotation and mirroring (client side)

Templates are placed with a quarter-turn rotation (clockwise seen from above) and an optional mirror on the x axis
(applied before rotating). One quarter turn maps `(x, z) → (depthZ − 1 − z, x)` and `north → east → south → west`;
mirroring swaps `east`/`west`. Only `facing` changes; all other properties stay. The server stores circuits
unrotated.

## 5. Simulation tests (optional)

Run by the mod in its own Java-redstone simulation; only if all pass does the library show “logic checked”. Each
test starts after 40 game ticks warm-up. Numbers are game ticks (1 redstone tick = 2 game ticks), capped at 2000
per step; at most 128 rows/steps per test.

```json
{ "truth": { "in": ["A", "B"], "out": ["Q"], "rows": ["00:0", "01:1"], "ticks": 30 } }
{ "setup": [ … ], "steps": [
    { "set": { "A": 1 } }, { "press": "B" }, { "bump": "G" }, { "run": 20 },
    { "expect": { "Q": 1 } },
    { "count": { "out": "Q", "ticks": 200, "toggles": [4, 999] } },
    { "count": { "out": "Q", "ticks": 60, "on": [2, 12] } },
    { "mark": "Q" }, { "changed": { "Q": 1 } } ] }
```

- `set`: lever/plate/button on (>0) or off; container fill level or daylight strength 0–15 (hex digit in truth rows);
  dropper item count.
- `press`: stone button (20 ticks). `bump`: change the state of a block an observer watches.
- Outputs: lamp lit, piston powered, hopper **locked**, copper bulb lit, torch lit, dust power > 0,
  repeater/observer on, comparator output > 0, dropper holds items.

## 6. Limits (summary)

- Size 1–16 per axis, ≤ 4096 cells, palette ≤ 64, response ≤ 256 KB, index ≤ 200 entries.
- Texts: name ≤ 64, desc ≤ 1200, note ≤ 300, ≤ 12 languages.
- Unknown blocks or properties → the whole circuit is rejected (never partially shown).
