// YAML in dem Umfang, den Plugin-/Mod-Configs nutzen (Bukkit/Paper, Fabric-Mods):
// Block-Mappings, Blocklisten „- a“, Flow-Listen „[a, b]“, Kommentare,
// Anführungszeichen. Alles Exotische (Block-Scalars, verschachtelte Flows,
// mehrzeilige Strings) wird als „nur im erweiterten Modus“ markiert.
import {
  ConfigParseError,
  LineIndex,
  applyHints,
  commentHints,
  detectEol,
  entryId,
  newGroup,
  type CommentHints,
  type ConfigDoc,
  type ConfigEntry,
  type ConfigGroup,
  type ListItemType,
  type ParseCode,
} from './types'

interface Line {
  start: number
  end: number
  indent: number
  kind: 'blank' | 'comment' | 'marker' | 'content'
}

type ScalarType = 'string' | 'number' | 'bool' | 'null'
interface Scalar {
  type: ScalarType
  text: string
  quote: '"' | "'" | ''
  value: string | number | boolean
  float?: boolean
  words?: [string, string]
}

const BOOL_WORDS: Record<string, [string, string]> = {}
for (const [yes, no] of [
  ['true', 'false'],
  ['yes', 'no'],
  ['on', 'off'],
]) {
  for (const f of [(s: string) => s, (s: string) => s[0]!.toUpperCase() + s.slice(1), (s: string) => s.toUpperCase()]) {
    BOOL_WORDS[f(yes!)] = [f(yes!), f(no!)]
    BOOL_WORDS[f(no!)] = [f(yes!), f(no!)]
  }
}
const INT_RE = /^[-+]?(?:0|[1-9][0-9_]*)$/
const FLOAT_RE = /^[-+]?(?:\.[0-9]+|[0-9][0-9_]*(?:\.[0-9_]*)?)(?:[eE][-+]?[0-9]+)?$/

/** Typ eines ungequoteten Scalars (YAML 1.1 wie SnakeYAML in Bukkit). */
export function plainScalar(text: string): Scalar {
  const words = BOOL_WORDS[text]
  if (words) return { type: 'bool', text, quote: '', value: text === words[0], words }
  if (text === '' || text === '~' || /^(null|Null|NULL)$/.test(text)) return { type: 'null', text, quote: '', value: text }
  if (INT_RE.test(text)) {
    const value = Number(text.replace(/_/g, ''))
    if (Number.isSafeInteger(value)) return { type: 'number', text, quote: '', value, float: false }
  } else if (FLOAT_RE.test(text) && /[.eE]/.test(text) && /\d/.test(text)) {
    const value = Number(text.replace(/_/g, ''))
    if (Number.isFinite(value)) return { type: 'number', text, quote: '', value, float: true }
  }
  return { type: 'string', text, quote: '', value: text }
}

function decodeDouble(raw: string): string {
  try {
    return JSON.parse(raw) as string
  } catch {
    return raw
      .slice(1, -1)
      .replace(/\\(x[0-9a-fA-F]{2}|u[0-9a-fA-F]{4}|.)/g, (_, e: string) => {
        if (e.length > 1) return String.fromCharCode(parseInt(e.slice(1), 16))
        return ({ n: '\n', t: '\t', r: '\r', '0': '\0', e: '\x1b', _: ' ', N: '\u0085', b: '\b' } as Record<string, string>)[e] ?? e
      })
  }
}

/** YAML-Zeichenkette: Stil des Originals, Anführungszeichen nur wenn nötig. */
export function yamlString(value: string, quote: '"' | "'" | '' | undefined, flow = false): string {
  if (quote === '"' || /[\u0000-\u001f\u007f]/.test(value)) return JSON.stringify(value)
  const risky =
    value === '' ||
    /^\s|\s$/.test(value) ||
    /^[-?:,[\]{}#&*!|>'"%@`]/.test(value) ||
    /:(\s|$)|\s#/.test(value) ||
    (flow && /[,[\]{}]/.test(value)) ||
    plainScalar(value).type !== 'string'
  if (quote === "'" || risky) return `'${value.replace(/'/g, "''")}'`
  return value
}

class Yaml {
  readonly lines: Line[] = []
  readonly index: LineIndex
  readonly entries: ConfigEntry[] = []
  i = 0
  pending: string[] = []

  constructor(readonly text: string) {
    this.index = new LineIndex(text)
    let pos = text.charCodeAt(0) === 0xfeff ? 1 : 0
    while (pos <= text.length) {
      let nl = text.indexOf('\n', pos)
      if (nl < 0) nl = text.length
      const end = text[nl - 1] === '\r' ? nl - 1 : nl
      const line = text.slice(pos, end)
      const lead = /^[ \t]*/.exec(line)![0]
      const content = line.slice(lead.length)
      let kind: Line['kind'] = 'content'
      if (!content) kind = 'blank'
      else if (content.startsWith('#')) kind = 'comment'
      else if (!lead && (/^(---|\.\.\.)(\s|$)/.test(line) || line.startsWith('%'))) kind = 'marker'
      if (kind === 'content' && lead.includes('\t')) this.fail('tab', pos)
      this.lines.push({ start: pos, end, indent: lead.length, kind })
      if (nl >= text.length) break
      pos = nl + 1
    }
  }

  fail(code: ParseCode, at: number): never {
    throw new ConfigParseError(code, this.index.lineOf(Math.min(at, this.text.length)))
  }

  /** Leer-/Kommentarzeilen überspringen, Kommentare als Hilfetext sammeln. */
  trivia() {
    while (this.i < this.lines.length) {
      const line = this.lines[this.i]!
      if (line.kind === 'content') return
      if (line.kind === 'comment') this.pending.push(this.text.slice(line.start + line.indent + 1, line.end).replace(/^ /, ''))
      else this.pending = []
      this.i++
    }
  }

  nextContent(): Line | undefined {
    for (let j = this.i; j < this.lines.length; j++) if (this.lines[j]!.kind === 'content') return this.lines[j]
    return undefined
  }

  isSeqItem(line: Line, col = line.indent): boolean {
    return this.text[line.start + col] === '-' && /^[ \t]|^$/.test(this.text.slice(line.start + col + 1, line.end).slice(0, 1))
  }

  /** Zeilen tiefer als `col` (und Leerzeilen) überspringen; Ende der letzten übersprungenen Zeile. */
  skipBlock(col: number, lastEnd: number): number {
    while (this.i < this.lines.length) {
      const line = this.lines[this.i]!
      if (line.kind !== 'blank' && line.indent <= col) break
      if (line.kind !== 'blank') lastEnd = line.end
      this.i++
    }
    return lastEnd
  }

  /** Position hinter dem schließenden Anführungszeichen (auch über Zeilen) oder -1. */
  quoteEnd(p: number): number {
    const s = this.text
    const q = s[p]
    for (let k = p + 1; k < s.length; k++) {
      if (q === '"' && s[k] === '\\') k++
      else if (s[k] === q) {
        if (q === "'" && s[k + 1] === "'") k++
        else return k + 1
      }
    }
    return -1
  }

  /** Position hinter der passenden schließenden Klammer (auch über Zeilen) oder -1. */
  flowEnd(p: number): number {
    const s = this.text
    let depth = 0
    for (let k = p; k < s.length; k++) {
      const c = s[k]
      if (c === '"' || c === "'") {
        const end = this.quoteEnd(k)
        if (end < 0) return -1
        k = end - 1
      } else if (c === '[' || c === '{') depth++
      else if (c === ']' || c === '}') {
        depth--
        if (depth === 0) return k + 1
      }
    }
    return -1
  }

  /** Nach einem Wert in derselben Zeile ist nur ein Kommentar erlaubt. */
  expectLineEnd(p: number, line: Line) {
    const rest = this.text.slice(p, line.end)
    if (rest.trim() && !/^[ \t]+#|^#/.test(rest)) this.fail('unexpected', p)
  }

  scalarAt(p: number, end: number): Scalar | null {
    const s = this.text
    const c = s[p]
    if (c === '"' || c === "'") {
      const close = this.quoteEnd(p)
      if (close < 0 || close > end) return null
      const raw = s.slice(p, close)
      const value = c === '"' ? decodeDouble(raw) : raw.slice(1, -1).replace(/''/g, "'")
      return { type: 'string', text: value, quote: c, value }
    }
    const rest = s.slice(p, end)
    const hash = rest.search(/[ \t]#/)
    return plainScalar((hash >= 0 ? rest.slice(0, hash) : rest).trimEnd())
  }

  /** Flow-Liste `[a, 'b', 3]` aus einfachen Werten, sonst `null`. */
  flowItems(inner: string): Scalar[] | null {
    const parts: string[] = []
    let current = ''
    for (let k = 0; k < inner.length; k++) {
      const c = inner[k]!
      if (c === '"' || c === "'") {
        let j = k + 1
        while (j < inner.length) {
          if (c === '"' && inner[j] === '\\') j += 2
          else if (inner[j] === c && !(c === "'" && inner[j + 1] === "'")) break
          else j += c === "'" && inner[j] === "'" ? 2 : 1
        }
        current += inner.slice(k, j + 1)
        k = j
      } else if (c === '[' || c === '{' || c === '#') return null
      else if (c === ',') {
        parts.push(current)
        current = ''
      } else current += c
    }
    parts.push(current)
    if (!parts[parts.length - 1]!.trim()) parts.pop()
    const out: Scalar[] = []
    for (const part of parts) {
      const text = part.trim()
      if (!text) return null
      if (text[0] === '"' || text[0] === "'") {
        if (text.length < 2 || text[text.length - 1] !== text[0]) return null
        const value = text[0] === '"' ? decodeDouble(text) : text.slice(1, -1).replace(/''/g, "'")
        out.push({ type: 'string', text: value, quote: text[0], value })
      } else out.push(plainScalar(text))
    }
    return out
  }

  parse(): ConfigDoc {
    const root = newGroup([], '', 1)
    this.trivia()
    const first = this.nextContent()
    if (first) {
      if (first.indent > 0) this.fail('badIndent', first.start)
      if (this.isSeqItem(first)) this.sequence(0, '', [], root, [], {}, first.start, '', true)
      else this.mapping(0, root, [], [])
    }
    this.trivia()
    if (this.i < this.lines.length) this.fail('badIndent', this.lines[this.i]!.start)
    return { format: 'yaml', root, entries: this.entries, eol: detectEol(this.text) }
  }

  mapping(col: number, group: ConfigGroup, path: (string | number)[], titles: string[], firstOffset?: number) {
    const counts = new Map<string, number>()
    let offset = firstOffset
    while (true) {
      let line: Line
      if (offset !== undefined) line = this.lines[this.i]!
      else {
        this.trivia()
        if (this.i >= this.lines.length) return
        line = this.lines[this.i]!
        if (line.indent < col) return
        if (line.indent > col) this.fail('badIndent', line.start)
        if (this.isSeqItem(line)) this.fail('unexpected', line.start)
        offset = line.start + line.indent
      }
      this.keyLine(line, offset, col, group, path, titles, counts)
      offset = undefined
    }
  }

  keyLine(line: Line, p: number, col: number, group: ConfigGroup, path: (string | number)[], titles: string[], counts: Map<string, number>) {
    const s = this.text
    let key: string
    const c = s[p]
    if (c === '"' || c === "'") {
      const close = this.quoteEnd(p)
      if (close < 0 || close > line.end) this.fail('unterminatedString', p)
      const raw = s.slice(p, close)
      key = c === '"' ? decodeDouble(raw) : raw.slice(1, -1).replace(/''/g, "'")
      p = close
      while (s[p] === ' ' || s[p] === '\t') p++
      if (s[p] !== ':') this.fail('expectedSeparator', p)
      p++
    } else {
      if (c === '?' || c === '[' || c === '{') this.fail('unexpected', p)
      const rest = s.slice(p, line.end)
      const colon = /:(?=[ \t]|$)/.exec(rest)
      const hash = rest.search(/(^|[ \t])#/)
      if (!colon || (hash >= 0 && hash < colon.index)) this.fail('expectedSeparator', p)
      key = rest.slice(0, colon.index).trim()
      if (!key) this.fail('expectedKey', p)
      p += colon.index + 1
    }
    const hints = commentHints(this.pending)
    this.pending = []
    const n = counts.get(key) ?? 0
    counts.set(key, n + 1)
    const entryPath = [...path, n ? `${key}#${n}` : key]
    this.i++
    this.value(p, line, col, key, entryPath, group, titles, hints, true)
  }

  /** Wert ab `p` in Zeile `line` (die Zeile ist schon verbraucht). */
  value(p: number, line: Line, col: number, key: string, path: (string | number)[], group: ConfigGroup, titles: string[], hints: CommentHints, inMapping: boolean) {
    const s = this.text
    let q = p
    while (s[q] === ' ' || s[q] === '\t') q++
    // Anker/Tag vor einem Block (`base: &base`) ignorieren.
    const prop = /^[&!]\S*/.exec(s.slice(q, line.end))
    const afterProp = prop ? s.slice(q + prop[0].length, line.end).trim() : null
    const rest = s.slice(q, line.end)
    const lineNo = this.index.lineOf(line.start)
    const base = { id: entryId(path), key, groups: titles, line: lineNo }
    const push = (entry: ConfigEntry) => {
      applyHints(entry, hints)
      this.entries.push(entry)
      group.children.push({ type: 'entry', entry })
    }

    if (!rest || rest.startsWith('#') || (prop && (!afterProp || afterProp.startsWith('#')))) {
      const next = this.nextContent()
      if (next && next.indent > col) {
        if (this.isSeqItem(next)) this.sequence(next.indent, key, path, group, titles, hints, p, s.slice(p, line.end), false)
        else {
          const child = newGroup(path, key, lineNo, hints.help)
          group.children.push({ type: 'group', group: child })
          this.mapping(next.indent, child, path, [...titles, key])
        }
        return
      }
      if (next && inMapping && next.indent === col && this.isSeqItem(next)) {
        this.sequence(col, key, path, group, titles, hints, p, s.slice(p, line.end), false)
        return
      }
      if (prop) push({ ...base, kind: 'raw', value: prop[0], span: { start: q, end: q + prop[0].length }, style: {} })
      else push({ ...base, kind: 'raw', value: '', span: { start: p, end: p }, style: { empty: true } })
      return
    }

    const c = rest[0]
    if (c === '|' || c === '>') {
      const end = this.skipBlock(col, line.end)
      push({ ...base, kind: 'complex', value: s.slice(q, end), span: { start: q, end }, style: {} })
      return
    }
    if (c === '[' || c === '{') {
      const end = this.flowEnd(q)
      if (end < 0) this.fail('unclosed', q)
      if (end <= line.end) {
        this.expectLineEnd(end, line)
        const items = c === '[' ? this.flowItems(s.slice(q + 1, end - 1)) : null
        const list = items && this.listEntry(base, items, { start: q, end })
        if (list) {
          // Trenner wie im Original („, “ oder „,“).
          const inner = s.slice(q + 1, end - 1)
          list.style = { ...list.style, list: 'inline', separator: inner.includes(',') && !/,\s/.test(inner) ? ',' : ', ' }
          push(list)
        } else push({ ...base, kind: 'raw', value: s.slice(q, end), span: { start: q, end }, style: {} })
        return
      }
      // Mehrzeiliger Flow-Wert.
      while (this.i < this.lines.length && this.lines[this.i]!.start < end) this.i++
      this.expectLineEnd(end, this.lines[this.i - 1]!)
      push({ ...base, kind: 'complex', value: s.slice(q, end), span: { start: q, end }, style: {} })
      return
    }
    if (c === '"' || c === "'") {
      const close = this.quoteEnd(q)
      if (close < 0) this.fail('unterminatedString', q)
      if (close <= line.end) {
        this.expectLineEnd(close, line)
        const scalar = this.scalarAt(q, close)!
        push({ ...base, kind: 'string', value: scalar.text, span: { start: q, end: close }, style: { quote: scalar.quote } })
        return
      }
      while (this.i < this.lines.length && this.lines[this.i]!.start < close) this.i++
      this.expectLineEnd(close, this.lines[this.i - 1]!)
      push({ ...base, kind: 'complex', value: s.slice(q, close), span: { start: q, end: close }, style: {} })
      return
    }
    // Ungequoteter Wert bis zum Kommentar.
    const hash = rest.search(/[ \t]#/)
    const text = (hash >= 0 ? rest.slice(0, hash) : rest).trimEnd()
    const span = { start: q, end: q + text.length }
    const next = this.lines[this.i]
    if (next && next.kind === 'content' && next.indent > col) {
      // Tiefer eingerückter Schlüssel → Einrückungsfehler, sonst mehrzeiliger Text.
      if (/^[^#\s'"][^#]*?:(\s|$)/.test(s.slice(next.start + next.indent, next.end))) this.fail('badIndent', next.start)
      const end = this.skipBlock(col, line.end)
      push({ ...base, kind: 'complex', value: s.slice(q, end), span: { start: q, end }, style: {} })
      return
    }
    if (c === '*' || c === '&' || c === '!') {
      push({ ...base, kind: 'raw', value: text, span, style: {} })
      return
    }
    const scalar = plainScalar(text)
    if (scalar.type === 'bool') push({ ...base, kind: 'bool', value: scalar.value, span, style: { boolWords: scalar.words } })
    else if (scalar.type === 'number') push({ ...base, kind: 'number', value: scalar.value, integer: !scalar.float, span, style: { float: scalar.float } })
    else if (scalar.type === 'null') push({ ...base, kind: 'raw', value: text, span, style: {} })
    else push({ ...base, kind: 'string', value: text, span, style: { quote: '' } })
  }

  listEntry(base: Omit<ConfigEntry, 'kind' | 'value' | 'style' | 'span'>, items: Scalar[], span: { start: number; end: number }): ConfigEntry | null {
    const types = new Set(items.map((i) => i.type))
    if (types.has('null') || types.size > 1) return null
    const itemType: ListItemType = types.has('number') ? 'number' : types.has('bool') ? 'bool' : 'string'
    const first = items[0]
    return {
      ...base,
      span,
      kind: 'list',
      value: items.map((i) => i.text),
      itemType,
      style: {
        quote: first?.quote ?? '',
        float: items.some((i) => i.float),
        ...(first?.words ? { boolWords: first.words } : {}),
      },
    }
  }

  /** Blockliste bei Spalte `col`. `start` = Ende des Doppelpunkts der Schlüsselzeile. */
  sequence(col: number, key: string, path: (string | number)[], group: ConfigGroup, titles: string[], hints: CommentHints, start: number, prefix: string, isRoot: boolean) {
    const s = this.text
    const snapshot = this.entries.length
    const lineNo = this.index.lineOf(start)
    const holder = newGroup(path, key, lineNo, hints.help)
    const scalars: Scalar[] = []
    let maps = 0
    let other = 0
    let lastEnd = start
    let index = 0
    while (true) {
      this.trivia()
      const line = this.lines[this.i]
      if (!line || line.indent < col) break
      if (line.indent > col) this.fail('badIndent', line.start)
      if (!this.isSeqItem(line)) break
      // Kommentare zwischen Listeneinträgen gehören zur Liste.
      this.pending = []
      let p = line.start + col + 1
      while (s[p] === ' ' || s[p] === '\t') p++
      const rest = s.slice(p, line.end)
      const title = `${key || '#'} [${index + 1}]`
      const itemPath = [...path, index]
      if (!rest || rest.startsWith('#')) {
        this.i++
        const next = this.nextContent()
        if (next && next.indent > col && !this.isSeqItem(next)) {
          const sub = newGroup(itemPath, title, this.index.lineOf(next.start))
          holder.children.push({ type: 'group', group: sub })
          this.pending = []
          this.mapping(next.indent, sub, itemPath, [...titles, key, title].filter(Boolean))
          maps++
        } else {
          other++
          this.skipBlock(col, line.end)
        }
      } else if (/^(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[^#'"[\]{},\s][^#]*?)[ \t]*:(?:[ \t]|$)/.test(rest) && !/^[&*!|>]/.test(rest)) {
        // „- key: value“ → Mapping in Spalte hinter dem Strich.
        const sub = newGroup(itemPath, title, this.index.lineOf(line.start))
        holder.children.push({ type: 'group', group: sub })
        this.pending = []
        this.mapping(p - line.start, sub, itemPath, [...titles, key, title].filter(Boolean), p)
        maps++
      } else {
        this.i++
        const scalar = /^[[{|>&*!]/.test(rest) ? null : this.scalarAt(p, line.end)
        const next = this.lines[this.i]
        if (!scalar || (next && next.kind === 'content' && next.indent > col)) {
          other++
          this.skipBlock(col, line.end)
        } else {
          if (scalar.quote) this.expectLineEnd(this.quoteEnd(p), line)
          scalars.push(scalar)
        }
      }
      // Ende der letzten Inhaltszeile (ohne nachfolgende Leerzeilen).
      for (let j = this.i - 1; j >= 0; j--) {
        if (this.lines[j]!.kind !== 'blank') {
          lastEnd = this.lines[j]!.end
          break
        }
      }
      index++
    }
    const base = { id: entryId(path), key, groups: titles, line: lineNo }
    if (maps && !scalars.length && !other) {
      group.children.push({ type: 'group', group: holder })
      return
    }
    this.entries.length = snapshot
    const list = !maps && !other && !isRoot ? this.listEntry(base, scalars, { start, end: lastEnd }) : null
    const entry: ConfigEntry = list
      ? { ...list, style: { ...list.style, list: 'lines', indent: ' '.repeat(col), prefix } }
      : { ...base, kind: 'complex', value: s.slice(start, lastEnd), span: { start, end: lastEnd }, style: {} }
    applyHints(entry, hints)
    this.entries.push(entry)
    group.children.push({ type: 'entry', entry })
  }
}

export function parseYaml(text: string): ConfigDoc {
  return new Yaml(text).parse()
}
