// Halloween 2026 „Nacht der langen Schatten“: Pixel-Mondnacht (320×180) aus dem freigegebenen Entwurf, Stufe „Horror“
// (Blutmond, Blitze mit Schattengestalt, rote Augen, Zombie-Hände, Schädel). Zahlen 1:1 aus dem Entwurf (makeScene).
// Läuft nur im Browser (Canvas). `frame(t)` zeichnet den Zustand zur Zeit t (Sekunden); bei „Bewegung reduzieren“
// zeichnet der Aufrufer einmal `frame(4)` als Standbild.

import { SPR, bay, hex, mix, rng, type SpriteName } from '#shared/halloween-art'

function layer(w: number, h: number): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  return c
}

function ctx2d(c: HTMLCanvasElement): CanvasRenderingContext2D {
  const g = c.getContext('2d')
  if (!g) throw new Error('canvas 2d unavailable')
  return g
}

/** Sprite in eine Leinwand (Größe = Sprite) zeichnen. */
export function drawSprite(cv: HTMLCanvasElement, name: SpriteName): void {
  const s = SPR[name] as { pal: Record<string, string>, m: readonly string[] }
  cv.width = s.m[0]!.length
  cv.height = s.m.length
  const g = ctx2d(cv)
  g.clearRect(0, 0, cv.width, cv.height)
  s.m.forEach((row, y) => [...row].forEach((ch, x) => {
    const c = s.pal[ch]
    if (c) {
      g.fillStyle = c
      g.fillRect(x, y, 1, 1)
    }
  }))
}

/** Mondphase in einer 13×13-Leinwand: 0 = Neumond, 1 = Vollmond (Mondlauf-Balken). */
export function moonPhase(cv: HTMLCanvasElement, f: number): void {
  if (cv.width !== 13) {
    cv.width = 13
    cv.height = 13
  }
  const g = ctx2d(cv)
  g.clearRect(0, 0, 13, 13)
  for (let y = 0; y < 13; y++) for (let x = 0; x < 13; x++) {
    const u = (x - 6) / 6, v = (y - 6) / 6
    if (u * u + v * v > 1.05) continue
    const term = (1 - 2 * f) * Math.sqrt(Math.max(0, 1 - v * v)), lit = u > term
    g.fillStyle = lit ? (u - term < 0.25 ? '#b9bfd0' : '#eef1f8') : '#2a2342'
    g.fillRect(x, y, 1, 1)
  }
}

const WITCH = ['.......k........', '......kk........', '.....kkk........', '...kkkkkkk......', '.....kkk........', '....kkkkk.......', '...kkkkkkk......', 'kkkkkkkkkkkkkkkk', 'kkkk..k..k......']
const JACK = ['....g....', '...g.....', '.oOOoOOo.', 'oOyOoOyOo', 'oOyyoyyOo', 'oOOOoOOOo', 'oyOyoyOyo', '.oyyyyyo.', '..ooooo..']
const JP: Record<string, string> = { o: '#7a3512', O: '#c4651f', y: '#ffd27a', g: '#4d7a45' }
const GHOST = ['.www.', 'wwwww', 'wkwkw', 'wwwww', 'wwkww', 'wwwww', 'w.w.w']

export interface SceneOptions {
  /** Mond-Position (Anteil der Breite), Standard 0.74. */
  moonX?: number
  /** Blitz: wird beim Einschlag aufgerufen (Seiten-Aufflackern, Handabdrücke). */
  onBolt?: () => void
  /** „Flackern & Rauschen“: aus = kein helles Aufleuchten der Szene beim Blitz. */
  flicker?: () => boolean
}

export interface SceneInfo {
  /** Gräber in Szenen-Koordinaten (für den versteckten Kürbis „zwischen den Gräbern“). */
  graves: { x: number, y: number }[]
  width: number
  height: number
}

/** Baut die Szene in `cv` (Größe = Pixel-Auflösung, z. B. 320×180) und gibt die Zeichenfunktion zurück. */
export function makeScene(cv: HTMLCanvasElement, seed: number, opts: SceneOptions = {}): { frame: (t: number) => void, info: SceneInfo } {
  const W = cv.width, H = cv.height, R = rng(seed), ctx = ctx2d(cv)
  const moonX = opts.moonX ?? 0.74
  const mx = Math.round(W * moonX), my = Math.round(H * 0.24), mr = Math.round(H * 0.11)
  // Himmel mit Bayer-Verlauf und Mondhof
  const sky = layer(W, H), sg = ctx2d(sky), im = sg.createImageData(W, H)
  const stops = ([[0, '#06050b'], [0.42, '#110c20'], [0.72, '#21173a'], [1, '#2e2348']] as const).map(([t, c]) => [t, hex(c)] as const)
  const halo = hex('#4a406e')
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const t = y / H
    let i = 1
    while (i < stops.length - 1 && t > stops[i]![0]) i++
    const a = stops[i - 1]!, b = stops[i]!, k = Math.min(1, Math.max(0, (t - a[0]) / (b[0] - a[0])))
    let col = mix(a[1], b[1], Math.floor(k * 4 + bay(x, y)) / 4)
    const d = Math.hypot(x - mx, y - my)
    if (d < mr * 2.8) {
      const inten = Math.max(0, 1 - (d - mr) / (mr * 1.8)) ** 2
      col = mix(col, halo, (Math.floor(inten * 3 + bay(x, y)) / 3) * 0.6)
    }
    const o = (y * W + x) * 4
    im.data[o] = col[0]
    im.data[o + 1] = col[1]
    im.data[o + 2] = col[2]
    im.data[o + 3] = 255
  }
  sg.putImageData(im, 0, 0)
  const stars: { x: number, y: number, p: number, s: number, b: number }[] = []
  for (let i = 0; i < W * 0.28; i++) {
    const x = Math.floor(R() * W), y = Math.floor(R() * H * 0.62)
    if (Math.hypot(x - mx, y - my) < mr * 2.4) continue
    stars.push({ x, y, p: R() * 6.28, s: 0.6 + R() * 2.2, b: R() })
  }
  // Mond (nur für die Krater – gezeigt wird in „Horror“ der Blutmond)
  const craters = Array.from({ length: 6 }, () => ({ x: (R() - 0.5) * mr * 1.2, y: (R() - 0.5) * mr * 1.2, r: 1 + R() * mr * 0.22 }))
  const blood = layer(W, H), bl = ctx2d(blood), RR = Math.round(mr * 2.8)
  for (let y = -RR; y <= RR; y++) for (let x = -RR; x <= RR; x++) {
    const d = Math.hypot(x, y)
    if (x * x + y * y <= mr * mr + mr * 0.6) {
      const n = (x * 0.6 + y * 0.45) / mr
      let c = n > 0.42 + bay(x + 64, y + 64) * 0.18 ? '#9c2f26' : '#d8553b'
      if (craters.some((q) => (x - q.x) ** 2 + (y - q.y) ** 2 < q.r * q.r)) c = n > 0.42 ? '#7e231d' : '#b8432f'
      bl.fillStyle = c
      bl.fillRect(mx + x, my + y, 1, 1)
    } else {
      const inten = Math.max(0, 1 - (d - mr) / (mr * 1.8)) ** 2
      if (bay(x + 64, y + 64) < inten * 0.75) {
        bl.fillStyle = 'rgba(150,32,36,.42)'
        bl.fillRect(mx + x, my + y, 1, 1)
      }
    }
  }
  // Ferne Hügel + Herrenhaus
  const far = layer(W, H), fg1 = ctx2d(far)
  fg1.fillStyle = '#1e1638'
  const hill = (x: number) => Math.round((H * 0.70 + Math.sin(x * 0.021 + seed) * H * 0.035 + Math.sin(x * 0.057) * H * 0.014) / 2) * 2
  for (let x = 0; x < W; x++) fg1.fillRect(x, hill(x), 1, H)
  const hx = Math.round(W * 0.2), hb = hill(hx + 14)
  fg1.fillStyle = '#17112d'
  fg1.fillRect(hx, hb - 14, 28, 16)
  fg1.fillRect(hx - 3, hb - 24, 7, 26)
  fg1.fillRect(hx + 24, hb - 20, 7, 22)
  fg1.fillRect(hx + 11, hb - 19, 6, 6)
  const roof = (cx: number, top: number, n: number) => {
    for (let i = 0; i < n; i++) fg1.fillRect(cx - Math.floor(i / 2), top + i, 1 + Math.floor(i / 2) * 2, 1)
  }
  roof(hx, hb - 32, 9)
  roof(hx + 27, hb - 27, 8)
  roof(hx + 14, hb - 25, 7)
  fg1.fillStyle = '#2a2140'
  for (const [x, y] of [[hx + 4, hb - 10], [hx + 9, hb - 10], [hx + 19, hb - 10], [hx + 26, hb - 15], [hx - 1, hb - 12]] as const) fg1.fillRect(x, y, 1, 2)
  const lits = [{ x: hx, y: hb - 19, p: 0 }, { x: hx + 9, y: hb - 10, p: 1.3 }, { x: hx + 26, y: hb - 15, p: 2.1 }, { x: hx + 19, y: hb - 6, p: 3.7 }, { x: hx + 14, y: hb - 17, p: 4.4 }]
  // Mitte: Boden, Bäume, Zaun, Gräber
  const mid = layer(W, H), m = ctx2d(mid)
  const ground = (x: number) => Math.round(H * 0.83 + Math.sin(x * 0.03 + 2) * H * 0.02)
  m.fillStyle = '#120e20'
  for (let x = 0; x < W; x++) m.fillRect(x, ground(x), 1, H)
  m.fillStyle = '#0d0a17'
  const tips: { x: number, y: number }[] = []
  const tree = (x: number, y: number, len: number, ang: number, d: number): void => {
    if (d === 2 && len > 4) tips.push({ x: Math.round(x + Math.cos(ang) * len), y: Math.round(y - Math.sin(ang) * len) })
    const ex = x + Math.cos(ang) * len, ey = y - Math.sin(ang) * len
    for (let t = 0; t <= len; t++) m.fillRect(Math.round(x + (ex - x) * t / len), Math.round(y + (ey - y) * t / len), d > 3 ? 2 : 1, 1)
    if (d > 0) {
      tree(ex, ey, len * (0.62 + R() * 0.15), ang + 0.35 + R() * 0.35, d - 1)
      tree(ex, ey, len * (0.6 + R() * 0.15), ang - 0.35 - R() * 0.35, d - 1)
      if (R() < 0.4) tree(ex, ey, len * 0.5, ang + (R() - 0.5) * 0.4, d - 2)
    }
  }
  for (const [fx, s] of [[0.06, 1], [0.37, 0.8], [0.93, 1.1]] as const) {
    const x = Math.round(W * fx)
    tree(x, ground(x), H * 0.11 * s, Math.PI / 2 + (R() - 0.5) * 0.2, 6)
  }
  const fence0 = Math.round(W * 0.45), fence1 = Math.round(W * 0.66)
  for (let x = fence0; x < fence1; x += 4) {
    const g = ground(x)
    m.fillRect(x, g - 9, 1, 9)
    m.fillRect(x, g - 10, 1, 1)
  }
  for (let x = fence0; x < fence1; x++) {
    const g = ground(x)
    m.fillRect(x, g - 7, 1, 1)
    m.fillRect(x, g - 3, 1, 1)
  }
  const graves: { x: number, y: number, cx: number, cy: number }[] = []
  ;[0.5, 0.56, 0.62, 0.74, 0.8].forEach((fx, i) => {
    const x = Math.round(W * fx) + (i % 2 ? 1 : 0), g = ground(x) + 3, h = 6 + (i % 3) * 2, w = 5 + (i % 2)
    m.fillStyle = '#1c1730'
    m.fillRect(x, g - h, w, h)
    m.fillRect(x + 1, g - h - 1, w - 2, 1)
    m.fillStyle = '#2d2648'
    m.fillRect(x + w - 1, g - h + 1, 1, h - 2)
    graves.push({ x: x + w / 2, y: g - h, cx: x + w + 1, cy: g - 3 })
    m.fillStyle = '#d9d2c0'
    m.fillRect(x + w + 1, g - 3, 1, 3)
  })
  // Vordergrund + Kürbisse
  const fore = layer(W, H), f = ctx2d(fore)
  f.fillStyle = '#08070e'
  for (let x = 0; x < W; x++) {
    const g = Math.round(H * 0.93 + Math.sin(x * 0.05) * 2)
    f.fillRect(x, g, 1, H)
    if (R() < 0.25) f.fillRect(x, g - 1 - Math.floor(R() * 2), 1, 2)
  }
  const pumpkins = [0.06, 0.16, 0.29, 0.53, 0.66, 0.79, 0.9].map((fx, i) => ({ x: Math.round(W * fx), y: Math.round(H * (0.935 + (i % 2) * 0.012)) - 8, p: R() * 9, big: i % 3 === 0 }))
  for (let y = Math.round(H * 0.86); y < H; y++) for (let x = 0; x < W; x++) {
    const k = (y - H * 0.86) / (H * 0.14)
    if (bay(x, y) < k * 0.18) {
      f.fillStyle = 'rgba(224,138,60,.10)'
      f.fillRect(x, y, 1, 1)
    }
  }
  for (const { x, y, big } of pumpkins) JACK.forEach((row, ry) => [...row].forEach((ch, rx) => {
    const c = JP[ch]
    if (c) {
      f.fillStyle = c
      f.fillRect(x + rx, y + ry, 1, 1)
      if (big && ry > 1) f.fillRect(x + rx, y + ry, 1, 1)
    }
  }))
  const glow = layer(36, 36), gg = ctx2d(glow)
  for (let y = 0; y < 36; y++) for (let x = 0; x < 36; x++) {
    const d = Math.hypot(x - 18, y - 18) / 18
    if (d < 1 && bay(x, y) < (1 - d) ** 1.4) {
      gg.fillStyle = 'rgba(232,140,58,.26)'
      gg.fillRect(x, y, 1, 1)
    }
  }
  // Spinnennetz in der linken oberen Ecke
  const web = layer(W, H), wg = ctx2d(web)
  wg.fillStyle = 'rgba(207,213,227,.22)'
  const WR = Math.round(H * 0.22)
  for (let a = 0; a <= 4; a++) {
    const ang = (a / 4) * Math.PI / 2
    for (let r = 0; r < WR; r++) wg.fillRect(Math.round(Math.cos(ang) * r), Math.round(Math.sin(ang) * r), 1, 1)
  }
  for (let ring = 1; ring <= 4; ring++) {
    const rr = ring * WR / 4.4
    for (let a = 0; a < 90; a += 1.5) {
      const ang = a * Math.PI / 180, sag = Math.sin((a % 22.5) / 22.5 * Math.PI) * ring * 0.8
      wg.fillRect(Math.round(Math.cos(ang) * (rr - sag)), Math.round(Math.sin(ang) * (rr - sag)), 1, 1)
    }
  }
  const ghosts = [{ x: W * 0.55, y: H * 0.74, v: 3.2, p: 0 }, { x: W * 0.2, y: H * 0.8, v: -2.4, p: 2 }]
  const ravens = tips.sort(() => R() - 0.5).slice(0, 3).map((q) => ({ ...q, p: R() * 9 }))
  const spider = { x: Math.round(W * 0.44), len: H * 0.18, p: R() * 6 }
  // Nebelbänder (nahtlos kachelbar)
  const fogBand = (fh: number, k1: number, k2: number, k3: number, a: number) => {
    const TW = W * 2
    const c = layer(TW, fh), g = ctx2d(c), d = g.createImageData(TW, fh)
    for (let y = 0; y < fh; y++) for (let x = 0; x < TW; x++) {
      const n = 0.5 + 0.25 * Math.sin(2 * Math.PI * k1 * x / TW + seed) + 0.17 * Math.sin(2 * Math.PI * k2 * x / TW + 1.7) + 0.08 * Math.sin(2 * Math.PI * k3 * x / TW + 4)
      const fall = 1 - Math.abs(y - fh / 2) / (fh / 2), v = n * fall
      if (bay(x, y) < v * 0.85) {
        const o = (y * TW + x) * 4
        d.data[o] = 150
        d.data[o + 1] = 144
        d.data[o + 2] = 182
        d.data[o + 3] = a
      }
    }
    g.putImageData(d, 0, 0)
    return c
  }
  const fogs = [
    { c: fogBand(Math.round(H * 0.12), 3, 7, 13, 34), y: H * 0.64, v: 2.2 },
    { c: fogBand(Math.round(H * 0.1), 4, 9, 17, 42), y: H * 0.77, v: 4.5 },
    { c: fogBand(Math.round(H * 0.09), 2, 5, 11, 50), y: H * 0.88, v: 8 },
  ]
  const clouds = Array.from({ length: 4 }, (_, i) => ({ x: R() * W, y: H * (0.12 + i * 0.07) + R() * 6, len: 18 + R() * 30, v: 0.8 + R() * 1.4 }))
  const wisps: { x: number, y: number, life: number, p: number }[] = []
  const flyers: { k: 'w' | 'b', x: number, y: number, v: number, vy?: number, p?: number }[] = []
  let nextWitch = 2.5 + R() * 2, nextBats = 1 + R() * 2, nextSwarm = 8 + R() * 4, last = 0
  const eyes = Array.from({ length: 6 }, () => ({ x: 0, y: 0, until: 0, next: R() * 3 }))
  let nextBolt = 3 + R() * 3
  let bolt: { t0: number, pts: [number, number][], fx: number, fy: number } | null = null
  const makeBolt = () => {
    const pts: [number, number][] = []
    let x = W * (0.08 + R() * 0.55), y = 0
    pts.push([x, y])
    while (y < H * 0.62) {
      y += 3 + R() * 6
      x += (R() - 0.5) * 12
      pts.push([x, y])
    }
    const fx = Math.round(W * (0.1 + R() * 0.8))
    return { pts, fx, fy: ground(fx) }
  }
  const line = (a: [number, number], b: [number, number], c: string) => {
    const n = Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]))
    ctx.fillStyle = c
    for (let i = 0; i <= n; i++) ctx.fillRect(Math.round(a[0] + (b[0] - a[0]) * i / n), Math.round(a[1] + (b[1] - a[1]) * i / n), 1, 1)
  }
  const fogDraw = (fo: { c: HTMLCanvasElement, y: number, v: number }, tt: number) => {
    const off = Math.floor((((tt * fo.v) % (W * 2)) + W * 2) % (W * 2))
    ctx.drawImage(fo.c, -off, Math.round(fo.y))
    ctx.drawImage(fo.c, W * 2 - off, Math.round(fo.y))
  }

  function frame(t: number): void {
    const dt = Math.min(0.1, Math.max(0, t - last))
    last = t
    ctx.drawImage(sky, 0, 0)
    for (const s of stars) {
      const b = 0.35 + 0.65 * (0.5 + 0.5 * Math.sin(t * s.s + s.p)) * s.b
      ctx.fillStyle = `rgba(230,233,242,${b.toFixed(2)})`
      ctx.fillRect(s.x, s.y, 1, 1)
    }
    ctx.drawImage(blood, 0, 0)
    // Der Blutmond grinst ab und zu (~21 s)
    if ((t + seed) % 21 > 18.6) {
      const e = Math.max(3, Math.round(mr * 0.4))
      ctx.fillStyle = '#2a0808'
      ctx.fillRect(mx - e, my - 4, 2, 3)
      ctx.fillRect(mx + e - 1, my - 4, 2, 3)
      for (let i = -e; i <= e; i++) {
        const yy = my + 3 + Math.round(2 - 2 * i * i / (e * e))
        ctx.fillRect(mx + i, yy, 1, 1)
        if (i % 2) ctx.fillRect(mx + i, yy + 1, 1, 1)
      }
    }
    for (const c of clouds) {
      c.x += c.v * dt
      if (c.x > W + 10) c.x = -c.len - 10
      ctx.fillStyle = 'rgba(25,19,44,.82)'
      ctx.fillRect(Math.round(c.x), Math.round(c.y), Math.round(c.len), 1)
      ctx.fillRect(Math.round(c.x + 4), Math.round(c.y + 1), Math.round(c.len - 9), 1)
      ctx.fillStyle = 'rgba(120,112,160,.18)'
      ctx.fillRect(Math.round(c.x + 2), Math.round(c.y - 1), Math.round(c.len * 0.6), 1)
    }
    // Flieger am Himmel
    if (t > nextWitch) {
      flyers.push({ k: 'w', x: -18, y: my - 6 + R() * 10, v: W / 9 })
      nextWitch = t + 14 + R() * 8
    }
    if (t > nextBats) {
      const n = 2 + Math.floor(R() * 3), y0 = H * (0.18 + R() * 0.25)
      for (let i = 0; i < n; i++) flyers.push({ k: 'b', x: W + 4 + i * 7, y: y0 + (R() - 0.5) * 8, v: -(W / 7) * (0.8 + R() * 0.4), p: R() * 6 })
      nextBats = t + 4 + R() * 4
    }
    if (t > nextSwarm) {
      for (let i = 0; i < 12; i++) flyers.push({ k: 'b', x: hx + 2 + R() * 4, y: hb - 30 - R() * 6, v: (W / 6) * (0.5 + R() * 0.8), vy: -(4 + R() * 10), p: R() * 6 })
      nextSwarm = t + 20 + R() * 10
    }
    for (let i = flyers.length - 1; i >= 0; i--) {
      const q = flyers[i]!
      q.x += q.v * dt
      if (q.vy) q.y += q.vy * dt
      if (q.x < -30 || q.x > W + 30) {
        flyers.splice(i, 1)
        continue
      }
      ctx.fillStyle = '#06050b'
      if (q.k === 'w') {
        const yy = Math.round(q.y + Math.sin(t * 2) * 1.5)
        WITCH.forEach((row, ry) => [...row].forEach((ch, rx) => {
          if (ch === 'k') ctx.fillRect(Math.round(q.x) + rx, yy + ry, 1, 1)
        }))
      } else {
        const p = q.p ?? 0
        const up = Math.sin(t * 14 + p) > 0, x = Math.round(q.x), y = Math.round(q.y + Math.sin(t * 3 + p) * 2)
        ctx.fillRect(x + 1, y, 1, 1)
        if (up) {
          ctx.fillRect(x, y - 1, 1, 1)
          ctx.fillRect(x + 2, y - 1, 1, 1)
        } else {
          ctx.fillRect(x, y, 1, 1)
          ctx.fillRect(x + 2, y, 1, 1)
        }
      }
    }
    if (t > nextBolt) {
      bolt = { t0: t, ...makeBolt() }
      nextBolt = t + 9 + R() * 9
      opts.onBolt?.()
    }
    const bt = bolt ? t - bolt.t0 : 99
    if (bolt && bt < 0.32) for (let i = 1; i < bolt.pts.length; i++) line(bolt.pts[i - 1]!, bolt.pts[i]!, i % 3 ? '#f1ecff' : '#c9b8ff')
    ctx.drawImage(far, 0, 0)
    for (const l of lits) {
      ctx.fillStyle = `rgba(240,160,70,${(0.6 + 0.4 * Math.abs(Math.sin(t * 1.3 + l.p) * Math.sin(t * 4.7 + l.p))).toFixed(2)})`
      ctx.fillRect(l.x, l.y, 1, 2)
    }
    fogDraw(fogs[0]!, t)
    ctx.drawImage(mid, 0, 0)
    for (const r of ravens) {
      ctx.fillStyle = '#0a0812'
      ctx.fillRect(r.x - 1, r.y - 2, 3, 2)
      const turn = Math.sin(t * 0.7 + r.p) > 0.6
      ctx.fillRect(r.x + (turn ? -2 : 2), r.y - 3, 1, 1)
      ctx.fillRect(r.x + (turn ? -3 : 3), r.y - 3, 1, 1)
    }
    for (const g of graves) {
      const fl = 0.6 + 0.4 * Math.sin(t * 9 + g.x) * Math.sin(t * 5.3 + g.y)
      ctx.fillStyle = `rgba(255,200,110,${fl.toFixed(2)})`
      ctx.fillRect(g.cx, g.cy - 1, 1, 1)
      ctx.fillStyle = `rgba(255,170,80,${(fl * 0.25).toFixed(2)})`
      ctx.fillRect(g.cx - 1, g.cy - 2, 3, 3)
    }
    for (const gh of ghosts) {
      gh.x += gh.v * dt
      if (gh.x > W + 8) gh.x = -8
      if (gh.x < -8) gh.x = W + 8
      const a = 0.28 + 0.16 * Math.sin(t * 0.9 + gh.p), yy = Math.round(gh.y + Math.sin(t * 1.6 + gh.p) * 2)
      GHOST.forEach((row, ry) => [...row].forEach((ch, rx) => {
        if (ch === '.') return
        ctx.fillStyle = ch === 'k' ? `rgba(20,14,34,${(a * 1.6).toFixed(2)})` : `rgba(230,236,248,${a.toFixed(2)})`
        ctx.fillRect(Math.round(gh.x) + (gh.v < 0 ? 4 - rx : rx), yy + ry, 1, 1)
      }))
    }
    // Zombie-Hände aus den Gräbern
    graves.forEach((g, gi) => {
      const ph = (t * 0.22 + gi * 0.37) % 1, h = ph < 0.3 ? ph / 0.3 * 6 : ph < 0.6 ? 6 : ph < 0.8 ? (0.8 - ph) / 0.2 * 6 : 0
      if (h < 0.5) return
      const hx0 = Math.round(g.x - 5), base = g.cy + 3, top = base - Math.round(h)
      ctx.fillStyle = '#6f8063'
      ctx.fillRect(hx0, top, 1, base - top)
      if (h > 4) {
        const w = Math.sin(t * 6 + gi) > 0
        ctx.fillRect(hx0 - 1, top, 3, 1)
        ctx.fillRect(hx0 - 1, top - 1, 1, 1)
        ctx.fillRect(hx0 + 1, top - (w ? 2 : 1), 1, 1)
        ctx.fillRect(hx0, top - 2, 1, 1)
        ctx.fillRect(hx0 + 2, top + (w ? 0 : -1), 1, 1)
      }
      ctx.fillStyle = '#2a2130'
      ctx.fillRect(hx0 - 2, base, 5, 1)
    })
    // Schädel auf den Zaunpfählen mit glühenden Augen
    for (let x = fence0; x < fence1; x += 12) {
      const g = ground(x)
      ctx.fillStyle = '#d6d1c4'
      ctx.fillRect(x - 1, g - 13, 3, 3)
      ctx.fillRect(x, g - 10, 1, 1)
      ctx.fillStyle = `rgba(255,59,47,${(0.6 + 0.4 * Math.sin(t * 3 + x)).toFixed(2)})`
      ctx.fillRect(x - 1, g - 12, 1, 1)
      ctx.fillRect(x + 1, g - 12, 1, 1)
    }
    // Rote Augen im Dunkeln
    for (let i = 0; i < 6; i++) {
      const e = eyes[i]!
      if (t > e.next) {
        e.x = Math.round(W * (0.04 + R() * 0.92))
        e.y = Math.round(H * (0.72 + R() * 0.16))
        e.until = t + 2.5 + R() * 4
        e.next = e.until + 1 + R() * 5
      }
      if (t < e.until && Math.sin(t * 2.7 + i * 7) < 0.92) {
        ctx.fillStyle = 'rgba(255,40,30,.28)'
        ctx.fillRect(e.x - 1, e.y - 1, 3, 3)
        ctx.fillRect(e.x + 2, e.y - 1, 3, 3)
        ctx.fillStyle = '#ff4433'
        ctx.fillRect(e.x, e.y, 1, 1)
        ctx.fillRect(e.x + 3, e.y, 1, 1)
      }
    }
    // Schattengestalt – nur im Blitzlicht
    if (bolt && bt < 1.8) {
      const a = bt < 0.6 ? 1 : 1 - (bt - 0.6) / 1.2, x = bolt.fx, y = bolt.fy
      ctx.fillStyle = `rgba(2,1,4,${a.toFixed(2)})`
      ctx.fillRect(x - 1, y - 17, 3, 3)
      ctx.fillRect(x - 1, y - 14, 3, 9)
      ctx.fillRect(x - 1, y - 5, 1, 5)
      ctx.fillRect(x + 1, y - 5, 1, 5)
      ctx.fillRect(x - 2, y - 13, 1, 8)
      ctx.fillRect(x + 2, y - 13, 1, 8)
      ctx.fillRect(x - 3, y - 6, 1, 2)
      ctx.fillRect(x + 3, y - 6, 1, 2)
      ctx.fillStyle = `rgba(255,255,255,${a.toFixed(2)})`
      ctx.fillRect(x - 1, y - 16, 1, 1)
      ctx.fillRect(x + 1, y - 16, 1, 1)
    }
    fogDraw(fogs[1]!, t)
    fogDraw({ ...fogs[0]!, v: -fogs[0]!.v * 1.6, y: fogs[1]!.y - 4 }, t)
    ctx.drawImage(fore, 0, 0)
    for (const p of pumpkins) {
      ctx.globalAlpha = 0.7 + 0.3 * Math.sin(t * 7 + p.p) * Math.sin(t * 3.1 + p.p)
      ctx.drawImage(glow, p.x - 14, p.y - 13)
      ctx.globalAlpha = 1
    }
    if (R() < dt * 1.6) {
      const g = graves[Math.floor(R() * graves.length)]!
      wisps.push({ x: g.x + (R() - 0.5) * 3, y: g.y, life: 1, p: R() * 6 })
    }
    for (let i = wisps.length - 1; i >= 0; i--) {
      const w = wisps[i]!
      w.y -= 4 * dt
      w.life -= dt * 0.35
      if (w.life <= 0) {
        wisps.splice(i, 1)
        continue
      }
      ctx.fillStyle = `rgba(169,220,234,${(w.life * 0.8).toFixed(2)})`
      ctx.fillRect(Math.round(w.x + Math.sin(t * 2 + w.p) * 1.5), Math.round(w.y), 1, 1)
    }
    fogDraw(fogs[2]!, t)
    ctx.drawImage(web, 0, 0)
    const sl = spider.len * (0.75 + 0.25 * Math.sin(t * 0.8 + spider.p))
    ctx.fillStyle = 'rgba(207,213,227,.35)'
    ctx.fillRect(spider.x, 0, 1, Math.round(sl))
    ctx.fillStyle = '#0a0812'
    ctx.fillRect(spider.x - 1, Math.round(sl), 3, 2)
    ctx.fillRect(spider.x - 2, Math.round(sl) + 1, 1, 1)
    ctx.fillRect(spider.x + 2, Math.round(sl) + 1, 1, 1)
    ctx.fillStyle = '#b9a2ea'
    ctx.fillRect(spider.x, Math.round(sl) + 1, 1, 1)
    // Blitzlicht über der ganzen Szene (nur mit „Flackern & Rauschen“)
    if (bt < 0.7 && (opts.flicker?.() ?? true)) {
      const a = bt < 0.07 ? 0.55 : bt < 0.13 ? 0.05 : bt < 0.2 ? 0.42 : Math.max(0, 0.42 * (1 - (bt - 0.2) / 0.5))
      ctx.fillStyle = `rgba(214,206,255,${a.toFixed(2)})`
      ctx.fillRect(0, 0, W, H)
    }
    ctx.fillStyle = 'rgba(60,0,10,.10)'
    ctx.fillRect(0, 0, W, H)
  }
  return { frame, info: { graves: graves.map((g) => ({ x: g.x, y: g.y })), width: W, height: H } }
}
