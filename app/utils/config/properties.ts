// `.properties` (Java, z. B. server.properties) und die Minecraft-Optionsdateien
// (options.txt, optionsof.txt, optionsshaders.txt: `key:value` ohne Escapes).
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
} from './types'

/** Java-Escapes einer Properties-Zeile auflösen. */
function unescape(raw: string): string {
  return raw.replace(/\\(u[0-9a-fA-F]{4}|.)/g, (_, e: string) => {
    if (e.length === 5) return String.fromCharCode(parseInt(e.slice(1), 16))
    return ({ n: '\n', t: '\t', r: '\r', f: '\f' } as Record<string, string>)[e] ?? e
  })
}

export function propertiesString(value: string): string {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t')
    .replace(/^ /, '\\ ')
}

/** Optionsdateien: keine Escapes, aber keine Zeilenumbrüche. */
export function optionsString(value: string): string {
  return value.replace(/[\r\n]+/g, ' ')
}

function typed(base: Omit<ConfigEntry, 'kind' | 'value' | 'style'>, text: string, options: boolean): ConfigEntry {
  const trimmed = text.trim()
  if (trimmed === 'true' || trimmed === 'false') return { ...base, kind: 'bool', value: trimmed === 'true', style: {} }
  const num = trimmed === text && trimmed ? parseNumber(trimmed) : null
  if (num) return { ...base, kind: 'number', value: num.value, integer: !num.float, style: { float: num.float } }
  // options.txt: Listen als JSON, z. B. resourcePacks:["vanilla","file/x.zip"].
  if (options && trimmed.startsWith('[') && trimmed.endsWith(']')) {
    try {
      const list = JSON.parse(trimmed) as unknown
      if (Array.isArray(list) && list.every((i) => typeof i === 'string')) {
        return { ...base, kind: 'list', value: list, itemType: 'string', style: { list: 'json' } }
      }
    } catch {
      // Kein JSON – bleibt Text.
    }
  }
  return { ...base, kind: 'string', value: text, style: {} }
}

export function parseProperties(text: string, dialect: 'properties' | 'options'): ConfigDoc {
  const lines = new LineIndex(text)
  const root = newGroup([], '', 1)
  const entries: ConfigEntry[] = []
  const seen = new Map<string, number>()
  let pending: string[] = []
  let pos = text.charCodeAt(0) === 0xfeff ? 1 : 0
  while (pos < text.length) {
    let nl = text.indexOf('\n', pos)
    if (nl < 0) nl = text.length
    const lineEnd = text[nl - 1] === '\r' ? nl - 1 : nl
    const line = text.slice(pos, lineEnd)
    const lineStart = pos
    pos = nl + 1
    const trimmed = line.trim()
    if (!trimmed) {
      pending = []
      continue
    }
    if (trimmed.startsWith('#') || (dialect === 'properties' && trimmed.startsWith('!'))) {
      pending.push(trimmed.slice(1).replace(/^ /, ''))
      continue
    }
    const indent = line.length - line.trimStart().length
    let key: string
    let valueStart: number
    if (dialect === 'options') {
      const sep = line.search(/[:=]/)
      if (sep < 0) throw new ConfigParseError('expectedSeparator', lines.lineOf(lineStart))
      key = line.slice(indent, sep).trim()
      valueStart = lineStart + sep + 1
    } else {
      // Schlüssel endet am ersten nicht maskierten =, : oder Leerraum.
      let i = indent
      while (i < line.length && !/[=:\s]/.test(line[i]!)) i += line[i] === '\\' ? 2 : 1
      key = unescape(line.slice(indent, Math.min(i, line.length)))
      while (i < line.length && /[ \t\f]/.test(line[i]!)) i++
      if (line[i] === '=' || line[i] === ':') i++
      while (i < line.length && /[ \t\f]/.test(line[i]!)) i++
      valueStart = lineStart + Math.min(i, line.length)
    }
    if (!key) throw new ConfigParseError('expectedKey', lines.lineOf(lineStart))

    // Fortsetzungszeilen (ungerade Zahl an \ am Ende) gehören zum Wert.
    let valueEnd = lineEnd
    let multiline = false
    if (dialect === 'properties') {
      let current = text.slice(lineStart, lineEnd)
      while (/(^|[^\\])(\\\\)*\\$/.test(current) && pos <= text.length) {
        multiline = true
        let next = text.indexOf('\n', pos)
        if (next < 0) next = text.length
        const end = text[next - 1] === '\r' ? next - 1 : next
        current = text.slice(pos, end)
        valueEnd = end
        pos = next + 1
        if (next >= text.length) break
      }
    }
    const rawValue = text.slice(valueStart, valueEnd)
    const count = seen.get(key) ?? 0
    seen.set(key, count + 1)
    const base = {
      id: entryId(count ? [key, count] : [key]),
      key,
      groups: [],
      span: { start: valueStart, end: valueEnd },
      line: lines.lineOf(lineStart),
    }
    let entry: ConfigEntry
    if (dialect === 'options') entry = typed(base, rawValue, true)
    else {
      const value = multiline ? unescape(rawValue.replace(/\\\r?\n[ \t\f]*/g, '')) : unescape(rawValue)
      entry = typed(base, value, false)
      // Zahl/Bool nur, wenn der Rohtext genau so aussah (keine Escapes).
      if (entry.kind !== 'string' && rawValue !== value) entry = { ...base, kind: 'string', value, style: {} }
    }
    applyHints(entry, commentHints(pending))
    pending = []
    entries.push(entry)
    root.children.push({ type: 'entry', entry })
  }
  return { format: dialect, root, entries, eol: detectEol(text) }
}
