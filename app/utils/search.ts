// Unscharfe Suche für die Befehlspalette (Strg+K): Die Buchstaben der Eingabe
// müssen in dieser Reihenfolge vorkommen, müssen aber nicht zusammenhängen.
// Bewertet wird, wie „natürlich“ der Treffer ist – Wortanfänge und
// zusammenhängende Stücke zählen mehr als verstreute Buchstaben.

export interface FuzzyResult {
  score: number
  /** Getroffene Stellen im Text – für die Hervorhebung. */
  positions: number[]
}

const FOLD: Record<string, string> = {
  ä: 'a', ö: 'o', ü: 'u', ß: 's', é: 'e', è: 'e', ê: 'e', ë: 'e', á: 'a', à: 'a', â: 'a', ã: 'a',
  í: 'i', ì: 'i', ï: 'i', ó: 'o', ò: 'o', ô: 'o', õ: 'o', ú: 'u', ù: 'u', û: 'u', ñ: 'n', ç: 'c',
}

/** Kleinschreibung + Umlaute auflösen, ohne die Länge zu verändern (Positionen bleiben gültig). */
export function fold(text: string): string {
  let out = ''
  for (const char of text.toLowerCase()) out += FOLD[char] ?? char
  return out
}

function isBoundary(text: string, index: number): boolean {
  if (index === 0) return true
  const before = text[index - 1]!
  return !/[a-z0-9]/i.test(before)
}

/**
 * `null` = kein Treffer. Höhere Punktzahl = besserer Treffer.
 * Leere Eingabe trifft alles mit Punktzahl 0.
 */
export function fuzzyMatch(query: string, text: string): FuzzyResult | null {
  const q = fold(query.trim())
  if (!q) return { score: 0, positions: [] }
  const haystack = fold(text)
  if (q.length > haystack.length) return null

  const positions: number[] = []
  let score = 0
  let from = 0
  let previous = -2

  for (const needle of q) {
    if (needle === ' ') continue
    let at = -1
    // Zuerst an einem Wortanfang suchen – „mi la“ soll „Minecraft Launcher“ finden.
    for (let i = from; i < haystack.length; i++) {
      if (haystack[i] === needle && isBoundary(text, i)) {
        at = i
        break
      }
    }
    if (at < 0) at = haystack.indexOf(needle, from)
    if (at < 0) return null

    if (at === previous + 1) score += 8
    if (isBoundary(text, at)) score += 6
    if (at === 0) score += 6
    // Späte Treffer sind schwächer, aber nie schlechter als ein fehlender Treffer.
    score += Math.max(0, 4 - at / 8)
    positions.push(at)
    previous = at
    from = at + 1
  }

  // Kurze Texte, die fast nur aus dem Gesuchten bestehen, stehen vorn.
  score += Math.max(0, 12 - (haystack.length - q.length) / 2)
  if (haystack === q) score += 40
  else if (haystack.startsWith(q)) score += 20
  else if (haystack.includes(q)) score += 12
  return { score, positions }
}

export interface Ranked<T> {
  item: T
  score: number
  positions: number[]
}

/**
 * Sortiert Einträge nach Trefferqualität. `fields`: der erste Eintrag ist der
 * Haupttext (dessen Trefferstellen hervorgehoben werden), weitere Felder
 * zählen abgeschwächt mit.
 */
export function rank<T>(items: readonly T[], query: string, fields: (item: T) => string[]): Ranked<T>[] {
  const out: Ranked<T>[] = []
  for (const item of items) {
    const texts = fields(item)
    const primary = fuzzyMatch(query, texts[0] ?? '')
    let best = primary
    let bonus = 0
    for (const extra of texts.slice(1)) {
      const hit = fuzzyMatch(query, extra)
      if (hit) bonus = Math.max(bonus, hit.score * 0.4)
    }
    if (!best && bonus === 0 && query.trim()) continue
    best ??= { score: 0, positions: [] }
    out.push({ item, score: best.score + bonus, positions: primary ? primary.positions : [] })
  }
  return out.sort((a, b) => b.score - a.score)
}

export interface Segment {
  text: string
  hit: boolean
}

/** Teilt den Text in Stücke mit/ohne Treffer – für `<mark>`-freies Hervorheben. */
export function highlight(text: string, positions: readonly number[]): Segment[] {
  if (!positions.length) return [{ text, hit: false }]
  const set = new Set(positions)
  const out: Segment[] = []
  for (let i = 0; i < text.length; i++) {
    const hit = set.has(i)
    const last = out[out.length - 1]
    if (last && last.hit === hit) last.text += text[i]
    else out.push({ text: text[i]!, hit })
  }
  return out
}
