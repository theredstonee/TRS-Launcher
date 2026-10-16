/**
 * Halloween 2026 – Pixel-Art-Grundlagen aus dem freigegebenen Entwurf (halloween-2026-mockup): Zufall, Bayer-Dithering,
 * Farben und die Sprite-Karten. Gemeinsam für die Website (Szene, Kürbisse, Abzeichen) und den Generator
 * (scripts/generate-halloween.mjs: Sticker-, Abzeichen- und Umhang-PNGs). Reine Daten/Funktionen, keine Importe.
 */

/** Deterministischer Zufall (mulberry32-Variante wie im Entwurf). */
export function rng(seed: number): () => number {
  let s = seed
  return () => {
    s = (s + 0x6D2B79F5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export const BAYER = [0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5] as const
/** Bayer-Schwelle 0…15/16 für Pixel (x, y). */
export function bay(x: number, y: number): number {
  return BAYER[(y & 3) * 4 + (x & 3)]! / 16
}

export type Rgb = [number, number, number]
export function hex(c: string): Rgb {
  const n = Number.parseInt(c.slice(1), 16)
  return [n >> 16, (n >> 8) & 255, n & 255]
}
export function mix(a: Rgb, b: Rgb, k: number): Rgb {
  return [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k]
}

export interface Sprite {
  pal: Record<string, string>
  m: readonly string[]
}

/** Sprites aus dem Entwurf (Zeichen → Farbe, `.` = leer). */
export const SPR = {
  pumpkin: { pal: { o: '#8a3f16', O: '#d9772f', g: '#4d7a45', y: '#ffd27a' }, m: [
    '............', '.....gg.....', '..ooOgOoo...', '.oOOOoOOOo..', 'oOyOOoOOyOo.', 'oOyyOoOyyOo.',
    'oOOOOoOOOOo.', 'oOyOyoyOyOo.', 'oOOyOyOyOOo.', '.oOOOoOOOo..', '..ooooooo...', '............'] },
  ghost: { pal: { w: '#eef1f8', s: '#b8bdcc', k: '#1a1426' }, m: [
    '....wwww....', '..wwwwwwww..', '.wwwwwwwwww.', '.wwkkwwkkww.', '.wwkkwwkkww.', '.wwwwwwwwww.',
    '.wwwwkkwwww.', '.wwwwkkwwws.', '.wwwwwwwwws.', '.wwwwwwwwss.', '.ww.ww.ww.s.', '.w...w...w..'] },
  bat: { pal: { k: '#2a2240', v: '#b9a2ea' }, m: [
    '............', '............', 'k....kk....k', 'kk..kkkk..kk', 'kkkkkvkvkkkk', '.kkkkkkkkkk.',
    '..kk.kk.kk..', '..k......k..', '............', '............', '............', '............'] },
  moon: { pal: { s: '#e6e9f2', d: '#a9afc2' }, m: [
    '....ssss....', '..sssss.....', '.ssssd......', '.sssd.......', 'sssd........', 'sssd........',
    'sssd........', 'sssd........', '.sssd.......', '.ssssd......', '..sssss.....', '....ssss....'] },
  cauldron: { pal: { i: '#4a4560', h: '#6f6890', b: '#9fd6e6' }, m: [
    '..b...b.....', '....b...b...', '.hhhhhhhhhh.', 'ibbbbbbbbbbi', '.iiiiiiiiii.', '.ihiiiiiiii.',
    'iihiiiiiiiii', 'iiiiiiiiiiii', '.iiiiiiiiii.', '..iiiiiiii..', '..i......i..', '............'] },
  grave: { pal: { c: '#7d7a8c', k: '#3b3851', g: '#2f4a35' }, m: [
    '....cccc....', '..cccccccc..', '.cccckkcccc.', '.cckkkkkkcc.', '.cccckkcccc.', '.cccckkcccc.',
    '.cccccccccc.', '.cccccccccc.', '.cccccccccc.', 'gggggggggggg', 'gggggggggggg', '............'] },
  moonbat: { pal: { s: '#e6e9f2', k: '#b9a2ea' }, m: [
    '...sss...', '..ss.....', '.ss......', '.ss.k...k', '.ss.kkkkk', '.ss..k.k.', '..ss.....', '...sss...'] },
  spider: { pal: { k: '#0e0b16', v: '#b9a2ea', l: '#3b3358' }, m: [
    'l.kkk.l', '.lkkkl.', 'lkvkvkl', '.kkkkk.', 'l.kkk.l', '.l...l.'] },
  pk8: { pal: { o: '#8a3f16', O: '#d9772f', g: '#4d7a45', y: '#ffd27a' }, m: [
    '....g...', '..oOOo..', '.oOyOyO.', '.oOOOOO.', '.oOyyyO.', '..oOOo..', '........', '........'] },
} as const satisfies Record<string, Sprite>

export type SpriteName = keyof typeof SPR

export function spriteSize(name: SpriteName): { w: number, h: number } {
  const s = SPR[name]
  return { w: s.m[0]!.length, h: s.m.length }
}

/** Ruft `put(x, y, farbe)` für jeden gesetzten Pixel des Sprites. */
export function eachSpritePixel(name: SpriteName, put: (x: number, y: number, color: string) => void): void {
  const s: Sprite = SPR[name]
  s.m.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      const c = s.pal[row[x]!]
      if (c) put(x, y, c)
    }
  })
}
