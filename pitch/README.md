# TRS Launcher – Showcase (pitch presentation)

Self-running booth loop and manual slideshow. Framework-free TypeScript, bundled with esbuild; it uses the
launcher's real start animation (`app/utils/intro/*`) on the first slide.

## Build

```sh
pnpm install                      # once (esbuild + Pixelify Sans come from node_modules)
node pitch/build.mjs <outDir>     # → <outDir>/index.html + <outDir>/assets/*
```

The output only uses relative paths and works from `file://`, from any http sub-path and in a Tauri webview.
It needs no network. It works under a strict CSP:
`default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; media-src 'self' blob:; font-src 'self' data:`
(no inline scripts, no eval; fonts and the logo are `data:` URLs, the QR code is inline SVG).

Media in `pitch/media/` are generated from the release screenshots and committed:
`python pitch/tools/prepare-media.py` (Pillow) and `python pitch/tools/make-qr.py` (segno).

## Query parameters

| Param | Values | Default | |
|---|---|---|---|
| `mode` | `loop`, `manual` | `loop` | loop = auto-advance forever (~3 min per cycle) |
| `lang` | `de`, `en` | `de` | |
| `demo` | `1` | – | kiosk build: "Try it yourself" call to action; any tap/key in loop mode emits the demo event |
| `calm` | `0` | calm on | `calm=0` plays the start animation with flicker and flashes |
| `sound` | `1` | off | start with sound (jingle of the start animation; toggle with M) |
| `slide` | `0…20` | `0` | start slide |

## Controls

Loop mode: any click/tap/key → manual mode (with `demo=1`: emits the demo event instead). F (fullscreen) and M (sound)
work in both modes. Manual mode: ← → / Space / Enter / PageUp / PageDown, click (left third = back), swipe,
Home/End, F fullscreen, Esc leaves fullscreen, P back to auto-play. The loop pauses while the tab is hidden.
`prefers-reduced-motion` switches to plain fades.

## Embedding API

```js
window.TRSPitch // { play(), pause(), goTo(i), next(), prev(), slides, current, mode }
window.addEventListener('trs-pitch:demo', () => { /* start the demo */ })
// inside an iframe additionally: parent.postMessage({ source: 'trs-pitch', type: 'demo' }, '*')
```

## Sources of the facts

README.md (features, platforms, loaders, versions), CHANGELOG.md (47 releases 0.1.0–0.27.1, social, hosting,
capes, creator programme, mobile), client-mod/README.md (client version ranges), client-mod/docs/benchmark.md
(FPS chart: 1.21.11 village scene, runs from one series), src-tauri/resources/client-mod/builds.json (105 builds),
src-tauri/crates/core/src/import/mod.rs (import sources), app/locales (8 languages), test counts by grep
(`#[test]` in src-tauri, `it(`/`test(` in *.test.ts). Competitor cells: vendor sites as of October 2026, unclear cells marked “?”; see the footnote on the slide.
Usage slide (`traction`): GitHub release download counts (installers + updates) and TRS API stats (accounts, active in
7 days, issues), read on 22 Oct 2026 – refresh them in `pitch/src/slides.ts` before each event.
