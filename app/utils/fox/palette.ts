import { css, mix, parseHex, type Rgb } from '../redstone/palette'

// Farben der Fuchsbau-Landschaft. Feste, abgestimmte Töne je Farbschema: warme
// Abenddämmerung (Dunkel), späte Dämmerung (OLED) und goldene Stunde (Hell). Die
// Welt bekommt ein Umgebungslicht (Dämmerung tönt alles rosa-violett, die goldene
// Stunde warm) – nur die Zaunlaternen folgen dem Akzent, Feuer und Sonne bleiben warm.

export type FoxVariant = 'dark' | 'oled' | 'light'

export interface FoxPalette {
  variant: FoxVariant
  /** Himmel von oben nach unten (Bänder, dazwischen gerastert). */
  sky: Rgb[]
  /** Dunst am Horizont – ferne Ebenen werden darin eingefärbt. */
  haze: Rgb
  hazeScale: number
  star: Rgb
  starDim: Rgb
  sun: Rgb
  sunCore: Rgb
  halo: Rgb
  cloudLit: Rgb
  cloud: Rgb
  cloudUnder: Rgb
  /** Ferne Hügel: hinten, davor (jeweils Fläche + Saum oben). */
  hillBack: Rgb
  hillBackRim: Rgb
  hill: Rgb
  hillRim: Rgb
  /** Tönung des fernen Waldbands und der Gruppen auf der Wiese. */
  forestFar: Rgb
  forestMid: Rgb
  /** Wiese: im Licht → tief im Schatten. */
  meadow: [Rgb, Rgb, Rgb, Rgb]
  grass: [Rgb, Rgb, Rgb]
  dirt: [Rgb, Rgb, Rgb]
  /** Ganz unten (Erde im Schatten). */
  deep: Rgb
  spruce: [Rgb, Rgb, Rgb, Rgb]
  spruceLog: [Rgb, Rgb, Rgb]
  cherry: [Rgb, Rgb, Rgb, Rgb]
  cherryLog: [Rgb, Rgb, Rgb]
  berryLeaf: [Rgb, Rgb, Rgb]
  berry: Rgb
  berryHi: Rgb
  /** Saum, den die tiefe Sonne auf Oberkanten legt. */
  rim: Rgb
  /** Loch im Bau (Eingang → tief). */
  hole: [Rgb, Rgb]
  root: [Rgb, Rgb]
  /** Lagerfeuer: Stämme, Glut, Flammen (innen → außen). */
  log: [Rgb, Rgb, Rgb]
  ember: Rgb
  flame: [Rgb, Rgb, Rgb]
  smoke: Rgb
  spark: Rgb
  fly: Rgb
  flyGlow: Rgb
  /** Fallende Blätter (warm) und Kirschblüten. */
  leaves: Rgb[]
  petals: Rgb[]
  flowers: [Rgb, Rgb, Rgb]
  /** Umgebungslicht (Faktor je Kanal) – tönt den Fuchs wie die Welt. */
  ambient: Rgb
  fence: [Rgb, Rgb]
  lanternFrame: Rgb
  lanternOff: Rgb
  lanternOn: Rgb
  lanternCore: Rgb
  /** Weiches Licht (Glüh-Ebene). */
  glowAccent: Rgb
  glowFire: Rgb
  glowSun: Rgb
  glowFly: Rgb
}

const FALLBACK: Record<string, string> = {
  '--color-redstone-300': '#ffc89a',
  '--color-redstone-400': '#ff9a52',
  '--color-redstone-500': '#e8702a',
}

const hex = (h: string): Rgb => parseHex(h)!
const WHITE: Rgb = [255, 255, 255]

/** Farbe im Umgebungslicht (je Kanal multipliziert). */
export function lit(c: Rgb, amb: Rgb): Rgb {
  return [Math.min(255, (c[0] * amb[0]) / 255), Math.min(255, (c[1] * amb[1]) / 255), Math.min(255, (c[2] * amb[2]) / 255)]
}

// Minecraft-Grundfarben (Tageslicht) – je Farbschema mit dem Umgebungslicht getönt.
const MC = {
  grass: ['#4c8030', '#5f9a3c', '#74b048'],
  dirt: ['#6e4630', '#8a5a3a', '#9a6a44'],
  spruce: ['#16291f', '#1f3a2e', '#2f5a40', '#3f7050'],
  spruceLog: ['#2c1c0e', '#3b2614', '#4e3420'],
  cherry: ['#c95a8c', '#e07aa8', '#f3a6c8', '#ffc6dc'],
  cherryLog: ['#2a1622', '#3b2230', '#5a3440'],
  berryLeaf: ['#244a2a', '#2f6a3a', '#3f8a4a'],
  log: ['#2c1c0e', '#3b2614', '#6a4a2c'],
  fence: ['#4e3420', '#6b4a2e'],
}

type Base = Omit<FoxPalette, 'variant' | 'lanternFrame' | 'lanternOff' | 'lanternOn' | 'lanternCore' | 'glowAccent' | 'glowSun'>

function base(variant: FoxVariant): Base {
  const amb = hex(variant === 'light' ? '#fff4e0' : variant === 'oled' ? '#9a7c92' : '#e6b8c0')
  const L = (h: string) => lit(hex(h), amb)
  const tri = (a: string[]) => a.map(L) as [Rgb, Rgb, Rgb]
  const quad = (a: string[]) => a.map(L) as [Rgb, Rgb, Rgb, Rgb]
  const shared = {
    grass: tri(MC.grass),
    dirt: tri(MC.dirt),
    spruce: quad(MC.spruce),
    spruceLog: tri(MC.spruceLog),
    cherry: quad(MC.cherry),
    cherryLog: tri(MC.cherryLog),
    berryLeaf: tri(MC.berryLeaf),
    berry: hex('#c8102e'),
    berryHi: hex('#ff5a6a'),
    log: tri(MC.log),
    fence: MC.fence.map(L) as [Rgb, Rgb],
    root: [L('#4e3420'), L('#2c1c0e')] as [Rgb, Rgb],
    ember: hex('#ff8a2a'),
    flame: [hex('#fff2a8'), hex('#ffb340'), hex('#ff6a1a')] as [Rgb, Rgb, Rgb],
    spark: hex('#ffd27a'),
    fly: hex('#fff4a8'),
    flyGlow: hex('#ffe27a'),
    leaves: ['#e07a3a', '#f0a050', '#b4502a', '#e8b04a'].map(hex),
    petals: ['#f3a6c8', '#ffc6dc'].map(hex),
    flowers: [hex('#ff8ac0'), hex('#ffd84a'), hex('#f4efe8')] as [Rgb, Rgb, Rgb],
    ambient: amb,
    glowFire: hex('#ff9a40'),
    glowFly: hex('#ffe27a'),
  }
  if (variant === 'light') {
    return {
      ...shared,
      sky: ['#8fb2dc', '#a6bfe2', '#bfc9e2', '#d8cfdc', '#ecd3cc', '#f8d8bc', '#ffdcaa', '#ffe4b0', '#fff0cc'].map(hex),
      haze: hex('#f6dcc4'),
      hazeScale: 0.62,
      star: hex('#ffffff'),
      starDim: hex('#ffffff'),
      sun: hex('#fff2c4'),
      sunCore: hex('#ffffff'),
      halo: hex('#fff0b8'),
      cloudLit: hex('#ffffff'),
      cloud: hex('#fff0e4'),
      cloudUnder: hex('#ecc4b4'),
      hillBack: hex('#c6b0c6'),
      hillBackRim: hex('#e2d0d8'),
      hill: hex('#a898b4'),
      hillRim: hex('#c8b6c6'),
      forestFar: hex('#8c94a8'),
      forestMid: hex('#6a7a80'),
      meadow: [hex('#b4c46a'), hex('#94b052'), hex('#7a9846'), hex('#5f7c3c')],
      deep: hex('#5a3c2a'),
      rim: hex('#fff0b0'),
      hole: [hex('#4a2e1e'), hex('#1e120c')],
      smoke: hex('#c8bcb8'),
    }
  }
  if (variant === 'oled') {
    return {
      ...shared,
      sky: ['#000000', '#07040b', '#100816', '#1a0c20', '#281028', '#3a1628', '#4e1e28', '#682a28', '#86402c'].map(hex),
      haze: hex('#3e1c2c'),
      hazeScale: 1,
      star: hex('#ffe8d8'),
      starDim: hex('#6e5064'),
      sun: hex('#f0a058'),
      sunCore: hex('#ffd098'),
      halo: hex('#c86040'),
      cloudLit: hex('#c88a74'),
      cloud: hex('#7a4048'),
      cloudUnder: hex('#4a2236'),
      hillBack: hex('#4a2236'),
      hillBackRim: hex('#6a3040'),
      hill: hex('#341a2c'),
      hillRim: hex('#4c2434'),
      forestFar: hex('#2a1424'),
      forestMid: hex('#1c0e1a'),
      meadow: [hex('#3e3a26'), hex('#2a2a1e'), hex('#1a1816'), hex('#0e0a0c')],
      deep: hex('#070407'),
      rim: hex('#e88a50'),
      hole: [hex('#1c100c'), hex('#050302')],
      smoke: hex('#4a3a44'),
    }
  }
  return {
    ...shared,
    sky: ['#2a1a3a', '#3e2044', '#56284e', '#7a3a5a', '#9a4a5c', '#c05e5a', '#e0785a', '#f09a64', '#ffc27a'].map(hex),
    haze: hex('#d07a82'),
    hazeScale: 1,
    star: hex('#ffe8d8'),
    starDim: hex('#9a7088'),
    sun: hex('#ffd27a'),
    sunCore: hex('#fff2c0'),
    halo: hex('#ffb070'),
    cloudLit: hex('#ffe2c4'),
    cloud: hex('#f4ac92'),
    cloudUnder: hex('#c8687a'),
    hillBack: hex('#b4607a'),
    hillBackRim: hex('#d88a8a'),
    hill: hex('#8a4566'),
    hillRim: hex('#b0647a'),
    forestFar: hex('#7a3a5a'),
    forestMid: hex('#4e2440'),
    meadow: [hex('#8a7a46'), hex('#5a5634'), hex('#3c3a2c'), hex('#261c22')],
    deep: hex('#170d14'),
    rim: hex('#ffb878'),
    hole: [hex('#3a2418'), hex('#0e0806')],
    smoke: hex('#8a7884'),
  }
}

/** Baut die Palette; `read` liefert Theme-Variablen (austauschbar für Tests). */
export function buildFoxPalette(read: (name: string) => string, variant: FoxVariant): FoxPalette {
  const v = (name: string): Rgb => parseHex(read(name)) ?? parseHex(FALLBACK[name]!)!
  const a300 = v('--color-redstone-300')
  const a400 = v('--color-redstone-400')
  const a500 = v('--color-redstone-500')
  const b = base(variant)
  const light = variant === 'light'
  // Im hellen Theme ist `redstone-300` dunkler Text – fürs Licht die helle Stufe nehmen.
  const accentHi = light ? mix(a400, WHITE, 0.45) : a300
  return {
    variant,
    ...b,
    lanternFrame: light ? hex('#3a2a26') : hex('#1e1416'),
    lanternOff: mix(a500, b.spruce[0], light ? 0.6 : 0.72),
    lanternOn: mix(a400, WHITE, 0.15),
    lanternCore: mix(accentHi, WHITE, 0.55),
    glowAccent: a400,
    glowSun: b.halo,
  }
}

/** Palette aus dem Dokument (Farbschema + Akzent). */
export function documentFoxPalette(): FoxPalette {
  if (typeof document === 'undefined') return buildFoxPalette(() => '', 'dark')
  const root = document.documentElement
  const style = getComputedStyle(root)
  const theme = root.dataset.theme
  const variant: FoxVariant = theme === 'light' ? 'light' : theme === 'oled' ? 'oled' : 'dark'
  return buildFoxPalette((name) => style.getPropertyValue(name), variant)
}

export { css, mix }
