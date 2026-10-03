# Touch controls – layout format (version 1)

Button layouts of the TRS mobile app. One JSON file per layout in `<app data>/controls/<id>.json`.
Same rules in Rust (`src-tauri/crates/core/src/controls.rs`, source of truth), TypeScript
(`app/utils/controls.ts`), Kotlin (`…/android/…/overlay/Layout.kt`) and Swift (`…/ios/Sources/Overlay/Layout.swift`).

```jsonc
{
  "version": 1,
  "id": "pvp",                       // ^[a-z0-9][a-z0-9-]{0,39}$ = file name
  "name": "PvP",                     // 1–48 chars
  "profile": "pvp",                  // pvp | build | redstone | custom (category)
  "builtinRev": 1,                   // only on untouched built-in layouts
  "buttons": [ … up to 48 … ],
  "gestures": { "tapAttack": true, "holdUse": true, "swipeHotbar": true, "cameraSensitivity": 1.0, "haptics": true }
}
```

Button: `id` (`[A-Za-z0-9_-]{1,32}`, unique), `label` (≤ 12 chars) and/or `icon` (pixel icon name),
`x`, `y`, `w`, `h` as fractions **of the safe area** (screen minus notch/gesture insets), `w`/`h` ≥ 0.02,
`x + w ≤ 1`, `y + h ≤ 1`; `opacity` 0.05–1 (default 0.6); `shape` `round` (circle, diameter = shorter side)
or `rect`; `toggle` (key/mouse latch), `passThrough` (finger on the button also turns the camera);
optional `show`: `game` | `menu` | `always` (default: menu/keyboard/chat/TRS menu/Esc always, else in game only).

Actions (`action.type`):

| type | fields | meaning |
| --- | --- | --- |
| `key` | `key` (GLFW 32–348), optional `chord` (≤ 3 keys) | chord keys go down first, up last (F3+G = `{"key":71,"chord":[292]}`) |
| `mouse` | `button` (0 left, 1 right, 2 middle … 7), optional `chord` | sneak-place = `{"button":1,"chord":[340]}` |
| `toggle` | `key` | tap = held, tap again = released |
| `joystick` | `mode`: `wasd` / `camera` | move stick (8 directions, pushed past the rim forward = sprint/Left Ctrl) or camera stick |
| `special` | `special` | `keyboard`, `menu` (Esc), `trsMenu` (Right Shift), `emoteWheel` (R, held), `chat` (T + keyboard), `hotbarSwipe` (tap = slot 1–9 by position, swipe follows the finger), `scrollUp`, `scrollDown` |

Gestures on the free area: in game, swipe = look around (3 mouse units per dp × sensitivity),
tap = left click (`tapAttack`) or right click, holding still for 300 ms = right button held (`holdUse`)
or left button held (break). In menus the cursor follows the finger, tap = left click, drag = left drag,
long press (450 ms) = right click, a second finger scrolls.

Built-in layouts: `pvp`, `build`, `redstone` (files next to `controls.rs`). The launcher writes them when
missing or when their `builtinRev` is older; saving one (launcher or in-game editor) drops `builtinRev`,
then updates leave it alone and “Reset” restores it.

Sharing: the file itself (`trs-controls-<name>.json`) or a code `TRSC1-` + base64url(deflate(JSON)), max 64 KiB.
