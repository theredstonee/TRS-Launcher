import type { Rgb } from '../redstone/palette'

// Der schlafende Fuchs am Lagerfeuer – von Hand gepixelt nach dem Minecraft-Fuchs
// (orange mit weißen Wangen, dunkle Ohrspitzen, buschiger Schwanz mit weißer Spitze).
// Er liegt eingerollt wie im Spiel: Kopf flach am Boden zum Feuer, der Schwanz liegt
// vorn herum und deckt mit der Spitze die Schnauze zu. Blick nach links (zum Feuer).
// Atmen: beim Einatmen hebt sich der Rücken um einen Texel.

/** Farben des Minecraft-Fuchses (Tageslicht; die Szene tönt sie). */
export const FOX_COLORS: Record<string, Rgb> = {
  O: [228, 124, 58],
  S: [178, 84, 34],
  D: [126, 56, 26],
  L: [252, 180, 106],
  W: [244, 239, 232],
  V: [214, 202, 190],
  K: [42, 26, 22],
  N: [20, 16, 16],
}

// 18 × 9, ausgeatmet. `.` = frei. Kopf links (zwei Ohren mit dunklen Spitzen, geschlossenes
// Auge, Nase, weiße Wange), eine dunkle Kante zur Schulter, dahinter der höhere, runde
// Rücken; unten der Schwanz, dessen weiße Spitze die Schnauze zudeckt.
const SLEEP = [
  '..K..K............',
  '.OO.OO....LLLLL...',
  '.LLLLLLD.LOOOOOLL.',
  '.ODDOOOODOOOOOOOOS',
  'KOOOOOOODOOOOOOOOS',
  '.WWWOOOSSSSSSSSSOS',
  'WWWLLLLLLLLLLLLLSD',
  'WWVOOOOOOOOOOOOSD.',
  '.VVSSSSSSSSSSSSD..',
]

export const SLEEPING_FOX_W = SLEEP[0]!.length
export const SLEEPING_FOX_H = SLEEP.length

export interface SleepView {
  /** Eingeatmet: Rücken einen Texel höher. */
  inhale: boolean
  /** Ein Ohr zuckt kurz (liegt flach). */
  twitch: boolean
}

/** Pixel des schlafenden Fuchses `[x, y, Farbe]` relativ zur linken oberen Ecke. */
export function sleepingFoxPixels(v: SleepView): [number, number, string][] {
  const out: [number, number, string][] = []
  SLEEP.forEach((row, y) => {
    for (let x = 0; x < row.length; x++) {
      let c = row[x]!
      if (c === '.') continue
      // Ohrzucken: das hintere Ohr klappt kurz flach.
      if (v.twitch && y === 0 && x === 5) continue
      if (v.twitch && y === 1 && x === 5) c = 'K'
      // Einatmen: über dem Rücken eine neue Lichtkante, die alte wird Fell.
      if (v.inhale && y === 1 && x >= 10 && x <= 14) c = 'O'
      out.push([x, y, c])
    }
  })
  if (v.inhale) for (let x = 10; x <= 14; x++) out.push([x, 0, 'L'])
  return out
}

/** Winziger Fuchs (Banner, Vorschau): 5 × 3, eingerollt. */
export const TINY_FOX = ['.K.K.', 'OOOOS', 'WLLLS']
