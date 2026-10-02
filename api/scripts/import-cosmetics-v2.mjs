// Übernimmt die freigegebenen v2-Kosmetik-Exporte aus dem TRS Studio.
// Öffentliche Teile → api/assets/cosmetics/v2/. Code-/Team-Teile → PRIVATE_ASSETS_DIR/cosmetics/v2/.
//
//   node api/scripts/import-cosmetics-v2.mjs [<studio-ordner>] [--private <ordner>]
//   Standard: Studio E:/ai/trs-studio, privat PRIVATE_ASSETS_DIR oder E:/ai/trs-private-assets
//
// Kopiert 1:1 (nicht verändern – vom User abgenommen): exports/cosmetic-v2/<id>.json, <id>.png, <id>-glow.png.
// Karten: cosmetics/<id>/preview-three.png → <id>-card.png, preview-three-night.png → <id>-card-night.png,
// auf höchstens 512 px verkleinert (Flächenmittel mit vormultipliziertem Alpha) und kompakt neu kodiert.
// catalog.json wird NICHT angefasst (Einträge mit "format": 2 von Hand pflegen, API.md §11.9).

import { copyFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PNG } from 'pngjs'

const IDS = ['redstone_crown', 'team_crown', 'trs_cap', 'lamp_helmet', 'top_hat', 'halo', 'witch_hat', 'pumpkin_head', 'bat_buddy']
const PRIVATE_IDS = new Set(['redstone_crown', 'team_crown', 'halo'])
const MAX_CARD = 512

function args() {
  let studio = null
  let priv = process.env.PRIVATE_ASSETS_DIR || 'E:/ai/trs-private-assets'
  const argv = process.argv.slice(2)
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--private') {
      priv = argv[++i]
      if (!priv) throw new Error('--private needs a directory')
    } else if (argv[i].startsWith('-')) {
      throw new Error(`unknown option ${argv[i]}`)
    } else if (studio) {
      throw new Error(`unexpected argument ${argv[i]}`)
    } else {
      studio = argv[i]
    }
  }
  return { studio: studio ?? 'E:/ai/trs-studio', priv }
}

const { studio: STUDIO, priv: PRIV } = args()
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', 'assets', 'cosmetics', 'v2')
const PRIVATE_OUT = join(PRIV, 'cosmetics', 'v2')
mkdirSync(OUT, { recursive: true })
mkdirSync(PRIVATE_OUT, { recursive: true })

/** Flächenmittel-Verkleinerung (jedes Ziel-Pixel = gewichteter Mittelwert der überdeckten Quell-Pixel). */
function downscale(src, dw, dh) {
  const { width: sw, height: sh, data } = src
  const out = new PNG({ width: dw, height: dh })
  const fx = sw / dw
  const fy = sh / dh
  for (let y = 0; y < dh; y++) {
    const y0 = y * fy
    const y1 = y0 + fy
    for (let x = 0; x < dw; x++) {
      const x0 = x * fx
      const x1 = x0 + fx
      let r = 0
      let g = 0
      let b = 0
      let a = 0
      let wsum = 0
      for (let sy = Math.floor(y0); sy < Math.ceil(y1); sy++) {
        const wy = Math.min(y1, sy + 1) - Math.max(y0, sy)
        for (let sx = Math.floor(x0); sx < Math.ceil(x1); sx++) {
          const w = wy * (Math.min(x1, sx + 1) - Math.max(x0, sx))
          const i = (sy * sw + sx) * 4
          const al = data[i + 3] / 255
          r += data[i] * al * w
          g += data[i + 1] * al * w
          b += data[i + 2] * al * w
          a += al * w
          wsum += w
        }
      }
      const o = (y * dw + x) * 4
      if (a > 0) {
        out.data[o] = Math.round(r / a)
        out.data[o + 1] = Math.round(g / a)
        out.data[o + 2] = Math.round(b / a)
      }
      out.data[o + 3] = Math.round((a / wsum) * 255)
    }
  }
  return out
}

function card(src, dest) {
  const png = PNG.sync.read(readFileSync(src))
  const k = Math.min(1, MAX_CARD / Math.max(png.width, png.height))
  const img = k < 1 ? downscale(png, Math.round(png.width * k), Math.round(png.height * k)) : png
  // Voll deckend? Dann ohne Alpha-Kanal speichern (kleiner).
  let opaque = true
  for (let i = 3; i < img.data.length; i += 4) if (img.data[i] !== 255) { opaque = false; break }
  const buf = PNG.sync.write(img, { colorType: opaque ? 2 : 6, deflateLevel: 9, filterType: -1 })
  writeFileSync(dest, buf)
  return `${img.width}×${img.height} ${(buf.length / 1024).toFixed(0)} KB`
}

for (const id of IDS) {
  const dest = PRIVATE_IDS.has(id) ? PRIVATE_OUT : OUT
  const exp = join(STUDIO, 'exports', 'cosmetic-v2')
  for (const f of [`${id}.json`, `${id}.png`, `${id}-glow.png`]) {
    const from = join(exp, f)
    if (!existsSync(from)) {
      if (f.endsWith('-glow.png')) continue
      throw new Error(`missing ${from}`)
    }
    copyFileSync(from, join(dest, f))
  }
  const dir = join(STUDIO, 'cosmetics', id)
  const day = card(join(dir, 'preview-three.png'), join(dest, `${id}-card.png`))
  const night = card(join(dir, 'preview-three-night.png'), join(dest, `${id}-card-night.png`))
  console.log(`${id}: card ${day}, night ${night} → ${dest}`)
}
console.log(`öffentlich → ${OUT}`)
console.log(`privat → ${PRIVATE_OUT}`)
