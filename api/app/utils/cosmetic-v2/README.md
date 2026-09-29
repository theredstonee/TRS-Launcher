# Kosmetik v2 – gemeinsamer TS-Renderer

TypeScript-Port der TRS-Studio-Referenz (`trs-studio/public/cosmetic-format.mjs` + `cosmetic-view.mjs`, Ansicht/Licht
aus `workbench.js`). Website (`/cosmetics`) und Launcher (Skins-Seite) nutzen **dieselben Dateien** – bitte nur
gemeinsam ändern. Format-Beschreibung: `api/API.md` §11.9 bzw. `trs-studio/cosmetic-format.md`.

Tests (`api/tests/cosmetic-v2-format.test.ts`) prüfen Mathematik, Animation, Prüfung und den three.js-Aufbau; liegt die
Studio-Referenz unter `E:/ai/trs-studio/public/`, vergleichen sie Posen, Matrizen, Geometrie, UVs, Höfe und
Sichtbarkeit aller mitgelieferten Modelle **1:1 mit der Werkbank** (pixelgleiche Optik).

## Dateien

| Datei | Inhalt | Abhängigkeiten |
|---|---|---|
| `format.ts` | Typen, `validateModel`/`parseModel`, 4×4-Mathematik, Flächen/UVs, Knochen, Animation, Frames, Höfe | keine (läuft auch auf dem Server) |
| `view.ts` | three.js-Aufbau + skinview3d-Hilfen, Laden | `three` (nur Typen, das Modul wird hineingereicht), `skinview3d` (nur Typen), DOM (`document`, `Image`, `fetch`) |

Kein Nuxt, keine Auto-Imports, keine Vue-Abhängigkeit. Übernahme in den Launcher: beide Dateien nach
`app/utils/cosmetic-v2/` kopieren (dort ggf. `three@0.156.1` + `@types/three@0.156.0` ergänzen – gleiche Version wie in
skinview3d 3.4.2). Import immer mit Pfad (`~/utils/cosmetic-v2/view`), **keine** `index.ts` anlegen (sonst importiert
Nuxt alle Namen automatisch).

## API (`view.ts`)

```ts
import * as THREE from 'three'               // oder: const THREE = await import('three')
import { SkinViewer } from 'skinview3d'
import { loadCosmetic, mountOnSkinViewer, frameHead } from '~/utils/cosmetic-v2/view'

const viewer = new SkinViewer({ canvas, width, height, fov: 32 })            // fov 32 wie die Werkbank
const loaded = await loadCosmetic({ model: item.model, texture: textureUrl, glow: item.glow }) // textureUrl = texture.url (Katalog) bzw. texture (Website)
const mounted = mountOnSkinViewer(THREE, viewer, loaded, { animate: true, glow: true, night: false })
frameHead(THREE, viewer, loaded.model, 'three')                             // Kamera wie die Werkbank (optional)
mounted.set({ night: true })                                               // Tag/Nacht, Animation, Leuchten
mounted.dispose()                                                          // abnehmen (Viewer bleibt)
```

| Export | Zweck |
|---|---|
| `loadCosmetic({ model, texture, glow? }, deps?)` | Lädt `model.json` + Bilder, prüft Modell **und Bildmaße** (`parseModel`), wirft bei Fehlern. `deps.fetchJson` / `deps.loadImg` ersetzbar (z. B. Tauri-Kern, Data-URLs). |
| `createCosmetic(THREE, model, images)` | Baut die Szene (Knochen-Gruppen, Meshes je Knochen mit Material-Gruppen cutout/emissive/translucent, Glow-Overlay additiv, Höfe als Sprites). Gibt `{ root, model, update(t, opts), setLight(f), highlight(id), dispose() }`. `root` an den Kopf hängen (Ursprung = Nacken). |
| `update(timeMs, { animate, glow, camera, drivers })` | Vor jedem Bild: Pose zur **Wanduhr**, Glow-/Textur-Frame, Höfe (zur Kamera schieben, Puls, Blickrichtung). |
| `mountOnSkinViewer(THREE, viewer, loaded, opts)` | Hängt an `viewer.playerObject.skin.head`, eigene rAF-Schleife wie die Werkbank. `set({ animate, glow, night, fixedTime })`, `dispose()`. Animation aus = Ruhepose, Leuchten steht. |
| `applyDayNight(viewer, cos, night)` | Werkbank-Licht: Nacht = Umgebungslicht 3 × 0,18, Kameralicht 0,6 × 0,2, Modell-Licht 0,2 (emissive + Leuchten bleiben voll). |
| `frameHead(THREE, viewer, model, view = 'three', zoom = 1)` | Kamera auf den Kopf (Ziel Nacken + 8 + Anhebung nach Modellhöhe, Abstand 34 · (1 + Anhebung/9)); `VIEWS` = front/three/side/back/top. |

`format.ts` exportiert u. a. `validateModel`, `parseModel`, `samplePoses`, `boneLocal`, `boneMatrices`, `faceCorners`,
`faceUvCorners`, `frameAt`, `haloIntensity`, `haloFacing`, `haloFalloff`, `rotationZYX`, `LIMITS` und alle Modell-Typen
(`CosmeticModel`, `Bone`, `Cube`, `Face`, `Animation`, `Halo`, …).

## Regeln, die gleich bleiben müssen

- Flächen beidseitig; `cutout` = `alphaTest 0.5`; `emissive` = gleiches Material ohne Licht-Faktor; `translucent` =
  transparent ohne Tiefe schreiben. Texturen `NearestFilter`, **keine Mipmaps**, `SRGBColorSpace`.
- Glow: `AdditiveBlending`, `depthWrite: false`, `polygonOffset -1/-2`, `toneMapped: false`, `renderOrder 2`.
- Höfe: Sprite 64×64-Verlauf `(1 − r)^2.5`, additiv, `renderOrder 3`, um `size/2` zur Kamera geschoben.
- Zeit = `Date.now()` (Wanduhr), damit Website, Launcher und Spiel dieselbe Phase zeigen.
- CSP: alles same-origin (Bilder/JSON von der eigenen API), keine Blob-/Daten-URLs nötig.
