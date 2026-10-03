// Config-Editor im Dateibrowser: Format erkennen, parsen, Änderungen
// formattreu zurückschreiben (nur die Textstellen geänderter Werte) und das
// Ergebnis zur Sicherheit erneut parsen. Tests: tests/config-editor.test.ts.
import { looksLikeForgeCfg, parseForgeCfg } from './forgecfg'
import { jsonString, parseJson } from './json'
import { optionsString, parseProperties, propertiesString } from './properties'
import { parseToml, tomlString } from './toml'
import { applyEdits, bracketList, ConfigParseError, formatNumber, parseNumber, type ConfigDoc, type ConfigEntry, type ConfigFormat, type ConfigValue } from './types'
import { parseYaml, plainScalar, yamlString } from './yaml'

export * from './types'
export { highlightLine, type HighlightToken } from './highlight'

/** Größter Text, den der Editor öffnet (wie im Kern). */
export const MAX_EDITOR_BYTES = 2 * 1024 * 1024

const BY_EXTENSION: Record<string, ConfigFormat | 'cfg'> = {
  toml: 'toml',
  json: 'json',
  jsonc: 'json',
  json5: 'json5',
  properties: 'properties',
  yml: 'yaml',
  yaml: 'yaml',
  cfg: 'cfg',
}

function kindOfName(name: string): ConfigFormat | 'cfg' | null {
  const lower = name.toLowerCase()
  // options.txt, optionsof.txt (OptiFine), optionsshaders.txt (Iris/OptiFine) …
  if (/^options[\w.-]*\.txt$/.test(lower)) return 'options'
  const dot = lower.lastIndexOf('.')
  if (dot <= 0) return null
  return BY_EXTENSION[lower.slice(dot + 1)] ?? null
}

/** Öffnet der Launcher diese Datei im eigenen Editor? */
export function editorSupports(name: string): boolean {
  return kindOfName(name) !== null
}

/** Format nach Name; `.cfg` nach Inhalt (Forge-Format oder schlicht key=value). */
export function detectFormat(name: string, text: string): ConfigFormat {
  const kind = kindOfName(name)
  if (kind === 'cfg') return looksLikeForgeCfg(text) ? 'forgecfg' : 'properties'
  return kind ?? 'properties'
}

export function parseConfig(format: ConfigFormat, text: string): ConfigDoc {
  switch (format) {
    case 'json':
      return parseJson(text, false)
    case 'json5':
      return parseJson(text, true)
    case 'toml':
      return parseToml(text)
    case 'properties':
    case 'options':
      return parseProperties(text, format)
    case 'forgecfg':
      return parseForgeCfg(text)
    case 'yaml':
      return parseYaml(text)
  }
}

/** Fehler beim Parsen als `{ code, line }` oder `null`, wenn alles passt. */
export function parseProblem(format: ConfigFormat, text: string): ConfigParseError | null {
  try {
    parseConfig(format, text)
    return null
  } catch (e) {
    if (e instanceof ConfigParseError) return e
    return new ConfigParseError('unexpected', 1)
  }
}

// --- Werte prüfen und vergleichen -------------------------------------------------

export type DraftProblem = 'number' | 'integer' | 'bool'

function boolWords(entry: ConfigEntry): [string, string] {
  return entry.style.boolWords ?? ['true', 'false']
}

/** Ungültige Eingabe in einem Feld (oder `null`). Bereichsverletzung ist nur ein Hinweis. */
export function draftProblem(entry: ConfigEntry, value: ConfigValue): DraftProblem | null {
  if (entry.kind === 'number') {
    if (typeof value !== 'number' || !Number.isFinite(value)) return 'number'
    if (entry.integer && !Number.isInteger(value)) return 'integer'
    return null
  }
  if (entry.kind === 'list' && Array.isArray(value)) {
    for (const item of value) {
      const text = item.trim()
      if (entry.itemType === 'number') {
        const num = parseNumber(text)
        if (!num) return 'number'
        if (entry.integer && num.float) return 'integer'
      } else if (entry.itemType === 'bool' && !boolWords(entry).includes(text) && !['true', 'false'].includes(text)) return 'bool'
    }
  }
  return null
}

/** Wert außerhalb von Min/Max (nur Warnung, Speichern bleibt möglich). */
export function outOfRange(entry: ConfigEntry, value: ConfigValue): boolean {
  if (entry.kind !== 'number' || typeof value !== 'number') return false
  return (entry.min !== undefined && value < entry.min) || (entry.max !== undefined && value > entry.max)
}

export function sameValue(a: ConfigValue, b: ConfigValue): boolean {
  if (Array.isArray(a) || Array.isArray(b)) {
    return Array.isArray(a) && Array.isArray(b) && a.length === b.length && a.every((v, i) => v === b[i])
  }
  return a === b
}

/** Listenelemente so, wie sie nach dem Speichern wieder gelesen werden. */
function normalizeItems(doc: ConfigDoc, entry: ConfigEntry, items: string[]): string[] {
  let out = items.map((item) => {
    if (entry.itemType === 'number') {
      const num = parseNumber(item)
      return num ? formatNumber(num.value, num.float) : item.trim()
    }
    if (entry.itemType === 'bool') return item.trim()
    return item
  })
  if (doc.format === 'forgecfg') out = out.map((i) => i.replace(/[\r\n]+/g, ' ').trim()).filter(Boolean)
  return out
}

// --- Zurückschreiben ------------------------------------------------------------

function stringText(doc: ConfigDoc, entry: ConfigEntry, value: string, flow = false): string {
  switch (doc.format) {
    case 'json':
    case 'json5':
      return jsonString(value, doc.format === 'json' ? '"' : entry.style.quote)
    case 'toml':
      return tomlString(value, entry.style.quote)
    case 'yaml':
      return yamlString(value, entry.style.quote, flow)
    case 'properties':
      return propertiesString(value)
    case 'options':
      return optionsString(value)
    case 'forgecfg':
      return value.replace(/[\r\n]+/g, ' ')
  }
}

function itemText(doc: ConfigDoc, entry: ConfigEntry, item: string): string {
  if (entry.itemType === 'number' || entry.itemType === 'bool') return item.trim()
  return stringText(doc, entry, item, entry.style.list === 'inline' && doc.format === 'yaml')
}

/** Neuer Text für die Stelle `entry.span`. */
export function serializeValue(doc: ConfigDoc, entry: ConfigEntry, value: ConfigValue): string {
  const eol = doc.eol
  switch (entry.kind) {
    case 'bool': {
      const [yes, no] = boolWords(entry)
      return value ? yes : no
    }
    case 'number':
      return formatNumber(Number(value), entry.style.float)
    case 'string':
    case 'enum':
      return stringText(doc, entry, String(value))
    case 'raw': {
      const text = String(value).replace(/[\r\n]+/g, ' ')
      return entry.style.empty && text ? ` ${text}` : text
    }
    case 'list': {
      const items = normalizeItems(doc, entry, Array.isArray(value) ? value : [])
      const parts = items.map((i) => itemText(doc, entry, i))
      const style = entry.style
      if (style.list === 'json') return JSON.stringify(items)
      if (doc.format === 'forgecfg') return parts.map((p) => `${style.indent ?? '    '}${p}${eol}`).join('')
      if (style.list === 'lines') {
        const prefix = style.prefix ?? ''
        if (!parts.length) return ` []${prefix.trim() ? ` ${prefix.trim()}` : ''}`
        return prefix + parts.map((p) => `${eol}${style.indent ?? ''}- ${p}`).join('')
      }
      return bracketList(parts, style, eol)
    }
    case 'complex':
      throw new Error('complex values are edited as text')
  }
}

/** Änderung, die nicht so im Text gelandet ist wie gedacht (Feld-ID). */
export class ConfigEditError extends Error {
  constructor(
    readonly entryId: string,
    readonly parse: ConfigParseError | null,
  ) {
    super(`edit of ${entryId} failed`)
    this.name = 'ConfigEditError'
  }
}

/**
 * Entwürfe formattreu in den Text schreiben. Nur die Textstellen geänderter
 * Werte werden ersetzt; danach wird erneut geparst und jeder geänderte Wert
 * nachgeprüft – sonst `ConfigEditError`.
 */
export function applyChanges(doc: ConfigDoc, text: string, drafts: Record<string, ConfigValue>): string {
  const byId = new Map(doc.entries.map((e) => [e.id, e]))
  const edits: { entry: ConfigEntry; text: string; value: ConfigValue }[] = []
  for (const [id, value] of Object.entries(drafts)) {
    const entry = byId.get(id)
    if (!entry || entry.kind === 'complex' || sameValue(entry.value, value)) continue
    edits.push({ entry, value, text: serializeValue(doc, entry, value) })
  }
  if (!edits.length) return text
  const out = applyEdits(
    text,
    edits.map((e) => ({ span: e.entry.span, text: e.text })),
  )
  let check: ConfigDoc
  try {
    check = parseConfig(doc.format, out)
  } catch (e) {
    throw new ConfigEditError(edits[0]!.entry.id, e instanceof ConfigParseError ? e : null)
  }
  const after = new Map(check.entries.map((e) => [e.id, e]))
  for (const edit of edits) {
    const now = after.get(edit.entry.id)
    if (!now) throw new ConfigEditError(edit.entry.id, null)
    if (edit.entry.kind === 'list') {
      const expected = normalizeItems(doc, edit.entry, edit.value as string[])
      if (now.kind !== 'list' || !sameValue(now.value, expected)) throw new ConfigEditError(edit.entry.id, null)
    } else if (out.slice(now.span.start, now.span.end).trim() !== edit.text.trim()) {
      throw new ConfigEditError(edit.entry.id, null)
    }
  }
  return out
}

/** YAML: würde ein Text ohne Anführungszeichen als etwas anderes gelesen? (für Tests/Anzeige) */
export function yamlPlainType(text: string): string {
  return plainScalar(text).type
}
