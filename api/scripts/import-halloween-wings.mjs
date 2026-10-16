// Halloween 2026 (API.md §36): übernimmt die vier Kürbisjäger-Flügel (Format v2, Platz `wings`, attach `body`) in die
// öffentlichen Assets und trägt sie in assets/cosmetics/catalog.json ein (unlock „reward“ / pumpkinhunter2026).
//
//   node api/scripts/import-halloween-wings.mjs [<ordner>]      Standard: F:/trs-wt/halloween26-wings
//
// Kopiert 1:1: <id>.json, <id>.png, <id>-glow.png (falls im Modell), <id>-card.png, <id>-card-night.png.
// Frames/Leucht-Frames kommen aus dem Modell. Fehlende Flügel werden übersprungen (Warnung), vorhandene Einträge ersetzt.
// Proprietäre Teile (redstone_wings) gehören NICHT hierher, sondern nach PRIVATE_ASSETS_DIR/cosmetics/v2.

import { copyFileSync, existsSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { HALLOWEEN_WINGS, HUNTER_REWARD } from '../shared/halloween.ts'

const SRC = process.argv[2] ?? 'F:/trs-wt/halloween26-wings'
const ASSETS = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets', 'cosmetics')
const OUT = join(ASSETS, 'v2')

const catalogFile = join(ASSETS, 'catalog.json')
const raw = JSON.parse(readFileSync(catalogFile, 'utf8'))
const list = Array.isArray(raw) ? raw : raw.cosmetics
let done = 0
for (const w of HALLOWEEN_WINGS) {
  const modelFile = join(SRC, `${w.id}.json`)
  if (!existsSync(modelFile)) {
    console.warn(`skip ${w.id}: ${modelFile} missing`)
    continue
  }
  const model = JSON.parse(readFileSync(modelFile, 'utf8'))
  if (model.slot !== 'wings' || model.attach !== 'body') throw new Error(`${w.id}: expected slot wings / attach body`)
  const files = [`${w.id}.json`, `${w.id}.png`, `${w.id}-card.png`, `${w.id}-card-night.png`]
  if (model.glow) files.push(`${w.id}-glow.png`)
  const missing = files.filter((f) => !existsSync(join(SRC, f)))
  if (missing.length) {
    console.warn(`skip ${w.id}: missing ${missing.join(', ')}`)
    continue
  }
  for (const f of files) copyFileSync(join(SRC, f), join(OUT, f))
  const frames = model.texture.frames ?? 1
  const glowFrames = model.glow ? (model.glow.frames ?? 1) : 0
  const entry = {
    id: w.id,
    name: w.name.de,
    format: 2,
    unlock: { type: 'reward', reward: HUNTER_REWARD },
    frames,
    ...(frames > 1 ? { frameTimeMs: model.texture.frameTimeMs } : {}),
    glowFrames,
    ...(glowFrames > 1 ? { glowFrameTimeMs: model.glow.frameTimeMs } : {}),
  }
  const at = list.findIndex((e) => e.id === w.id)
  if (at >= 0) list[at] = entry
  else list.push(entry)
  done++
}
writeFileSync(catalogFile, `${JSON.stringify(Array.isArray(raw) ? list : { ...raw, cosmetics: list }, null, 2)}\n`)
console.log(`${done} of ${HALLOWEEN_WINGS.length} Halloween wings imported`)
