// Farben der Pixel-Szenen aus den Theme-Variablen: Deepslate-Skala für Boden
// und Blöcke, Akzentfarbe für Staub und Glühen, Lampen-Bernstein für Lampen.
// So färben Theme (Dunkel/OLED/Hell) und Akzent die Szene automatisch mit.

export type Rgb = [number, number, number]

export interface Palette {
  light: boolean
  floor: [string, string, string]
  grout: string
  block: [string, string, string]
  blockEdge: string
  blockHi: string
  /** Staubfarbe je Signalstärke 0–15. */
  dust: string[]
  /** Hellere Körnung im Staub je Signalstärke. */
  dustHi: string[]
  slab: string
  slabEdge: string
  torchOff: string
  torchOn: string
  torchCore: string
  lampOff: string
  lampOffLine: string
  lampOn: string
  lampOnLine: string
  lampCore: string
  pistonBody: string
  pistonEdge: string
  pistonHead: string
  pistonArm: string
  /** Glühen (Akzent) und Lampenlicht als RGB für das weiche Licht darüber. */
  glow: Rgb
  lampGlow: Rgb
}

const FALLBACK: Record<string, string> = {
  '--color-base-950': '#111116',
  '--color-base-900': '#17171e',
  '--color-base-850': '#1d1d26',
  '--color-base-800': '#252531',
  '--color-base-700': '#333343',
  '--color-base-600': '#4a4a5e',
  '--color-base-400': '#8b8ba2',
  '--color-redstone-300': '#ff8f85',
  '--color-redstone-400': '#ff5a4d',
  '--color-redstone-500': '#e0281e',
  '--color-redstone-600': '#b31a12',
  '--color-lamp-300': '#ffd48a',
  '--color-lamp-400': '#ffb84d',
}

export function parseHex(value: string): Rgb | null {
  const m = /^#([0-9a-f]{3}|[0-9a-f]{6})$/i.exec(value.trim())
  if (!m) return null
  let hex = m[1]!
  if (hex.length === 3) hex = hex.replace(/./g, (c) => c + c)
  const n = Number.parseInt(hex, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

export function mix(a: Rgb, b: Rgb, t: number): Rgb {
  return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]
}

export function css(c: Rgb): string {
  return `rgb(${Math.round(c[0])} ${Math.round(c[1])} ${Math.round(c[2])})`
}

/** Liest die Theme-Farben; `read` ist austauschbar (Tests, SSR). */
export function buildPalette(read: (name: string) => string, light: boolean): Palette {
  const v = (name: string): Rgb => parseHex(read(name)) ?? parseHex(FALLBACK[name]!)!
  const b950 = v('--color-base-950')
  const b900 = v('--color-base-900')
  const b850 = v('--color-base-850')
  const b800 = v('--color-base-800')
  const b700 = v('--color-base-700')
  const b600 = v('--color-base-600')
  const b400 = v('--color-base-400')
  const r300 = v('--color-redstone-300')
  const r400 = v('--color-redstone-400')
  const r500 = v('--color-redstone-500')
  const r600 = v('--color-redstone-600')
  const l300 = v('--color-lamp-300')
  const l400 = v('--color-lamp-400')

  // Im hellen Theme ist der Boden heller Stein – die Blöcke heben sich dunkler ab.
  const floorBase = light ? b800 : b900
  const off = mix(r600, floorBase, light ? 0.55 : 0.62)
  const dust: string[] = []
  const dustHi: string[] = []
  for (let p = 0; p <= 15; p++) {
    const t = p === 0 ? 0 : 0.45 + (0.55 * p) / 15
    const c = p === 0 ? off : t < 0.8 ? mix(off, r500, (t - 0.45) / 0.35) : mix(r500, r400, (t - 0.8) / 0.2)
    dust.push(css(c))
    dustHi.push(css(p === 0 ? mix(off, floorBase, 0.35) : mix(c, r300, 0.55)))
  }

  return {
    light,
    floor: light
      ? [css(b800), css(mix(b800, b850, 0.5)), css(mix(b800, b700, 0.35))]
      : [css(b900), css(mix(b900, b850, 0.55)), css(mix(b900, b950, 0.6))],
    grout: css(light ? b700 : b950),
    block: light
      ? [css(b600), css(mix(b600, b700, 0.5)), css(mix(b600, b400, 0.3))]
      : [css(b800), css(mix(b800, b700, 0.5)), css(mix(b800, b850, 0.5))],
    blockEdge: css(light ? mix(b600, b400, 0.6) : b950),
    blockHi: css(light ? mix(b600, b800, 0.5) : b700),
    dust,
    dustHi,
    slab: css(light ? b700 : mix(b700, b600, 0.35)),
    slabEdge: css(light ? b600 : b800),
    torchOff: css(mix(r600, b800, 0.55)),
    torchOn: css(r400),
    torchCore: css(mix(r300, [255, 255, 255], 0.5)),
    lampOff: css(mix(l400, light ? b700 : b850, light ? 0.62 : 0.8)),
    lampOffLine: css(mix(l400, light ? b600 : b700, light ? 0.5 : 0.72)),
    lampOn: css(l400),
    lampOnLine: css(l300),
    lampCore: css(mix(l300, [255, 255, 255], 0.55)),
    pistonBody: css(light ? b600 : b700),
    pistonEdge: css(light ? mix(b600, b400, 0.5) : b800),
    pistonHead: css(mix(l400, light ? b600 : b700, 0.55)),
    pistonArm: css(mix(l400, light ? b400 : b600, 0.75)),
    glow: r400,
    lampGlow: l400,
  }
}

/** Palette aus dem Dokument (aktuelles Theme + Akzent). */
export function documentPalette(): Palette {
  if (typeof document === 'undefined') return buildPalette(() => '', false)
  const style = getComputedStyle(document.documentElement)
  return buildPalette((name) => style.getPropertyValue(name), document.documentElement.dataset.theme === 'light')
}
