import { css, mix, parseHex, type Rgb } from '../redstone/palette'

// Farben der Winterlandschaft. Die Landschaft selbst hat feste, abgestimmte
// Töne je Farbschema (Nacht, OLED-Nacht, heller Wintertag) – nur Polarlicht und
// Eislaternen folgen der Akzentfarbe, das Hüttenfenster bleibt warm.

export type WinterVariant = 'dark' | 'oled' | 'light'

export interface WinterPalette {
  variant: WinterVariant
  /** Himmel von oben nach unten (Bänder, dazwischen gerastert). */
  sky: Rgb[]
  /** Dunst am Horizont – ferne Ebenen werden darin eingefärbt. */
  haze: Rgb
  /** Wie stark ferne Ebenen im Dunst verschwinden (am Tag weniger). */
  hazeScale: number
  star: Rgb
  starDim: Rgb
  /** Mond (nachts) bzw. Sonne (Tag). */
  moon: Rgb
  moonShade: Rgb
  moonCrater: Rgb
  halo: Rgb
  cloud: Rgb
  cloudShade: Rgb
  rock: Rgb
  rockShade: Rgb
  snowLit: Rgb
  snow: Rgb
  snowShade: Rgb
  snowDeep: Rgb
  sparkle: Rgb
  pineLit: Rgb
  pine: Rgb
  pineDark: Rgb
  trunk: Rgb
  wood: Rgb
  woodDark: Rgb
  windowOn: Rgb
  windowFrame: Rgb
  smoke: Rgb
  flake: Rgb
  flakeFar: Rgb
  /** Eislaterne aus und an (Glas). */
  lanternOff: Rgb
  lanternFrame: Rgb
  lanternOn: Rgb
  lanternCore: Rgb
  /** Polarlicht wie echt: helle Unterkante, grüner Körper, dunkleres Grün, violett-rosa Oberkante. */
  aurora: [Rgb, Rgb, Rgb, Rgb]
  /** Weiches Licht (Glüh-Ebene). */
  glowAccent: Rgb
  glowWarm: Rgb
  glowMoon: Rgb
}

const FALLBACK: Record<string, string> = {
  '--color-redstone-300': '#b8e7ff',
  '--color-redstone-400': '#7fd3ff',
  '--color-redstone-500': '#2b97d6',
}

const hex = (h: string): Rgb => parseHex(h)!

// Echte Polarlichter: Sauerstoff-Grün (557,7 nm) unten, oben Rot/Violett – unabhängig vom Akzent.
const AURORA: [Rgb, Rgb, Rgb, Rgb] = [hex('#c8ffd9'), hex('#3dff86'), hex('#14c25a'), hex('#b45cff')]
const WHITE: Rgb = [255, 255, 255]

/** Feste Landschaftstöne je Farbschema. */
function base(variant: WinterVariant) {
  if (variant === 'light') {
    return {
      sky: ['#9fc9ea', '#b6d7f0', '#cbe3f5', '#ddedf9', '#ecf5fc'].map(hex),
      haze: hex('#dcecf8'),
      star: hex('#ffffff'),
      starDim: hex('#ffffff'),
      moon: hex('#fffbea'),
      moonShade: hex('#fff1c4'),
      moonCrater: hex('#ffe9b0'),
      halo: hex('#ffffff'),
      cloud: hex('#ffffff'),
      cloudShade: hex('#d9e7f4'),
      rock: hex('#7d93b3'),
      rockShade: hex('#5f7698'),
      snowLit: hex('#ffffff'),
      snow: hex('#eef5fc'),
      snowShade: hex('#cfdff0'),
      snowDeep: hex('#b3c8e2'),
      sparkle: hex('#ffffff'),
      pineLit: hex('#3f7c6c'),
      pine: hex('#2c5f55'),
      pineDark: hex('#1d443f'),
      trunk: hex('#5a4038'),
      wood: hex('#8a5d42'),
      woodDark: hex('#5e3d2c'),
      windowOn: hex('#ffcf7a'),
      windowFrame: hex('#4a3226'),
      smoke: hex('#c9d6e4'),
      flake: hex('#ffffff'),
      flakeFar: hex('#f4f9ff'),
    }
  }
  const oled = variant === 'oled'
  return {
    sky: (oled ? ['#000000', '#01030a', '#030812', '#06101f', '#0b182c'] : ['#060b18', '#0a1224', '#0f1a32', '#152442', '#1d3154']).map(hex),
    haze: hex(oled ? '#0d1a2e' : '#24385c'),
    star: hex('#f2f8ff'),
    starDim: hex(oled ? '#5d6f8f' : '#7f93b8'),
    moon: hex('#eef4ff'),
    moonShade: hex('#c3d0e6'),
    moonCrater: hex('#a9b8d3'),
    halo: hex('#9fc2ff'),
    cloud: hex('#3a4d72'),
    cloudShade: hex('#2a3a5a'),
    rock: hex(oled ? '#18233a' : '#25365a'),
    rockShade: hex(oled ? '#0f1729' : '#1a2744'),
    snowLit: hex(oled ? '#c9dcf5' : '#e3efff'),
    snow: hex(oled ? '#8ea7cc' : '#aac2e4'),
    snowShade: hex(oled ? '#54698f' : '#7690bd'),
    snowDeep: hex(oled ? '#2f3f5f' : '#4d6491'),
    sparkle: hex('#ffffff'),
    pineLit: hex(oled ? '#1b3a41' : '#24505a'),
    pine: hex(oled ? '#10262d' : '#173640'),
    pineDark: hex(oled ? '#08151b' : '#0e222b'),
    trunk: hex(oled ? '#1d1517' : '#2c2025'),
    wood: hex(oled ? '#3b2a26' : '#553a30'),
    woodDark: hex(oled ? '#241917' : '#36241f'),
    windowOn: hex('#ffc66b'),
    windowFrame: hex(oled ? '#1a1210' : '#2a1c17'),
    smoke: hex(oled ? '#2a3550' : '#465a80'),
    flake: hex('#f4f9ff'),
    flakeFar: hex(oled ? '#6d82a6' : '#9fb4d6'),
  }
}

/** Baut die Palette; `read` liefert Theme-Variablen (austauschbar für Tests). */
export function buildWinterPalette(read: (name: string) => string, variant: WinterVariant): WinterPalette {
  const v = (name: string): Rgb => parseHex(read(name)) ?? parseHex(FALLBACK[name]!)!
  const a300 = v('--color-redstone-300')
  const a400 = v('--color-redstone-400')
  const a500 = v('--color-redstone-500')
  // Feuerschein bleibt warm – im Winter-Design sind die Lampen-Variablen eisblau.
  const l300 = hex('#ffd48a')
  const l400 = hex('#ffb84d')
  const b = base(variant)
  const light = variant === 'light'
  // Im hellen Theme ist `redstone-300` dunkler Text – fürs Licht die helle Stufe nehmen.
  const accentHi = light ? mix(a400, WHITE, 0.45) : a300
  return {
    variant,
    ...b,
    hazeScale: light ? 0.62 : 1,
    lanternOff: mix(a500, light ? b.snowDeep : b.pineDark, light ? 0.7 : 0.78),
    lanternFrame: light ? hex('#3c4a60') : hex('#151d2c'),
    lanternOn: mix(a400, WHITE, 0.2),
    lanternCore: mix(accentHi, WHITE, 0.55),
    aurora: AURORA,
    glowAccent: a400,
    glowWarm: light ? l400 : mix(l400, l300, 0.3),
    glowMoon: b.halo,
  }
}

/** Palette aus dem Dokument (Farbschema + Akzent). */
export function documentWinterPalette(): WinterPalette {
  if (typeof document === 'undefined') return buildWinterPalette(() => '', 'dark')
  const root = document.documentElement
  const style = getComputedStyle(root)
  const theme = root.dataset.theme
  const variant: WinterVariant = theme === 'light' ? 'light' : theme === 'oled' ? 'oled' : 'dark'
  return buildWinterPalette((name) => style.getPropertyValue(name), variant)
}

export { css, mix }
