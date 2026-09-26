// Log-Ansicht der Instanzseite: Zeilen vereinheitlichen (Live-Log aus dem
// Kern und Log-Dateien als Text), Stacktraces gruppieren, filtern, suchen und
// die sichtbaren Zeilen für die virtualisierte Liste bereitstellen.
//
// Bewusst ohne Vue: Das Modell hält 100 000+ Einträge als einfache Objekte
// (keine Proxys), die Komponente bekommt nur einen Zähler, wenn sich etwas
// ändert. Anhängen ist inkrementell – nur neue Zeilen werden gefiltert.
import type { LogLevel, LogLine } from '../types'

export type LevelFilter = 'all' | 'error' | 'warn' | 'info'

export interface LogEntry {
  /** Unix-ms (Live-Log bzw. log4j-XML); sonst `null`. */
  time: number | null
  /** Uhrzeit wie in der Datei („12:34:56“), wenn es keinen Zeitstempel gibt. */
  clock: string | null
  level: LogLevel
  thread: string | null
  /** Erste Zeile der Meldung (ohne Farbcodes). */
  message: string
  /** Folgezeilen: Stacktrace, „Caused by“ … (eingeklappt). */
  detail: string[]
  /** Kleingeschriebener Suchtext – erst bei der ersten Suche berechnet. */
  lower?: string
}

/** Eine Zeile der virtualisierten Liste: Eintrag + Folgezeile (-1 = Kopfzeile). */
export interface LogRow {
  entry: number
  line: number
}

export interface LevelCounts {
  all: number
  error: number
  warn: number
  info: number
}

/** Höchstens so viele Einträge hält die Ansicht (älteste fliegen raus). */
export const MAX_ENTRIES = 150_000

// --- Aufbereiten --------------------------------------------------------------

// Nur feste Muster – nie ein RegExp aus Nutzereingaben.
const ANSI = /\u001b\[[0-9;?]*[ -/]*[@-~]/g
const SECTION = /§[0-9a-fk-orx]/gi
const CONTROL = /[\u0000-\u0008\u000b-\u001f\u007f]/g

/** Entfernt ANSI-Escape-Sequenzen, Minecraft-§-Farbcodes und Steuerzeichen (Tabs bleiben). */
export function cleanText(text: string): string {
  if (!text) return text
  let out = text
  if (out.includes('\u001b')) out = out.replace(ANSI, '')
  if (out.includes('§')) out = out.replace(SECTION, '')
  return out.replace(CONTROL, '')
}

const LEVELS: Record<string, LogLevel> = {
  TRACE: 'trace',
  FINEST: 'trace',
  FINER: 'trace',
  DEBUG: 'debug',
  FINE: 'debug',
  INFO: 'info',
  INFORMATION: 'info',
  CONFIG: 'info',
  WARN: 'warn',
  WARNING: 'warn',
  WARNUNG: 'warn',
  ERROR: 'error',
  SEVERE: 'error',
  SCHWERWIEGEND: 'error',
  FATAL: 'fatal',
}

export function parseLevel(raw: string): LogLevel | null {
  return LEVELS[raw.trim().toUpperCase()] ?? null
}

/** Zu welchem Filter gehört eine Stufe? */
export function levelGroup(level: LogLevel): Exclude<LevelFilter, 'all'> {
  if (level === 'error' || level === 'fatal') return 'error'
  if (level === 'warn') return 'warn'
  return 'info'
}

/**
 * Folgezeile einer vorherigen Meldung? (Stacktrace, „Caused by“, „… 5 more“,
 * nackte Exception-Kopfzeile).
 */
export function isContinuation(line: string): boolean {
  if (/^\s+at\s/.test(line) || /^\tat\s/.test(line)) return true
  if (/^\s*(Caused by|Suppressed):/.test(line)) return true
  if (/^\s*\.\.\. \d+ (more|common frames omitted)/.test(line)) return true
  // „java.lang.IllegalStateException: …“ ohne eigenes Log-Präfix
  if (/^[a-z][\w$]*(\.[\w$]+)+(Exception|Error|Throwable)(: .*)?$/.test(line)) return true
  return false
}

// `[12:34:56] [Render thread/INFO]: …`
// `[25Sep2026 12:34:56.123] [main/INFO] [cpw.mods.modlauncher.Launcher/MODLAUNCHER]: …`
// `[12:34:56] [main/INFO] (FabricLoader/GameProvider) …`
const HEADER = /^\[([^\]]{1,40})\] \[([^\]]{0,200})\/([A-Za-z]{3,13})\](?: \[[^\]]{0,200}\])?:? ?(.*)$/
// java.util.logging (vor 1.7): `2013-01-01 12:00:00 [INFO] …` oder `[INFO] …`
const LEGACY = /^(?:(\d{4}-\d{2}-\d{2} )?(\d{1,2}:\d{2}:\d{2}) )?(?:\[[A-Z]+\] )?\[(SEVERE|WARNING|INFO|FINE|FINER|FINEST|CONFIG)\] ?(.*)$/

function clockOf(raw: string): string | null {
  const m = /(\d{1,2}:\d{2}:\d{2})/.exec(raw)
  return m ? m[1]! : null
}

function makeEntry(level: LogLevel, message: string, thread: string | null = null, time: number | null = null, clock: string | null = null): LogEntry {
  const lines = cleanText(message).split(/\r?\n/)
  return { time, clock, level, thread, message: lines[0] ?? '', detail: lines.slice(1).filter((l) => l.trim() !== '') }
}

/** Eine Zeile einer Log-Datei → Eintrag bzw. `null`, wenn sie an den vorigen gehört. */
export function parseTextLine(raw: string, fallback: LogLevel = 'info'): LogEntry | null {
  const line = cleanText(raw).replace(/\s+$/, '')
  if (!line.trim()) return null
  const m = HEADER.exec(line)
  if (m) {
    const level = parseLevel(m[3]!)
    if (level) return makeEntry(level, m[4] ?? '', m[2] || null, null, clockOf(m[1]!))
  }
  const legacy = LEGACY.exec(line)
  if (legacy) return makeEntry(parseLevel(legacy[3]!) ?? fallback, legacy[4] ?? '', null, null, legacy[2] ?? null)
  if (isContinuation(line)) return null
  // Präfix „WARNING: …“ (JUL-Folgezeile unter Windows)
  const prefix = /^(SEVERE|WARNING|WARNUNG|INFO|INFORMATION|SCHWERWIEGEND): (.*)$/.exec(line)
  if (prefix) return makeEntry(parseLevel(prefix[1]!) ?? fallback, prefix[2] ?? '')
  return makeEntry(fallback, line)
}

function xmlAttr(xml: string, name: string): string | null {
  const m = new RegExp(`\\s${name}="([^"]*)"`).exec(xml.slice(0, xml.indexOf('>') + 1))
  return m ? m[1]! : null
}

function xmlCdata(xml: string, tag: string): string | null {
  const open = `<log4j:${tag}>`
  const start = xml.indexOf(open)
  if (start < 0) return null
  const end = xml.indexOf(`</log4j:${tag}>`, start)
  if (end < 0) return null
  let inner = xml.slice(start + open.length, end).trim()
  if (inner.startsWith('<![CDATA[')) inner = inner.slice(9)
  if (inner.endsWith(']]>')) inner = inner.slice(0, -3)
  return inner.replaceAll(']]]]><![CDATA[>', ']]>')
}

/** log4j-XML-Event (Ausgabe des Spiels auf stdout, z. B. im Launcher-Mitschnitt). */
export function parseXmlEvent(xml: string): LogEntry {
  let message = xmlCdata(xml, 'Message') ?? ''
  const throwable = xmlCdata(xml, 'Throwable')
  if (throwable?.trim()) message += `\n${throwable.trimEnd()}`
  const stamp = Number(xmlAttr(xml, 'timestamp'))
  return makeEntry(parseLevel(xmlAttr(xml, 'level') ?? '') ?? 'info', message, xmlAttr(xml, 'thread'), Number.isFinite(stamp) && stamp > 0 ? stamp : null)
}

/** Live-Zeile aus dem Kern → Eintrag (Throwable steht nach einem Zeilenumbruch in `message`). */
export function fromLogLine(line: LogLine): LogEntry {
  return makeEntry(line.level, line.message, line.thread, line.time)
}

/**
 * Eintrag an den vorigen hängen? Nur Zeilen ohne Thread (stderr, ohne
 * log4j-Präfix), die wie ein Stacktrace aussehen.
 */
export function continuesPrevious(entry: LogEntry): boolean {
  return entry.thread === null && entry.detail.length === 0 && isContinuation(entry.message)
}

/** Ganzen Dateitext zerlegen (für Tests und kleine Dateien; große Dateien: `LogTextParser`). */
export function parseLogText(text: string): LogEntry[] {
  const parser = new LogTextParser()
  const out: LogEntry[] = []
  parser.feed(text, out)
  parser.flush(out)
  return out
}

/** Zeilenweiser Parser für Log-Dateien und Absturzberichte, in Stücken fütterbar. */
export class LogTextParser {
  private xml: string[] | null = null
  private last: LogEntry | null = null
  private rest = ''

  constructor(private readonly fallback: LogLevel = 'info') {}

  /** Hängt fertige Einträge an `out` an; unvollständige letzte Zeile bleibt im Puffer. */
  feed(chunk: string, out: LogEntry[]): void {
    const text = this.rest + chunk
    const lines = text.split('\n')
    this.rest = lines.pop() ?? ''
    for (const line of lines) this.line(line, out)
  }

  flush(out: LogEntry[]): void {
    if (this.rest) this.line(this.rest, out)
    this.rest = ''
    if (this.xml) {
      out.push((this.last = parseXmlEvent(this.xml.join('\n'))))
      this.xml = null
    }
  }

  private line(raw: string, out: LogEntry[]): void {
    const line = raw.replace(/\r$/, '')
    if (this.xml) {
      this.xml.push(line)
      if (line.includes('</log4j:Event>') || this.xml.length > 2000) {
        out.push((this.last = parseXmlEvent(this.xml.join('\n'))))
        this.xml = null
      }
      return
    }
    if (line.trimStart().startsWith('<log4j:Event')) {
      if (line.includes('</log4j:Event>')) out.push((this.last = parseXmlEvent(line)))
      else this.xml = [line]
      return
    }
    const entry = parseTextLine(line, this.fallback)
    if (entry) {
      out.push((this.last = entry))
    } else if (line.trim()) {
      const clean = cleanText(line).replace(/\s+$/, '')
      if (this.last) this.last.detail.push(clean)
      else out.push((this.last = makeEntry(this.fallback, clean)))
    }
  }
}

// --- Suche ----------------------------------------------------------------------

export interface LogSegment {
  text: string
  hit: boolean
}

/** Teilt `text` an allen (groß/klein-unabhängigen) Vorkommen von `needle`. */
export function highlightMatches(text: string, needle: string): LogSegment[] {
  const n = needle.toLowerCase()
  if (!n) return [{ text, hit: false }]
  const lower = text.toLowerCase()
  const out: LogSegment[] = []
  let from = 0
  for (;;) {
    const i = lower.indexOf(n, from)
    if (i < 0) break
    if (i > from) out.push({ text: text.slice(from, i), hit: false })
    out.push({ text: text.slice(i, i + n.length), hit: true })
    from = i + n.length
  }
  if (from < text.length) out.push({ text: text.slice(from), hit: false })
  return out.length ? out : [{ text, hit: false }]
}

function haystack(entry: LogEntry): string {
  return (entry.lower ??= (entry.detail.length ? `${entry.message}\n${entry.detail.join('\n')}` : entry.message).toLowerCase())
}

export function entryMatches(entry: LogEntry, level: LevelFilter, needle: string): boolean {
  if (level !== 'all' && levelGroup(entry.level) !== level) return false
  return !needle || haystack(entry).includes(needle)
}

// --- Modell ---------------------------------------------------------------------

/** Einträge + Filter + sichtbare Zeilen (inkl. aufgeklappter Stacktraces). */
export class LogModel {
  entries: LogEntry[] = []
  rows: LogRow[] = []
  counts: LevelCounts = { all: 0, error: 0, warn: 0, info: 0 }
  /** Anzahl Treffer (Einträge) für den aktuellen Filter. */
  visibleEntries = 0
  private level: LevelFilter = 'all'
  private needle = ''
  private expanded = new Set<LogEntry>()

  get filter(): { level: LevelFilter; needle: string } {
    return { level: this.level, needle: this.needle }
  }

  /** Filter setzen und alles neu berechnen. */
  setFilter(level: LevelFilter, search: string): void {
    this.level = level
    this.needle = search.trim().toLowerCase().slice(0, 200)
    this.rebuild()
  }

  clear(): void {
    this.entries = []
    this.rows = []
    this.expanded.clear()
    this.counts = { all: 0, error: 0, warn: 0, info: 0 }
    this.visibleEntries = 0
  }

  /** Einträge komplett ersetzen (Dateiansicht). */
  replace(entries: LogEntry[]): void {
    this.clear()
    this.entries = entries.length > MAX_ENTRIES ? entries.slice(-MAX_ENTRIES) : entries
    this.rebuild()
  }

  /** Live-Zeilen anhängen: Stacktrace-Zeilen landen beim vorigen Eintrag. */
  appendLines(lines: readonly LogLine[]): void {
    for (const line of lines) {
      const entry = fromLogLine(line)
      const previous = this.entries[this.entries.length - 1]
      if (previous && continuesPrevious(entry)) this.extend(previous, entry.message)
      else this.push(entry)
    }
    if (this.entries.length > MAX_ENTRIES + 10_000) {
      // In großen Schritten kürzen, damit nicht jede Zeile alles neu baut.
      const drop = this.entries.splice(0, this.entries.length - MAX_ENTRIES)
      for (const e of drop) this.expanded.delete(e)
      this.rebuild()
    }
  }

  /** Fertige Einträge anhängen (Datei in Stücken). */
  appendEntries(entries: readonly LogEntry[]): void {
    for (const entry of entries) this.push(entry)
  }

  private push(entry: LogEntry): void {
    this.entries.push(entry)
    this.count(entry, 1)
    if (entryMatches(entry, this.level, this.needle)) {
      this.visibleEntries++
      this.rows.push({ entry: this.entries.length - 1, line: -1 })
    }
  }

  private extend(entry: LogEntry, line: string): void {
    const index = this.entries.length - 1
    const wasVisible = entryMatches(entry, this.level, this.needle)
    entry.detail.push(line)
    entry.lower = undefined
    const visible = entryMatches(entry, this.level, this.needle)
    if (visible && !wasVisible) {
      this.visibleEntries++
      this.rows.push({ entry: index, line: -1 })
    }
    if (visible && this.expanded.has(entry)) this.rows.push({ entry: index, line: entry.detail.length - 1 })
  }

  private count(entry: LogEntry, delta: number): void {
    this.counts.all += delta
    this.counts[levelGroup(entry.level)] += delta
  }

  rebuild(): void {
    this.counts = { all: 0, error: 0, warn: 0, info: 0 }
    const rows: LogRow[] = []
    let visible = 0
    for (let i = 0; i < this.entries.length; i++) {
      const entry = this.entries[i]!
      this.count(entry, 1)
      if (!entryMatches(entry, this.level, this.needle)) continue
      visible++
      rows.push({ entry: i, line: -1 })
      if (this.expanded.has(entry)) for (let d = 0; d < entry.detail.length; d++) rows.push({ entry: i, line: d })
    }
    this.rows = rows
    this.visibleEntries = visible
  }

  isExpanded(index: number): boolean {
    const entry = this.entries[index]
    return !!entry && this.expanded.has(entry)
  }

  /** Stacktrace auf-/zuklappen; liefert die Zeilenposition der Kopfzeile. */
  toggle(index: number): void {
    const entry = this.entries[index]
    if (!entry?.detail.length) return
    const at = this.rows.findIndex((r) => r.entry === index && r.line === -1)
    if (this.expanded.has(entry)) {
      this.expanded.delete(entry)
      if (at >= 0) this.rows.splice(at + 1, entry.detail.length)
    } else {
      this.expanded.add(entry)
      if (at >= 0) this.rows.splice(at + 1, 0, ...entry.detail.map((_, line) => ({ entry: index, line })))
    }
  }

  /** Alle Stacktraces auf- bzw. zuklappen. */
  setAllExpanded(open: boolean): void {
    this.expanded.clear()
    if (open) for (const e of this.entries) if (e.detail.length) this.expanded.add(e)
    this.rebuild()
  }

  /** Sichtbare Einträge als Text (Kopieren). */
  toText(formatTime: (entry: LogEntry) => string): string {
    const out: string[] = []
    let last = -1
    for (const row of this.rows) {
      if (row.entry === last) continue
      last = row.entry
      const e = this.entries[row.entry]!
      const time = formatTime(e)
      const head = `${time ? `[${time}] ` : ''}[${e.thread ?? '-'}/${e.level.toUpperCase()}] ${e.message}`
      out.push(e.detail.length ? `${head}\n${e.detail.join('\n')}` : head)
    }
    return out.join('\n')
  }
}
