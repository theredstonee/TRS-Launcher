// JSON und JSON5 (auch JSON mit Kommentaren, wie viele Mods es schreiben).
// Rekursiver Abstieg, der sich zu jedem Wert die genaue Textstelle merkt.
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
} from './types'

type Node =
  | { t: 'obj'; start: number; end: number; members: Member[] }
  | { t: 'arr'; start: number; end: number; items: Node[] }
  | { t: 'str'; start: number; end: number; value: string; quote: '"' | "'" }
  | { t: 'num'; start: number; end: number; value: number | null; float: boolean }
  | { t: 'bool'; start: number; end: number; value: boolean }
  | { t: 'null'; start: number; end: number }

interface Member {
  key: string
  keyStart: number
  value: Node
  comments: string[]
}

const NUMBER_RE = /[+-]?(?:Infinity|NaN|0[xX][0-9a-fA-F]+|(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)/y
const DECIMAL_RE = /^-?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/
const IDENT_RE = /[A-Za-z_$][\w$]*/y

class Parser {
  pos = 0
  comments: string[] = []
  constructor(
    readonly text: string,
    readonly lines: LineIndex,
  ) {}

  fail(code: ConstructorParameters<typeof ConfigParseError>[0], at = this.pos): never {
    throw new ConfigParseError(code, this.lines.lineOf(Math.min(at, this.text.length)))
  }

  /** Leerraum und Kommentare überspringen; Kommentare merken (Hilfetext für den nächsten Schlüssel). */
  skip() {
    const s = this.text
    while (this.pos < s.length) {
      const c = s[this.pos]!
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === '﻿' || c === ' ') {
        // Leerzeile trennt Kommentare vom nächsten Schlüssel.
        if (c === '\n' && /^[ \t\r]*\n/.test(s.slice(this.pos + 1, this.pos + 40))) this.comments = []
        this.pos++
      } else if (c === '/' && s[this.pos + 1] === '/') {
        const end = s.indexOf('\n', this.pos)
        const stop = end < 0 ? s.length : end
        // Kommentar hinter einem Wert gehört zu diesem, nicht zum nächsten Schlüssel.
        if (!this.trailing()) this.comments.push(s.slice(this.pos + 2, stop).trim())
        this.pos = stop
      } else if (c === '/' && s[this.pos + 1] === '*') {
        const end = s.indexOf('*/', this.pos + 2)
        if (end < 0) this.fail('unclosed')
        if (!this.trailing()) {
          for (const line of s.slice(this.pos + 2, end).split('\n')) this.comments.push(line.replace(/^\s*\*?\s*/, '').trimEnd())
        }
        this.pos = end + 2
      } else break
    }
  }

  /** Steht vor der aktuellen Position in derselben Zeile schon etwas? */
  trailing(): boolean {
    const lineStart = this.text.lastIndexOf('\n', this.pos - 1) + 1
    return /[^\s{[]/.test(this.text.slice(lineStart, this.pos))
  }

  value(): Node {
    this.skip()
    const s = this.text
    const start = this.pos
    const c = s[this.pos]
    if (c === undefined) this.fail('unexpectedEnd')
    if (c === '{') return this.object()
    if (c === '[') return this.array()
    if (c === '"' || c === "'") {
      const value = this.string()
      return { t: 'str', start, end: this.pos, value, quote: c }
    }
    for (const [word, node] of [
      ['true', { t: 'bool', value: true }],
      ['false', { t: 'bool', value: false }],
      ['null', { t: 'null' }],
    ] as const) {
      if (s.startsWith(word, start) && !/[\w$]/.test(s[start + word.length] ?? '')) {
        this.pos = start + word.length
        return { ...node, start, end: this.pos } as Node
      }
    }
    NUMBER_RE.lastIndex = start
    const m = NUMBER_RE.exec(s)
    if (m && !/[\w$.]/.test(s[start + m[0].length] ?? '')) {
      this.pos = start + m[0].length
      const raw = m[0]
      const decimal = DECIMAL_RE.test(raw)
      const value = decimal ? Number(raw) : null
      const safe = value !== null && Number.isFinite(value) && (/[.eE]/.test(raw) || Number.isSafeInteger(value))
      return { t: 'num', start, end: this.pos, value: safe ? value : null, float: /[.eE]/.test(raw) }
    }
    this.fail('invalidValue')
  }

  string(): string {
    const s = this.text
    const quote = s[this.pos]
    let out = ''
    this.pos++
    while (true) {
      const c = s[this.pos]
      if (c === undefined || c === '\n' || c === '\r') this.fail('unterminatedString')
      this.pos++
      if (c === quote) return out
      if (c !== '\\') {
        out += c
        continue
      }
      const e = s[this.pos++]
      switch (e) {
        case 'n': out += '\n'; break
        case 'r': out += '\r'; break
        case 't': out += '\t'; break
        case 'b': out += '\b'; break
        case 'f': out += '\f'; break
        case 'v': out += '\v'; break
        case '0': out += '\0'; break
        case 'u': {
          const hex = s.slice(this.pos, this.pos + 4)
          if (!/^[0-9a-fA-F]{4}$/.test(hex)) this.fail('invalidValue')
          out += String.fromCharCode(parseInt(hex, 16))
          this.pos += 4
          break
        }
        case 'x': {
          const hex = s.slice(this.pos, this.pos + 2)
          if (!/^[0-9a-fA-F]{2}$/.test(hex)) this.fail('invalidValue')
          out += String.fromCharCode(parseInt(hex, 16))
          this.pos += 2
          break
        }
        case '\r':
          // JSON5: Zeilenfortsetzung.
          if (s[this.pos] === '\n') this.pos++
          break
        case '\n':
          break
        case undefined:
          this.fail('unterminatedString')
        default:
          out += e
      }
    }
  }

  object(): Node {
    const start = this.pos
    this.pos++
    this.comments = []
    const members: Member[] = []
    const seen = new Set<string>()
    while (true) {
      this.skip()
      const c = this.text[this.pos]
      if (c === '}') {
        this.pos++
        return { t: 'obj', start, end: this.pos, members }
      }
      if (c === undefined) this.fail('unclosed', start)
      const comments = this.comments
      this.comments = []
      const keyStart = this.pos
      let key: string
      if (c === '"' || c === "'") key = this.string()
      else {
        IDENT_RE.lastIndex = this.pos
        const m = IDENT_RE.exec(this.text)
        if (!m) this.fail('expectedKey')
        key = m[0]
        this.pos += key.length
      }
      if (seen.has(key)) this.fail('duplicateKey', keyStart)
      seen.add(key)
      this.skip()
      if (this.text[this.pos] !== ':') this.fail('expectedSeparator')
      this.pos++
      const value = this.value()
      this.comments = []
      members.push({ key, keyStart, value, comments })
      this.skip()
      const next = this.text[this.pos]
      if (next === ',') this.pos++
      else if (next !== '}') this.fail(next === undefined ? 'unclosed' : 'unexpected', next === undefined ? start : this.pos)
    }
  }

  array(): Node {
    const start = this.pos
    this.pos++
    const items: Node[] = []
    while (true) {
      this.skip()
      const c = this.text[this.pos]
      if (c === ']') {
        this.pos++
        this.comments = []
        return { t: 'arr', start, end: this.pos, items }
      }
      if (c === undefined) this.fail('unclosed', start)
      items.push(this.value())
      this.skip()
      const next = this.text[this.pos]
      if (next === ',') this.pos++
      else if (next !== ']') this.fail(next === undefined ? 'unclosed' : 'unexpected', next === undefined ? start : this.pos)
    }
  }
}

function isScalar(n: Node): n is Extract<Node, { t: 'str' | 'num' | 'bool' }> {
  return n.t === 'str' || n.t === 'bool' || (n.t === 'num' && n.value !== null)
}

function scalarText(n: Extract<Node, { t: 'str' | 'num' | 'bool' }>, text: string): string {
  return n.t === 'str' ? n.value : text.slice(n.start, n.end)
}

class Builder {
  entries: ConfigEntry[] = []
  constructor(
    readonly text: string,
    readonly lines: LineIndex,
  ) {}

  entry(key: string, path: (string | number)[], titles: string[], node: Node, keyStart: number, comments: string[]): ConfigEntry {
    const base = {
      id: entryId(path),
      key,
      groups: titles,
      span: { start: node.start, end: node.end },
      line: this.lines.lineOf(keyStart),
    }
    let entry: ConfigEntry
    if (node.t === 'bool') entry = { ...base, kind: 'bool', value: node.value, style: {} }
    else if (node.t === 'num' && node.value !== null) entry = { ...base, kind: 'number', value: node.value, integer: !node.float, style: { float: node.float } }
    else if (node.t === 'str') entry = { ...base, kind: 'string', value: node.value, style: { quote: node.quote } }
    else if (node.t === 'arr' && node.items.every(isScalar)) {
      const types = new Set(node.items.map((i) => i.t))
      const itemType: ListItemType = types.size === 0 || types.has('str') ? 'string' : types.has('num') ? 'number' : 'bool'
      if (types.size > 1) entry = this.raw(base, node)
      else {
        const items = node.items as Extract<Node, { t: 'str' | 'num' | 'bool' }>[]
        const first = items[0]
        const multiline = this.text.slice(node.start, node.end).includes('\n')
        const keyIndent = indentAt(this.text, keyStart)
        entry = {
          ...base,
          kind: 'list',
          value: items.map((i) => scalarText(i, this.text)),
          itemType,
          style: {
            list: multiline ? 'multiline' : 'inline',
            quote: first?.t === 'str' ? first.quote : '"',
            float: items.some((i) => i.t === 'num' && i.float),
            indent: first && multiline ? indentAt(this.text, first.start) : `${keyIndent}  `,
            closeIndent: closingIndent(this.text, node.end - 1) ?? keyIndent,
            separator: inlineSeparator(this.text, items),
          },
        }
      }
    } else entry = this.raw(base, node)
    applyHints(entry, commentHints(comments))
    this.entries.push(entry)
    return entry
  }

  raw(base: Omit<ConfigEntry, 'kind' | 'value' | 'style'>, node: Node): ConfigEntry {
    const source = this.text.slice(node.start, node.end)
    return { ...base, kind: source.includes('\n') ? 'complex' : 'raw', value: source, style: {} }
  }

  object(node: Extract<Node, { t: 'obj' }>, group: ConfigGroup, path: (string | number)[], titles: string[]) {
    for (const m of node.members) this.member(m.key, m.value, m.keyStart, m.comments, group, [...path, m.key], titles)
  }

  member(key: string, value: Node, keyStart: number, comments: string[], group: ConfigGroup, path: (string | number)[], titles: string[]) {
    const help = commentHints(comments).help
    const line = this.lines.lineOf(keyStart)
    if (value.t === 'obj') {
      const child = newGroup(path, key, line, help)
      group.children.push({ type: 'group', group: child })
      this.object(value, child, path, [...titles, key])
    } else if (value.t === 'arr' && value.items.length > 0 && value.items.every((i) => i.t === 'obj')) {
      // Liste von Objekten → je Element eine Gruppe „key [n]“.
      const child = newGroup(path, key, line, help)
      group.children.push({ type: 'group', group: child })
      value.items.forEach((item, i) => {
        const title = `${key} [${i + 1}]`
        const sub = newGroup([...path, i], title, this.lines.lineOf(item.start))
        child.children.push({ type: 'group', group: sub })
        this.object(item as Extract<Node, { t: 'obj' }>, sub, [...path, i], [...titles, key, title])
      })
    } else {
      group.children.push({ type: 'entry', entry: this.entry(key, path, titles, value, keyStart, comments) })
    }
  }
}

/** Einrückung der schließenden Klammer, wenn sie allein am Zeilenanfang steht. */
function closingIndent(text: string, offset: number): string | undefined {
  const start = text.lastIndexOf('\n', offset - 1) + 1
  const before = text.slice(start, offset)
  return /^[ \t]*$/.test(before) ? before : undefined
}

export function parseJson(text: string, json5: boolean): ConfigDoc {
  const lines = new LineIndex(text)
  const parser = new Parser(text, lines)
  const root = parser.value()
  parser.skip()
  if (parser.pos < text.length) parser.fail('unexpected')
  const builder = new Builder(text, lines)
  const group = newGroup([], '', 1)
  if (root.t === 'obj') builder.object(root, group, [], [])
  else builder.member('', root, root.start, [], group, [], [])
  return { format: json5 ? 'json5' : 'json', root: group, entries: builder.entries, eol: detectEol(text) }
}

/** Zeichenkette im Stil des Originals (JSON: immer doppelte Anführungszeichen). */
export function jsonString(value: string, quote: '"' | "'" | '' | undefined): string {
  if (quote !== "'") return JSON.stringify(value)
  const body = JSON.stringify(value).slice(1, -1).replace(/\\"/g, '"').replace(/'/g, "\\'")
  return `'${body}'`
}
