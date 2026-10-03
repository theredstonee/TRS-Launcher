// Altes Forge-Format (1.7.10–1.12): `category { … }`, Einträge mit Typ-Präfix
// `B:` (bool), `I:` (int), `D:` (double), `S:` (Text), Listen `S:name <` … `>`.
// Kommentare tragen Hinweise wie „[range: 1 ~ 64, default: 16]“.
import {
  ConfigParseError,
  LineIndex,
  applyHints,
  commentHints,
  detectEol,
  entryId,
  newGroup,
  parseNumber,
  type ConfigDoc,
  type ConfigEntry,
  type ConfigGroup,
  type ListItemType,
} from './types'

const CATEGORY_RE = /^("(?:[^"\\]|\\.)*"|[^\s"{}=<>#]+(?:[ \t]+[^\s"{}=<>#]+)*)[ \t]*\{[ \t]*$/
const PROPERTY_RE = /^([A-Za-z]):("(?:[^"\\]|\\.)*"|[^=<]+?)[ \t]*(=|<)/

function unquote(name: string): string {
  return name.startsWith('"') && name.endsWith('"') && name.length >= 2 ? name.slice(1, -1) : name
}

/** Erkennt das Forge-Format an Kategorien oder Typ-Präfixen. */
export function looksLikeForgeCfg(text: string): boolean {
  return /^[ \t]*[A-Za-z]:(?:"[^"\n]*"|[^=\n<]+)[ \t]*(?:=|<[ \t]*$)/m.test(text) || /^[ \t]*[\w."-]+[ \t]*\{[ \t]*\r?$/m.test(text)
}

export function parseForgeCfg(text: string): ConfigDoc {
  const lines = new LineIndex(text)
  const root = newGroup([], '', 1)
  const entries: ConfigEntry[] = []
  const stack: { group: ConfigGroup; path: string[]; titles: string[]; keys: Set<string> }[] = [{ group: root, path: [], titles: [], keys: new Set() }]
  let pending: string[] = []
  // Forge setzt zwischen Kategorie-Banner und Kategorie eine Leerzeile.
  let banner: string[] = []
  let pos = text.charCodeAt(0) === 0xfeff ? 1 : 0

  const nextLine = () => {
    let nl = text.indexOf('\n', pos)
    if (nl < 0) nl = text.length
    const end = text[nl - 1] === '\r' ? nl - 1 : nl
    const line = { start: pos, end, text: text.slice(pos, end) }
    pos = nl + 1
    return line
  }

  while (pos < text.length) {
    const line = nextLine()
    const trimmed = line.text.trim()
    const lineNo = lines.lineOf(line.start)
    const top = stack[stack.length - 1]!
    if (!trimmed) {
      if (pending.length) banner = pending
      pending = []
      continue
    }
    if (trimmed.startsWith('#')) {
      pending.push(trimmed.slice(1).replace(/^ /, ''))
      continue
    }
    if (trimmed === '}') {
      if (stack.length === 1) throw new ConfigParseError('unexpected', lineNo)
      stack.pop()
      pending = []
      continue
    }
    const category = CATEGORY_RE.exec(trimmed)
    if (category) {
      const name = unquote(category[1]!)
      // Banner wie „####“ und die Wiederholung des Namens sind kein Hilfetext.
      const help = commentHints((pending.length ? pending : banner).filter((p) => p.trim().toLowerCase() !== name.toLowerCase())).help
      pending = []
      banner = []
      const path = [...top.path, name]
      const group = newGroup(path, name, lineNo, help)
      top.group.children.push({ type: 'group', group })
      stack.push({ group, path, titles: [...top.titles, name], keys: new Set() })
      continue
    }
    banner = []
    const prop = PROPERTY_RE.exec(trimmed)
    if (!prop) throw new ConfigParseError('unexpected', lineNo)
    const type = prop[1]!.toUpperCase()
    const key = unquote(prop[2]!.trim())
    if (top.keys.has(key)) throw new ConfigParseError('duplicateKey', lineNo)
    top.keys.add(key)
    const indent = line.text.length - line.text.trimStart().length
    const base = { id: entryId([...top.path, key]), key, groups: top.titles, line: lineNo }
    let entry: ConfigEntry
    if (prop[3] === '<') {
      // Liste: je Zeile ein Element bis zur Zeile mit „>“.
      if (trimmed.slice(prop[0].length).trim()) throw new ConfigParseError('unexpected', lineNo)
      const start = pos
      const items: string[] = []
      let itemIndent: string | undefined
      let closed = false
      let end = start
      while (pos < text.length) {
        const item = nextLine()
        if (item.text.trim() === '>') {
          end = item.start
          closed = true
          break
        }
        itemIndent ??= /^[ \t]*/.exec(item.text)![0]
        if (item.text.trim()) items.push(item.text.trim())
      }
      if (!closed) throw new ConfigParseError('unclosed', lineNo)
      const lineIndent = line.text.slice(0, indent)
      const itemType: ListItemType = type === 'I' || type === 'D' ? 'number' : type === 'B' ? 'bool' : 'string'
      entry = {
        ...base,
        span: { start, end },
        kind: 'list',
        value: items,
        itemType,
        ...(type === 'I' ? { integer: true } : {}),
        style: { list: 'lines', indent: itemIndent ?? `${lineIndent}    `, float: type === 'D' },
      }
    } else {
      const valueStart = line.start + indent + prop[0].length
      const raw = text.slice(valueStart, line.end)
      const span = { start: valueStart, end: line.end }
      const num = parseNumber(raw)
      if (type === 'B' && /^(true|false)$/i.test(raw.trim())) entry = { ...base, span, kind: 'bool', value: raw.trim().toLowerCase() === 'true', style: {} }
      else if ((type === 'I' || type === 'D') && num && raw === raw.trim()) {
        entry = { ...base, span, kind: 'number', value: num.value, integer: type === 'I', style: { float: type === 'D' || num.float } }
      } else if (type === 'S' || type === 'B' || type === 'I' || type === 'D') entry = { ...base, span, kind: type === 'S' ? 'string' : 'raw', value: raw, style: {} }
      else entry = { ...base, span, kind: 'raw', value: raw, style: {} }
    }
    applyHints(entry, commentHints(pending))
    pending = []
    entries.push(entry)
    top.group.children.push({ type: 'entry', entry })
  }
  if (stack.length > 1) throw new ConfigParseError('unclosed', stack[stack.length - 1]!.group.line)
  return { format: 'forgecfg', root, entries, eol: detectEol(text) }
}
