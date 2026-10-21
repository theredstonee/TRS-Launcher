// Bilder des Musikvideos: NASA-Fotos (public domain, /wait-song/*.webp, s. CREDITS.md) werden erst beim Öffnen
// geladen und einmal farblich an die Lila/Redstone-Palette angepasst (pro Pixel, damit es auch ohne ctx.filter geht).
// Dazu der Kopf des Fans aus seinem Skin und ein Planet (Kugel aus der Nachtkarte der Erde).
import { canvas, clamp, ctx2d, layer, rng, type Cv } from './kit'
import { FAN, astronaut, headFace, rocket, type Rocket } from './sprites'

export type PhotoId = 'nebulaA' | 'nebulaB' | 'limb' | 'plume' | 'stars'

const FILES: Record<PhotoId | 'earth', string> = {
  nebulaA: '/wait-song/nebula-a.webp',
  nebulaB: '/wait-song/nebula-b.webp',
  limb: '/wait-song/earth-limb.webp',
  plume: '/wait-song/launch-plume.webp',
  stars: '/wait-song/starfield.webp',
  earth: '/wait-song/earth-night.webp',
}

interface Grade {
  /** Farbton-Drehung in Grad. */
  hue: number
  sat: number
  con: number
  bri: number
  /** Tönung oben/unten (Hex) und Stärke. */
  top: string
  bottom: string
  amt: number
  maxW: number
}

const GRADES: Record<PhotoId, Grade> = {
  nebulaA: { hue: -8, sat: 1.15, con: 1.12, bri: 0.85, top: '#3a1d7a', bottom: '#8a1a24', amt: 0.28, maxW: 1600 },
  nebulaB: { hue: 150, sat: 0.95, con: 1.15, bri: 0.8, top: '#2a1660', bottom: '#7a1830', amt: 0.3, maxW: 1600 },
  limb: { hue: -28, sat: 1.1, con: 1.08, bri: 0.92, top: '#1a1040', bottom: '#b0303a', amt: 0.22, maxW: 1600 },
  plume: { hue: -8, sat: 1.15, con: 1.1, bri: 0.95, top: '#2a0a10', bottom: '#ff7a3a', amt: 0.12, maxW: 1200 },
  stars: { hue: 45, sat: 0.9, con: 1.2, bri: 0.7, top: '#140c30', bottom: '#2a0c22', amt: 0.3, maxW: 1600 },
}

const hex = (h: string) => {
  const n = Number.parseInt(h.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255] as const
}

/** Farbkorrektur in place (Farbmatrix + Verlaufstönung). */
export function gradePixels(data: Uint8ClampedArray, w: number, h: number, gr: Grade) {
  const a = (gr.hue * Math.PI) / 180
  const c = Math.cos(a)
  const s = Math.sin(a)
  // Farbton-Drehung (wie CSS hue-rotate) kombiniert mit Sättigung
  const hm = [
    0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928,
    0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.14, 0.072 - c * 0.072 - s * 0.283,
    0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072,
  ]
  const top = hex(gr.top)
  const bot = hex(gr.bottom)
  for (let y = 0; y < h; y++) {
    const f = y / Math.max(1, h - 1)
    const tr = top[0] + (bot[0] - top[0]) * f
    const tg = top[1] + (bot[1] - top[1]) * f
    const tb = top[2] + (bot[2] - top[2]) * f
    for (let x = 0; x < w; x++) {
      const i = (y * w + x) * 4
      let r = data[i]!
      let g = data[i + 1]!
      let b = data[i + 2]!
      const r1 = hm[0]! * r + hm[1]! * g + hm[2]! * b
      const g1 = hm[3]! * r + hm[4]! * g + hm[5]! * b
      const b1 = hm[6]! * r + hm[7]! * g + hm[8]! * b
      const l = 0.2126 * r1 + 0.7152 * g1 + 0.0722 * b1
      r = l + (r1 - l) * gr.sat
      g = l + (g1 - l) * gr.sat
      b = l + (b1 - l) * gr.sat
      r = ((r / 255 - 0.5) * gr.con + 0.5) * 255 * gr.bri
      g = ((g / 255 - 0.5) * gr.con + 0.5) * 255 * gr.bri
      b = ((b / 255 - 0.5) * gr.con + 0.5) * 255 * gr.bri
      // Tönung: Schatten färben (Soft-Light-artig)
      const k = gr.amt * (1 - Math.min(1, l / 255) * 0.7)
      data[i] = r + (tr - r) * k
      data[i + 1] = g + (tg - g) * k
      data[i + 2] = b + (tb - b) * k
    }
  }
}

function loadImage(src: string, cors = false): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    if (cors) {
      img.crossOrigin = 'anonymous'
      img.referrerPolicy = 'no-referrer'
    }
    img.decoding = 'async'
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error(`image ${src}`))
    img.src = src
  })
}

const idle = () => new Promise<void>((r) => setTimeout(r, 0))

export interface PlanetTex {
  w: number
  h: number
  px: Uint32Array
}

/**
 * Kugel-Renderer: Nachschlagetabelle je Pixel (Länge/Breite/Tag-Licht/Rand), pro Bild nur Textur-Lookups.
 * Licht kommt von rechts hinten: man sieht vor allem die Nachtseite mit den Stadtlichtern (= „Server-Lichter“)
 * und eine schmale beleuchtete Sichel. Ergebnis in `out` (N×N), wird weich skaliert gezeichnet.
 */
export class Sphere {
  readonly out: Cv
  private readonly g: CanvasRenderingContext2D
  private readonly img: ImageData
  private readonly buf: Uint32Array
  private readonly idx: Int32Array
  private readonly lon: Float32Array
  private readonly lat: Float32Array
  private readonly day: Float32Array
  private readonly rim: Float32Array
  /** Deckkraft am Rand (weiche Kante statt Treppe). */
  private readonly edge: Uint8Array
  constructor(readonly n: number, tilt = 0.32, light: [number, number, number] = [0.85, -0.35, -0.2]) {
    this.out = canvas(n, n)
    this.g = ctx2d(this.out)
    this.img = this.g.createImageData(n, n)
    this.buf = new Uint32Array(this.img.data.buffer)
    const ids: number[] = []
    const lon: number[] = []
    const lat: number[] = []
    const day: number[] = []
    const rim: number[] = []
    const edge: number[] = []
    const ll = Math.hypot(...light)
    const [lx, ly, lz] = light.map((v) => v / ll) as [number, number, number]
    const r = n / 2
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const dx = (x + 0.5 - r) / r
        const dy = (y + 0.5 - r) / r
        const d2 = dx * dx + dy * dy
        if (d2 > 1) continue
        edge.push(Math.round(clamp((1 - Math.sqrt(d2)) * r * 1.4, 0, 1) * 255))
        const dz = Math.sqrt(1 - d2)
        const yy = dy * Math.cos(tilt) - dz * Math.sin(tilt)
        const zz = dy * Math.sin(tilt) + dz * Math.cos(tilt)
        ids.push(y * n + x)
        lon.push(Math.atan2(dx, zz) / (2 * Math.PI) + 0.5)
        lat.push(Math.asin(clamp(yy, -1, 1)) / Math.PI + 0.5)
        const dif = dx * lx + dy * ly + dz * lz
        day.push(clamp(dif * 1.6 + 0.08, 0, 1))
        rim.push((1 - dz) ** 3)
      }
    }
    this.idx = Int32Array.from(ids)
    this.lon = Float32Array.from(lon)
    this.lat = Float32Array.from(lat)
    this.day = Float32Array.from(day)
    this.rim = Float32Array.from(rim)
    this.edge = Uint8Array.from(edge)
  }

  /** Zeichnet die Kugel mit Drehung `rot` (0…1 = eine Umdrehung). `lights` hebt die Stadtlichter (Puls). */
  render(tex: PlanetTex, rot: number, lights = 1) {
    const { buf, idx, lon, lat, day, rim, edge } = this
    buf.fill(0)
    const tw = tex.w
    const th = tex.h
    const px = tex.px
    for (let i = 0; i < idx.length; i++) {
      let u = lon[i]! + rot
      u -= Math.floor(u)
      const v = lat[i]!
      const c = px[((v * th) | 0) * tw + ((u * tw) | 0)]!
      const d = day[i]!
      const e = rim[i]!
      const night = 1 - d
      // Grundfarbe: Nachtseite dunkel, Sichel hell
      const k = 0.55 + d * 2.6
      let r = (c & 255) * k
      let g = ((c >>> 8) & 255) * k
      let b = ((c >>> 16) & 255) * k
      // Stadtlichter (nur Nacht) – warm, mit Redstone-Rot
      const L = ((c >>> 24) & 255) / 255 * lights * night
      r += L * 255 * 1.3
      g += L * 120
      b += L * 60
      // Server-Raster (Längen/Breiten) schwach rot auf der Nachtseite
      const gu = (u * 36) % 1
      const gv = (v * 18) % 1
      if (gu < 0.035 || gv < 0.05) r += 38 * night * lights
      // Atmosphäre am Rand
      r += e * (120 + d * 120)
      g += e * (40 + d * 70)
      b += e * (150 + d * 60)
      buf[idx[i]!] = (edge[i]! << 24) | (Math.min(255, b) << 16) | (Math.min(255, g) << 8) | Math.min(255, r)
    }
    this.g.putImageData(this.img, 0, 0)
    return this.out
  }
}

/** Planeten-Textur aus der Nachtkarte: Land/Meer dunkel lila, Städte → Licht (im Alpha-Byte). */
function planetFromImage(img: HTMLImageElement, w = 1024): PlanetTex {
  const h = w / 2
  const c = layer(w, h, (g) => {
    g.imageSmoothingEnabled = true
    g.drawImage(img, 0, 0, w, h)
  })
  const d = ctx2d(c).getImageData(0, 0, w, h).data
  const px = new Uint32Array(w * h)
  for (let i = 0; i < w * h; i++) {
    const r = d[i * 4]!
    const g = d[i * 4 + 1]!
    const b = d[i * 4 + 2]!
    const lum = (r * 0.3 + g * 0.55 + b * 0.15) / 255
    const warm = Math.max(0, (r + g) / 2 - b) / 255
    const light = clamp((warm * 3.2 + Math.max(0, lum - 0.3) * 2.2) - 0.04)
    const land = clamp((lum - 0.06) * 3)
    const br = 10 + land * 34
    const bg = 8 + land * 18
    const bb = 26 + land * 44
    px[i] = (Math.round(light * 255) << 24) | (Math.round(bb) << 16) | (Math.round(bg) << 8) | Math.round(br)
  }
  return { w, h, px }
}

/** Ersatz-Planet ohne Foto: Kontinente aus Rauschen + Lichterraster. */
function planetProcedural(w = 512): PlanetTex {
  const h = w / 2
  const px = new Uint32Array(w * h)
  const rand = rng(7)
  const blobs = Array.from({ length: 40 }, () => [rand() * w, rand() * h * 0.8 + h * 0.1, 20 + rand() * 60] as const)
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      let land = 0
      for (const [bx, by, br] of blobs) {
        const dx = Math.min(Math.abs(x - bx), w - Math.abs(x - bx))
        land = Math.max(land, 1 - Math.hypot(dx, (y - by) * 1.4) / br)
      }
      const isLand = land > 0.25
      const light = isLand && ((x * 7 + y * 13) % 23 === 0 || ((x % 16 === 0 || y % 16 === 0) && land > 0.5)) ? 0.9 : 0
      px[y * w + x] = (Math.round(light * 255) << 24) | ((isLand ? 96 : 50) << 16) | ((isLand ? 30 : 14) << 8) | (isLand ? 60 : 22)
    }
  }
  return { w, h, px }
}

/** Gesicht 8×8 (+ Hut-Ebene, falls durchsichtig) aus einem Minecraft-Skin. */
async function faceFromSkin(url: string): Promise<Cv | null> {
  const img = await loadImage(url, true)
  const k = img.naturalWidth / 64
  const out = layer(8, 8, (g) => {
    g.drawImage(img, 8 * k, 8 * k, 8 * k, 8 * k, 0, 0, 8, 8)
  })
  // Hut nur, wenn er Durchsichtiges enthält (wie Minecraft bei alten Skins)
  const hat = layer(8, 8, (g) => g.drawImage(img, 40 * k, 8 * k, 8 * k, 8 * k, 0, 0, 8, 8))
  const hd = ctx2d(hat).getImageData(0, 0, 8, 8).data
  let transparent = false
  for (let i = 3; i < hd.length; i += 4) if (hd[i]! < 255) transparent = true
  if (transparent) ctx2d(out).drawImage(hat, 0, 0)
  // Komplett durchsichtiges Gesicht = kein brauchbarer Skin
  const fd = ctx2d(out).getImageData(0, 0, 8, 8).data
  let opaque = 0
  for (let i = 3; i < fd.length; i += 4) if (fd[i]! > 200) opaque++
  return opaque > 40 ? out : null
}

const SKIN_URL = /^https:\/\/textures\.minecraft\.net\/texture\/[0-9a-f]{1,128}$/

export class Assets {
  photos: Partial<Record<PhotoId, Cv>> = {}
  planet: PlanetTex
  sphere: Sphere
  face: Cv
  astronaut: Cv
  rocket: Rocket
  /** Steigt bei jeder Änderung (Szenen bauen davon abhängige Ebenen neu). */
  version = 0
  private started = false

  constructor() {
    this.planet = planetProcedural()
    this.sphere = new Sphere(400)
    this.face = headFace(FAN)
    this.astronaut = astronaut(null)
    this.rocket = rocket(this.face)
  }

  /** Lädt alles im Hintergrund (Reihenfolge = Bedarf im Video). Wirft nie. */
  load(skin: string | null) {
    if (this.started) return
    this.started = true
    void (async () => {
      if (skin && SKIN_URL.test(skin)) {
        try {
          const face = await faceFromSkin(skin)
          if (face) this.setFace(face)
        } catch {
          // Lila Ersatzkopf bleibt
        }
      }
      for (const id of ['limb', 'nebulaA', 'nebulaB', 'plume', 'stars'] as PhotoId[]) {
        try {
          const img = await loadImage(FILES[id])
          await idle()
          this.photos[id] = this.grade(img, GRADES[id])
          this.version++
        } catch {
          // Ohne Foto zeichnen die Szenen ihren Verlauf
        }
        if (id === 'limb') {
          try {
            const img = await loadImage(FILES.earth)
            await idle()
            this.planet = planetFromImage(img)
            this.version++
          } catch {
            // Rauschen-Planet bleibt
          }
        }
      }
    })()
  }

  private setFace(face: Cv) {
    this.face = face
    this.astronaut = astronaut(face)
    this.rocket = rocket(face)
    this.version++
  }

  private grade(img: HTMLImageElement, gr: Grade): Cv {
    const k = Math.min(1, gr.maxW / img.naturalWidth)
    const w = Math.round(img.naturalWidth * k)
    const h = Math.round(img.naturalHeight * k)
    const c = canvas(w, h)
    const g = ctx2d(c)
    g.imageSmoothingEnabled = true
    g.drawImage(img, 0, 0, w, h)
    const data = g.getImageData(0, 0, w, h)
    gradePixels(data.data, w, h, gr)
    g.putImageData(data, 0, 0)
    return c
  }
}
