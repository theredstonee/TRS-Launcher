// TOML (Forge/NeoForge/Fabric-Mods). Eigener kleiner Parser, der sich zu
// jedem Wert die Textstelle merkt; Kommentare über einem Schlüssel werden
// Hilfetext, Forge-Hinweise („Range: 0 ~ 100“, „Allowed Values: …“) Grenzen.
import {
  ConfigParseError,
  LineIndex,
  applyHints,
  commentHints,
  detectEol,
  entryId,
  indentAt,
  inlineSeparator,
  newGroup,
  type ConfigDoc,
  type ConfigEntry,
  type ConfigGroup,
  type ListItemType,
  type ParseCode,
} from './types'

type Value =
  | { t: 'str'; start: number; end: number; value: string; quote: '"' | "'" }
  | { t: 'num'; start: number; end: number; value: number; float: boolean }
  | { t: 'bool'; start: number; end: number; value: boolean }
  | { t: 'arr'; start: number; end: number; items: Value[] }
  /** Inline-Tabelle, Datum, Hex-Zahl, mehrzeiliger String … */
  | { t: 'other'; start: number; end: number }

const BARE_KEY = /[A-Za-z0-9_-]+/y
const DATE_RE = /\d{4}-\d{2}-\d{2}(?:[Tt ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)?(?:[Zz]|[+-]\d{2}:\d{2})?|\d{2}:\d{2}:\d{2}(?:\.\d+)?/y
const TOKEN_RE = /[0-9A-Za-z_+\-.]+/y
const INT_RE = /^[+-]?(?:0|[1-9](?:_?\d)*)$/
const FLOAT_RE = /^[+-]?(?:0|[1-9](?:_?\d)*)(?:\.\d(?:_?\d)*)?(?:[eE][+-]?\d(?:_?\d)*)?$/
const OTHER_NUM_RE = /^(?:0x[0-9A-Fa-f](?:_?[0-9A-Fa-f])*|0o[0-7](?:_?[0-7])*|0b[01](?:_?[01])*|[+-]?(?:inf|nan))$/

class Toml {
  pos = 0
  readonly lines: LineIndex
  readonly root = newGroup([], '', 1)
  readonly entries: ConfigEntry[] = []
  private current: { group: ConfigGroup; path: (string | number)[]; titles: string[] }
  /** Tabellen nach Namenspfad (bei [[x]] das jeweils letzte Element). */
  private readonly tables = new Map<string, { group: ConfigGroup; path: (string | number)[]; titles: string[] }>()
  private readonly arrayCounts = new Map<string, number>()
  private readonly keys = new Map<string, Set<string>>()
  private pending: string[] = []

  constructor(readonly text: string) {
    this.lines = new LineIndex(text)
    this.current = { group: this.root, path: [], titles: [] }
    this.tables.set('', this.current)
  }

  fail(code: ParseCode, at = this.pos): never {
    throw new ConfigParseError(code, this.lines.lineOf(Math.min(at, this.text.length)))
  }

  spaces() {
    while (this.text[this.pos] === ' ' || this.text[this.pos] === '\t') this.pos++
  }

  /** Rest der Zeile: nur Leerraum oder Kommentar erlaubt. */
  endOfLine() {
    this.spaces()
    if (this.text[this.pos] === '#') {
      const nl = this.text.indexOf('\n', this.pos)
      this.pos = nl < 0 ? this.text.length : nl
    }
    if (this.text[this.pos] === '\r') this.pos++
    if (this.pos >= this.text.length) return
    if (this.text[this.pos] !== '\n') this.fail('unexpected')
    this.pos++
  }

  parse(): ConfigDoc {
    const s = this.text
    if (s.charCodeAt(0) === 0xfeff) this.pos = 1
    while (this.pos < s.length) {
      this.spaces()
      const c = s[this.pos]
      if (c === undefined) break
      if (c === '\n' || c === '\r') {
        this.pending = []
        this.endOfLine()
      } else if (c === '#') {
        const nl = s.indexOf('\n', this.pos)
        this.pending.push(s.slice(this.pos + 1, nl < 0 ? s.length : nl).replace(/\r$/, '').replace(/^ /, ''))
        this.pos = nl < 0 ? s.length : nl + 1
      } else if (c === '[') this.header()
      else this.keyValue()
    }
    return { format: 'toml', root: this.root, entries: this.entries, eol: detectEol(s) }
  }

  key(): string[] {
    const parts: string[] = []
    while (true) {
      this.spaces()
      const c = this.text[this.pos]
      if (c === '"' || c === "'") {
        if (this.text.startsWith(c.repeat(3), this.pos)) this.fail('expectedKey')
        parts.push(c === '"' ? this.basic() : this.literal())
      } else {
        BARE_KEY.lastIndex = this.pos
        const m = BARE_KEY.exec(this.text)
        if (!m) this.fail('expectedKey')
        parts.push(m[0])
        this.pos += m[0].length
      }
      this.spaces()
      if (this.text[this.pos] !== '.') return parts
      this.pos++
    }
  }

  header() {
    const start = this.pos
    const array = this.text.startsWith('[[', this.pos)
    this.pos += array ? 2 : 1
    const names = this.key()
    if (!this.text.startsWith(array ? ']]' : ']', this.pos)) this.fail('unclosed', start)
    this.pos += array ? 2 : 1
    this.endOfLine()
    const line = this.lines.lineOf(start)
    const help = commentHints(this.pending).help
    this.pending = []

    // Elterntabellen nötigenfalls anlegen.
    let parent = this.tables.get('')!
    for (let i = 0; i < names.length - 1; i++) parent = this.table(names.slice(0, i + 1), parent, line)
    const name = names[names.length - 1]!
    const id = names.join('\u0000')
    if (array) {
      const n = (this.arrayCounts.get(id) ?? 0) + 1
      this.arrayCounts.set(id, n)
      const path = [...parent.path, name, n - 1]
      const title = `${name} [${n}]`
      const group = newGroup(path, title, line, help)
      parent.group.children.push({ type: 'group', group })
      const entry = { group, path, titles: [...parent.titles, title] }
      this.tables.set(id, entry)
      // Untertabellen des vorigen Elements vergessen.
      for (const key of [...this.tables.keys()]) if (key.startsWith(`${id}\u0000`)) this.tables.delete(key)
      this.current = entry
    } else {
      const table = this.table(names, parent, line, help)
      this.current = table
    }
  }

  table(names: string[], parent: { group: ConfigGroup; path: (string | number)[]; titles: string[] }, line: number, help?: string) {
    const id = names.join('\u0000')
    const known = this.tables.get(id)
    if (known) {
      if (help && !known.group.help) known.group.help = help
      return known
    }
    const name = names[names.length - 1]!
    const path = [...parent.path, name]
    const group = newGroup(path, name, line, help)
    parent.group.children.push({ type: 'group', group })
    const entry = { group, path, titles: [...parent.titles, name] }
    this.tables.set(id, entry)
    return entry
  }

  keyValue() {
    const keyStart = this.pos
    const names = this.key()
    const key = names.join('.')
    if (this.text[this.pos] !== '=') this.fail('expectedSeparator')
    this.pos++
    this.spaces()
    const value = this.value()
    this.endOfLine()

    const groupId = this.current.group.id
    const seen = this.keys.get(groupId) ?? new Set<string>()
    if (seen.has(key)) this.fail('duplicateKey', keyStart)
    seen.add(key)
    this.keys.set(groupId, seen)

    const entry = this.entry(key, [...this.current.path, key], value, keyStart)
    applyHints(entry, commentHints(this.pending))
    this.pending = []
    this.entries.push(entry)
    this.current.group.children.push({ type: 'entry', entry })
  }

  entry(key: string, path: (string | number)[], v: Value, keyStart: number): ConfigEntry {
    const base = { id: entryId(path), key, groups: this.current.titles, span: { start: v.start, end: v.end }, line: this.lines.lineOf(keyStart) }
    if (v.t === 'bool') return { ...base, kind: 'bool', value: v.value, style: {} }
    if (v.t === 'num') return { ...base, kind: 'number', value: v.value, integer: !v.float, style: { float: v.float } }
    if (v.t === 'str') return { ...base, kind: 'string', value: v.value, style: { quote: v.quote } }
    if (v.t === 'arr') {
      const types = new Set(v.items.map((i) => i.t))
      if (types.size <= 1 && !types.has('arr') && !types.has('other')) {
        const items = v.items as Exclude<Value, { t: 'arr' | 'other' }>[]
        const first = items[0]
        const multiline = this.text.slice(v.start, v.end).includes('\n')
        const keyIndent = indentAt(this.text, keyStart)
        const unit = keyIndent.includes('\t') ? '\t' : '  '
        const closeStart = this.text.lastIndexOf('\n', v.end - 2) + 1
        const beforeClose = this.text.slice(closeStart, v.end - 1)
        const itemType: ListItemType = types.has('num') ? 'number' : types.has('bool') ? 'bool' : 'string'
        return {
          ...base,
          kind: 'list',
          value: items.map((i) => (i.t === 'str' ? i.value : this.text.slice(i.start, i.end))),
          itemType,
          style: {
            list: multiline ? 'multiline' : 'inline',
            quote: first?.t === 'str' ? first.quote : '"',
            float: items.some((i) => i.t === 'num' && i.float),
            indent: first && multiline ? indentAt(this.text, first.start) : keyIndent + unit,
            closeIndent: /^[ \t]*$/.test(beforeClose) ? beforeClose : keyIndent,
            separator: inlineSeparator(this.text, items),
          },
        }
      }
    }
    const source = this.text.slice(v.start, v.end)
    return { ...base, kind: source.includes('\n') ? 'complex' : 'raw', value: source, style: {} }
  }

  /** Leerraum, Zeilenumbrüche und Kommentare innerhalb von Arrays. */
  skipInArray() {
    while (this.pos < this.text.length) {
      const c = this.text[this.pos]
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r') this.pos++
      else if (c === '#') {
        const nl = this.text.indexOf('\n', this.pos)
        this.pos = nl < 0 ? this.text.length : nl
      } else break
    }
  }

  value(): Value {
    const s = this.text
    const start = this.pos
    const c = s[start]
    if (c === undefined || c === '\n' || c === '\r') this.fail('invalidValue')
    if (s.startsWith('"""', start) || s.startsWith("'''", start)) {
      const q = s.slice(start, start + 3)
      let i = start + 3
      while (true) {
        const end = s.indexOf(q, i)
        if (end < 0) this.fail('unterminatedString', start)
        // Escapes zählen (nur bei """): ungerade Zahl an \ davor → maskiert.
        let slashes = 0
        while (q === '"""' && s[end - 1 - slashes] === '\\') slashes++
        if (slashes % 2 === 0) {
          let close = end + 3
          // Bis zu zwei zusätzliche Anführungszeichen gehören noch zum Inhalt.
          while (s[close] === q[0] && close < end + 5) close++
          this.pos = close
          return { t: 'other', start, end: close }
        }
        i = end + 1
      }
    }
    if (c === '"') return { t: 'str', start, value: this.basic(), end: this.pos, quote: '"' }
    if (c === "'") return { t: 'str', start, value: this.literal(), end: this.pos, quote: "'" }
    if (c === '[') {
      this.pos++
      const items: Value[] = []
      while (true) {
        this.skipInArray()
        if (s[this.pos] === ']') {
          this.pos++
          return { t: 'arr', start, end: this.pos, items }
        }
        if (this.pos >= s.length) this.fail('unclosed', start)
        items.push(this.value())
        this.skipInArray()
        if (s[this.pos] === ',') this.pos++
        else if (s[this.pos] !== ']') this.fail(this.pos >= s.length ? 'unclosed' : 'unexpected', this.pos >= s.length ? start : this.pos)
      }
    }
    if (c === '{') {
      // Inline-Tabelle: nur Klammern zählen (Strings beachten), Inhalt bleibt Rohtext.
      let depth = 0
      for (let i = start; i < s.length; i++) {
        const ch = s[i]
        if (ch === '"' || ch === "'") {
          this.pos = i
          if (ch === '"') this.basic()
          else this.literal()
          i = this.pos - 1
        } else if (ch === '{' || ch === '[') depth++
        else if (ch === '}' || ch === ']') {
          depth--
          if (depth === 0) {
            this.pos = i + 1
            return { t: 'other', start, end: this.pos }
          }
        } else if (ch === '#') {
          const nl = s.indexOf('\n', i)
          i = (nl < 0 ? s.length : nl) - 1
        }
      }
      this.fail('unclosed', start)
    }
    for (const word of ['true', 'false'] as const) {
      if (s.startsWith(word, start) && !/[\w-]/.test(s[start + word.length] ?? '')) {
        this.pos = start + word.length
        return { t: 'bool', start, end: this.pos, value: word === 'true' }
      }
    }
    DATE_RE.lastIndex = start
    const date = DATE_RE.exec(s)
    if (date && !/[\w:.-]/.test(s[start + date[0].length] ?? '')) {
      this.pos = start + date[0].length
      return { t: 'other', start, end: this.pos }
    }
    TOKEN_RE.lastIndex = start
    const token = TOKEN_RE.exec(s)?.[0]
    if (!token) this.fail('invalidValue')
    this.pos = start + token.length
    if (INT_RE.test(token) || (FLOAT_RE.test(token) && /[.eE]/.test(token))) {
      const value = Number(token.replace(/_/g, ''))
      const float = /[.eE]/.test(token)
      if (Number.isFinite(value) && (float || Number.isSafeInteger(value))) return { t: 'num', start, end: this.pos, value, float }
      return { t: 'other', start, end: this.pos }
    }
    if (OTHER_NUM_RE.test(token)) return { t: 'other', start, end: this.pos }
    this.fail('invalidValue', start)
  }

  basic(): string {
    const s = this.text
    const start = this.pos
    this.pos++
    let out = ''
    while (true) {
      const c = s[this.pos]
      if (c === undefined || c === '\n' || c === '\r') this.fail('unterminatedString', start)
      this.pos++
      if (c === '"') return out
      if (c !== '\\') {
        out += c
        continue
      }
      const e = s[this.pos++]
      const simple: Record<string, string> = { b: '\b', t: '\t', n: '\n', f: '\f', r: '\r', e: '\x1b', '"': '"', '\\': '\\' }
      if (e !== undefined && e in simple) out += simple[e]
      else if (e === 'u' || e === 'U') {
        const len = e === 'u' ? 4 : 8
        const hex = s.slice(this.pos, this.pos + len)
        const code = /^[0-9a-fA-F]+$/.test(hex) && hex.length === len ? parseInt(hex, 16) : NaN
        if (!(code <= 0x10ffff)) this.fail('invalidValue')
        out += String.fromCodePoint(code)
        this.pos += len
      } else this.fail('invalidValue', this.pos - 1)
    }
  }

  literal(): string {
    const s = this.text
    const start = this.pos
    let i = start + 1
    while (i < s.length && s[i] !== "'" && s[i] !== '\n') i++
    if (s[i] !== "'") this.fail('unterminatedString', start)
    this.pos = i + 1
    return s.slice(start + 1, i)
  }
}

export function parseToml(text: string): ConfigDoc {
  return new Toml(text).parse()
}

/** TOML-Zeichenkette: Literal-Stil bleibt, wenn der Wert es zulässt. */
export function tomlString(value: string, quote: '"' | "'" | '' | undefined): string {
  if (quote === "'" && !/['\u0000-\u001f\u007f]/.test(value)) return `'${value}'`
  return JSON.stringify(value)
}
