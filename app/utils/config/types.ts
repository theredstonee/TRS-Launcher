// Gemeinsames Modell des Config-Editors (Dateibrowser → „Einfach“-Modus).
// Jeder Parser liefert Gruppen + Einträge mit der genauen Textstelle (`span`)
// des Werts. Gespeichert wird nie neu serialisiert, sondern nur die Stellen
// geänderter Werte ersetzt – Kommentare, Reihenfolge und Formatierung bleiben.

import { z } from 'zod'

export type ConfigFormat = 'json' | 'json5' | 'toml' | 'properties' | 'options' | 'forgecfg' | 'yaml'

/**
 * - `raw`: unbekannter/komplexer Wert in einer Zeile → kleines Textfeld mit dem Rohtext
 * - `complex`: mehrzeilige Struktur → nur im erweiterten Modus bearbeitbar
 */
export type EntryKind = 'bool' | 'number' | 'string' | 'enum' | 'list' | 'raw' | 'complex'
export type ListItemType = 'string' | 'number' | 'bool'

export type ConfigValue = boolean | number | string | string[]

export interface Span {
  start: number
  end: number
}

export interface ConfigEntry {
  /** Stabil über erneutes Parsen hinweg (Pfad als JSON). */
  id: string
  key: string
  /** Titel der umgebenden Gruppen (für die Suche). */
  groups: string[]
  kind: EntryKind
  value: ConfigValue
  span: Span
  /** 1-basiert. */
  line: number
  help?: string
  defaultText?: string
  min?: number
  max?: number
  integer?: boolean
  options?: string[]
  /** Listen: Typ der Elemente. */
  itemType?: ListItemType
  /** Format-eigene Angaben zum Zurückschreiben (Anführungszeichen, Listenstil …). */
  style: EntryStyle
}

export interface EntryStyle {
  /** Zeichen um Zeichenketten: `"` / `'` / leer = ohne. */
  quote?: '"' | "'" | ''
  /** Zahl hatte einen Dezimalpunkt (1.0 bleibt 1.0). */
  float?: boolean
  /** YAML: Wortfamilie der Wahrheitswerte (true/yes/on) samt Schreibweise. */
  boolWords?: [string, string]
  /** Listen: `inline` = [a, b], `lines` = je Zeile ein Element (YAML „- a“, Forge „<“). */
  list?: 'inline' | 'multiline' | 'lines' | 'json'
  /** Einrückung der Elemente bei mehrzeiligen Listen. */
  indent?: string
  /** Einrückung der schließenden Klammer bei mehrzeiligen Listen. */
  closeIndent?: string
  /** Trenner bei einzeiligen Listen. */
  separator?: string
  /** Wert war leer (YAML `key:` ohne Wert) – beim Setzen ein Leerzeichen voranstellen. */
  empty?: boolean
  /** YAML-Blockliste: Rest der Schlüsselzeile nach dem Doppelpunkt (z. B. Kommentar). */
  prefix?: string
}

export interface ConfigGroup {
  id: string
  title: string
  help?: string
  line: number
  children: ConfigNode[]
}

export type ConfigNode = { type: 'entry'; entry: ConfigEntry } | { type: 'group'; group: ConfigGroup }

export interface ConfigDoc {
  format: ConfigFormat
  root: ConfigGroup
  /** Alle Einträge flach, in Dateireihenfolge. */
  entries: ConfigEntry[]
  eol: '\n' | '\r\n'
}

export type ParseCode =
  | 'unterminatedString'
  | 'unexpected'
  | 'unexpectedEnd'
  | 'invalidValue'
  | 'expectedKey'
  | 'expectedSeparator'
  | 'duplicateKey'
  | 'unclosed'
  | 'badIndent'
  | 'tab'

export class ConfigParseError extends Error {
  constructor(
    readonly code: ParseCode,
    readonly line: number,
  ) {
    super(`${code} (line ${line})`)
    this.name = 'ConfigParseError'
  }
}

/** Schnelle Zeilennummer zu einer Textposition. */
export class LineIndex {
  private readonly starts: number[] = [0]
  constructor(text: string) {
    for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) this.starts.push(i + 1)
  }
  /** 1-basiert. */
  lineOf(offset: number): number {
    let lo = 0
    let hi = this.starts.length - 1
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1
      if (this.starts[mid]! <= offset) lo = mid
      else hi = mid - 1
    }
    return lo + 1
  }
}

export function detectEol(text: string): '\n' | '\r\n' {
  return text.includes('\r\n') ? '\r\n' : '\n'
}

export function entryId(path: (string | number)[]): string {
  return JSON.stringify(path)
}

export function newGroup(path: (string | number)[], title: string, line: number, help?: string): ConfigGroup {
  return { id: `g${entryId(path)}`, title, line, children: [], ...(help ? { help } : {}) }
}

// --- Zahlen --------------------------------------------------------------------

const INT_RE = /^[+-]?\d+$/
const FLOAT_RE = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/

/** Dezimalzahl als Text → Zahl, sonst `null` (auch bei zu großen Ganzzahlen). */
export function parseNumber(raw: string): { value: number; float: boolean } | null {
  const text = raw.trim()
  if (INT_RE.test(text)) {
    const value = Number(text)
    return Number.isSafeInteger(value) ? { value, float: false } : null
  }
  if (FLOAT_RE.test(text)) {
    const value = Number(text)
    return Number.isFinite(value) ? { value, float: true } : null
  }
  return null
}

/** Zahl zurück in Text; war es vorher eine Kommazahl, bleibt ein `.0`. */
export function formatNumber(value: number, float: boolean | undefined): string {
  if (!Number.isFinite(value)) return '0'
  const text = String(value)
  if (float && Number.isInteger(value) && !/[eE]/.test(text)) return `${text}.0`
  return text
}

// --- Hinweise aus Kommentaren ---------------------------------------------------

export interface CommentHints {
  help?: string
  min?: number
  max?: number
  options?: string[]
  defaultText?: string
}

const NUM = '[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?'

/**
 * Bereich/erlaubte Werte/Standard aus Kommentarzeilen (ohne `#`/`//`):
 * Forge-TOML `Range: 0 ~ 100`, `Range: > 0`, `Allowed Values: A, B`, NeoForge `Default: 5`,
 * Forge-cfg `[range: 1 ~ 64, default: 16]`, `[default: true]`, `Min: 0` / `Max: 10`,
 * `Valid values:` gefolgt von je einem Wert pro Zeile oder `[valid values: A, B]`.
 */
export function commentHints(lines: string[]): CommentHints {
  const hints: CommentHints = {}
  const help: string[] = []
  let collectingValues = false
  const toNum = (s: string) => {
    const n = Number(s)
    return Number.isFinite(n) ? n : undefined
  }
  for (const original of lines) {
    let line = original.trim()
    if (collectingValues) {
      // Forge 1.12: „Valid values:“ und danach je ein Wert pro Kommentarzeile.
      if (line && /^[\w.:\-+/ ]{1,80}$/.test(line) && !/:\s/.test(line)) {
        ;(hints.options ??= []).push(line)
        continue
      }
      collectingValues = false
    }
    let m: RegExpMatchArray | null
    // [range: 1 ~ 64, default: 16] / [default: true] / [valid values: A, B]
    line = line.replace(/\[(range|default|valid values)\s*:[^\]]*\]/gi, (block) => {
      for (const part of block.slice(1, -1).split(/,\s*(?=(?:range|default|valid values)\s*:)/i)) {
        const colon = part.indexOf(':')
        const name = part.slice(0, colon).trim().toLowerCase()
        const value = part.slice(colon + 1).trim()
        if (name === 'default') hints.defaultText = value
        else if (name === 'valid values') hints.options = value.split(',').map((v) => v.trim()).filter(Boolean)
        else {
          const range = value.match(new RegExp(`^(${NUM})\\s*~\\s*(${NUM})$`))
          if (range) {
            hints.min = toNum(range[1]!)
            hints.max = toNum(range[2]!)
          }
        }
      }
      return ''
    })
    line = line.trim()
    if ((m = line.match(new RegExp(`^range:\\s*(${NUM})\\s*~\\s*(${NUM})\\s*$`, 'i')))) {
      hints.min = toNum(m[1]!)
      hints.max = toNum(m[2]!)
      continue
    }
    if ((m = line.match(new RegExp(`^range:\\s*([<>]=?)\\s*(${NUM})\\s*$`, 'i')))) {
      if (m[1]!.startsWith('>')) hints.min = toNum(m[2]!)
      else hints.max = toNum(m[2]!)
      continue
    }
    if ((m = line.match(new RegExp(`^(min|minimum)\\s*:\\s*(${NUM})\\s*$`, 'i')))) {
      hints.min = toNum(m[2]!)
      continue
    }
    if ((m = line.match(new RegExp(`^(max|maximum)\\s*:\\s*(${NUM})\\s*$`, 'i')))) {
      hints.max = toNum(m[2]!)
      continue
    }
    if ((m = line.match(/^(?:allowed|valid) values\s*:\s*(.*)$/i))) {
      const list = m[1]!.trim()
      if (list) hints.options = list.split(',').map((v) => v.trim()).filter(Boolean)
      else collectingValues = true
      continue
    }
    if ((m = line.match(/^default(?: value)?\s*:\s*(.+)$/i))) {
      hints.defaultText = m[1]!.trim()
      continue
    }
    help.push(line)
  }
  // Leere Zeilen am Rand weg, Banner aus ##### / ===== auch.
  const text = help
    .filter((l) => !/^[#=\-*~]{3,}$/.test(l))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim()
  if (text) hints.help = text
  if (hints.min !== undefined && hints.max !== undefined && hints.min > hints.max) {
    delete hints.min
    delete hints.max
  }
  if (hints.options && hints.options.length < 2) delete hints.options
  return hints
}

/** Hinweise auf einen Eintrag anwenden (Bereich nur bei Zahlen, Auswahl nur bei Text). */
export function applyHints(entry: ConfigEntry, hints: CommentHints): ConfigEntry {
  if (hints.help) entry.help = hints.help
  if (hints.defaultText !== undefined) entry.defaultText = hints.defaultText
  if (entry.kind === 'number') {
    if (hints.min !== undefined) entry.min = hints.min
    if (hints.max !== undefined) entry.max = hints.max
  }
  if (entry.kind === 'string' && hints.options && typeof entry.value === 'string') {
    entry.kind = 'enum'
    entry.options = hints.options
  }
  return entry
}

/** Textersetzungen anwenden (dürfen sich nicht überlappen). */
export function applyEdits(text: string, edits: { span: Span; text: string }[]): string {
  const sorted = [...edits].sort((a, b) => b.span.start - a.span.start)
  let out = text
  let limit = Infinity
  for (const edit of sorted) {
    if (edit.span.end > limit) throw new Error('overlapping edits')
    out = out.slice(0, edit.span.start) + edit.text + out.slice(edit.span.end)
    limit = edit.span.start
  }
  return out
}

/** Einrückung der Zeile, in der `offset` liegt. */
export function indentAt(text: string, offset: number): string {
  const start = text.lastIndexOf('\n', offset - 1) + 1
  return /^[ \t]*/.exec(text.slice(start, offset))![0]
}

/** Trenner einer einzeiligen Liste wie im Original („, “ oder „,“). */
export function inlineSeparator(text: string, items: Span[]): string {
  if (items.length < 2) return ', '
  const between = text.slice(items[0]!.end, items[1]!.start)
  return !between.includes('\n') && between.trim() === ',' ? between : ', '
}

/** Liste in Klammern `[…]` (JSON, TOML, YAML-Flow) im Stil des Originals. */
export function bracketList(parts: string[], style: EntryStyle, eol: string): string {
  if (style.list === 'multiline') {
    if (!parts.length) return '[]'
    const indent = style.indent ?? '  '
    return `[${eol}${parts.map((p) => indent + p).join(`,${eol}`)}${eol}${style.closeIndent ?? ''}]`
  }
  return `[${parts.join(style.separator ?? ', ')}]`
}
// --- Antworten des Kerns (read/write_instance_text) ------------------------------

const versionSchema = z.string().regex(/^[0-9a-f]{64}$/)
export const textFileSchema = z.object({
  // 2 MB Bytes ergeben höchstens 2 Mio. UTF-16-Einheiten.
  text: z.string().max(2 * 1024 * 1024),
  version: versionSchema,
  bom: z.boolean(),
  hasBackup: z.boolean(),
})
export const savedTextSchema = z.object({ version: versionSchema, hasBackup: z.boolean() })
export type TextFile = z.infer<typeof textFileSchema>
export type SavedText = z.infer<typeof savedTextSchema>