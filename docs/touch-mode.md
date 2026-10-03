# TRS Client touch mode: mod ↔ mobile engine / touch overlay

What the TRS Client mod does when it runs inside the TRS mobile game engine (Android/iOS), and what the engine and
the touch overlay can rely on. Code: `client-mod/common/src/main/java/dev/theredstonee/trsclient/core/touch/`
(+ `touch/TouchHooks` per loader tree).

**Desktop is unaffected.** Every touch code path starts with `TouchMode.enabled()`, which is only true with
`-Dtrs.touch=true`. Without it the mod behaves exactly as before (no scaling, no gesture handling, no files, no link
lines).

## 1. System properties (set by the engine)

| Property | Example | Meaning |
|---|---|---|
| `trs.touch` | `true` | Turns touch mode on (always set by the mobile plugin). |
| `trs.touchScale` | `1.5` | Optional. Enlargement of the TRS menus, 1–3, default 1.5. Lowered automatically so at least 320×200 GUI pixels stay usable. |
| `trs.safeInsets` | `88,0,88,24` | Optional. Safe-area insets **left, top, right, bottom in window pixels** (notch, camera hole, rounded corners, home indicator). Converted to GUI pixels with the current GUI scale (rounded up). Invalid values = no insets. |
| `trs.mobile`, `trs.overlay.version` | `android`, `1` | Read by nobody yet; reserved. |

## 2. Input the mod expects

The overlay sends a finger in menus as **left mouse button** (`sendMouseButton(0)` + `moveMouseAbsolute`). The mod
turns that into gestures inside TRS menus:

| Finger | Result |
|---|---|
| Tap | Click (fired on release, at the touch-down point) |
| Drag (> 5 GUI px) | Scrolls the list under the finger (mouse-wheel steps, 14 GUI px per step) with momentum after release |
| Long press (450 ms) | Right click (context menu); if nothing takes it, the tooltip shows while the finger stays down |
| Sliders, colour pickers, rotating previews, HUD editor, emote wheel, map, skin editor, screenshot editor | Raw: press/drag/release go straight through |

There are no hover-only tooltips: TRS menus only "see" a pointer while a finger is down. Other mouse buttons and a
real mouse wheel pass through unchanged. Vanilla screens are not touched (the overlay's own menu handling applies).

## 3. Fixed keys for overlay buttons

Pressed by the overlay with `sendKey` (GLFW codes; on LWJGL 2 versions the engine translates them). Only active in
touch mode, not shown in the controls menu, not rebindable, only when no other screen is open.

| Overlay action (`special`) | Key | GLFW | LWJGL 2 | Opens |
|---|---|---|---|---|
| `trsMenu` | F13 | 302 | 100 | TRS menu |
| `emoteWheel` | F14 | 303 | 101 | Emote wheel (only in a world, Forge 1.8.9+ / Fabric / NeoForge; not 1.7.10 / 1.13.2) |
| – (e.g. editor button) | F15 | 304 | 102 | HUD editor |

The overlay sends a short press (down + up). The emote wheel then **stays open**: the player puts a finger on the
wheel, slides to an emote and lets go to play it (a tap on an emote works too). Letting go in the middle or outside
closes it; a locked emote shows a hint and keeps the wheel open.

## 4. On-screen keyboard

When a text field gets focus – any TRS text field (search, chat, notes, names …) or vanilla chat, sign, anvil, book
editing (and on 1.14+ any focused vanilla edit box) – the mod asks for the keyboard; when none is focused for two
ticks it asks to hide it. Both ways below are used; showing twice is harmless.

### 4a. TRS Link (preferred)

Capability negotiation in the existing handshake (`src-tauri/crates/core/src/link/`):

| Direction | Where | Value |
|---|---|---|
| launcher → game | `challenge.features` | contains `"keyboard"` (launcher/engine handles keyboard lines) |
| game → launcher | `auth.features` | contains `"keyboard"` when the game runs in touch mode |

Game → launcher lines (only sent when the launcher advertised `"keyboard"`):

```json
{"type":"keyboard.show","field":"chat"}
{"type":"keyboard.hide"}
```

`field` is one of `text`, `multiline`, `chat`, `sign`, `anvil`, `book` (lets the engine pick an IME action such as
"Send"). No reply is expected. The launcher maps them to `GameInput.showKeyboard(true|false)`. A line is sent only
on change; after a reconnect the next change is sent.

### 4b. Fallback file (poll)

Always written in touch mode, atomically replaced on every change:

`<gameDir>/config/trsclient/touch-state.json`

```json
{"version":1,"keyboard":true,"field":"chat","seq":7}
```

`seq` increases with every change (starts at 1 per game start). The overlay polls the file (e.g. every 100–200 ms)
and calls `showKeyboard` when `seq` changed. Additionally the mod prints one log line per change
(`[TRS-Touch] keyboard.show chat` / `[TRS-Touch] keyboard.hide`) for engines that already watch stdout.

## 5. HUD editor and the "Touch layout"

- Elements are dragged by finger with a larger grab area (8 GUI px around each element), corner handles and a snap
  distance of 10 GUI px. Edges snap to the **safe area** (from `trs.safeInsets`) instead of the screen edges; the
  unsafe margins are shaded. The editor's top bar has finger-sized buttons and starts below the top inset.
- **Touch layout** button (touch mode only): switches to (or creates) the HUD profile `Touch-Layout` and moves every
  active element, starting from its default position, to the nearest spot outside the overlay's default button
  zones and inside the safe area.

Default button zones the overlay's shipped layouts must stay inside (fractions of the screen, `x1,y1 → x2,y2`):

| Zone | x1 | y1 | x2 | y2 |
|---|---|---|---|---|
| Joystick (bottom left) | 0.00 | 0.50 | 0.32 | 1.00 |
| Action buttons (bottom right) | 0.68 | 0.45 | 1.00 | 1.00 |
| Top-left bar (pause/menu) | 0.00 | 0.00 | 0.10 | 0.14 |
| Top-right bar (chat, emote, keyboard, TRS menu) | 0.80 | 0.00 | 1.00 | 0.14 |
| Vanilla hotbar (bottom centre) | 0.30 | 0.86 | 0.70 | 1.00 |

(`TouchLayout.ZONES`; change both sides together.)

## 6. Menu scaling

TRS menus are drawn with the touch scale (section 1) – fonts, buttons, toggles and sliders grow together. The HUD
editor and the world map are not scaled (they show real screen positions). Scissor rectangles are converted so they
are correct on every Minecraft version.
