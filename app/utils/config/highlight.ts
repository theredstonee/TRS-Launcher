// Einfache Syntaxfarben für den erweiterten Modus – zeilenweise, ohne Zustand
// über Zeilen hinweg (mehrzeilige Strings/Kommentare bleiben ungefärbt).
import type { ConfigFormat } from './types'

export type TokenClass = 'key' | 'string' | 'number' | 'bool' | 'comment' | 'section' | 'punct' | 'text'
export interface HighlightToken {
  text: string
  cls: TokenClass
}

type Rule = [RegExp, TokenClass]

const STRING_DQ = /"(?:[^"\\]|\\.)*"?/y
const STRING_SQ = /'(?:[^'\\]|\\.)*'?/y
const NUMBER = /[-+]?(?:0[xX][0-9a-fA-F_]+|(?:\d[\d_]*\.?[\d_]*|\.\d+)(?:[eE][-+]?\d+)?)(?![\w.])/y

const RULES: Partial<Record<ConfigFormat, Rule[]>> = {
  json: [
    [/\/\/.*/y, 'comment'],
    [/\/\*.*?(?:\*\/|$)/y, 'comment'],
    [/(?:"(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|[A-Za-z_$][\w$]*)(?=\s*:)/y, 'key'],
    [STRING_DQ, 'string'],
    [STRING_SQ, 'string'],
    [/(?:true|false|null|Infinity|NaN)(?![\w$])/y, 'bool'],
    [NUMBER, 'number'],
    [/[{}[\],:]/y, 'punct'],
  ],
  toml: [
    [/#.*/y, 'comment'],
    [STRING_DQ, 'string'],
    [STRING_SQ, 'string'],
    [/(?:true|false|inf|nan)(?![\w-])/y, 'bool'],
    [/\d{4}-\d{2}-\d{2}(?:[Tt ]\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?)?(?:[Zz]|[+-]\d{2}:\d{2})?/y, 'number'],
    [NUMBER, 'number'],
    [/[{}[\],=]/y, 'punct'],
  ],
  yaml: [
    [STRING_DQ, 'string'],
    [/'(?:[^']|'')*'?/y, 'string'],
    [/[&*][\w-]+/y, 'section'],
    [/(?:true|false|yes|no|on|off|True|False|Yes|No|On|Off|TRUE|FALSE|YES|NO|ON|OFF|null|Null|NULL|~)(?=\s*(?:#|$|,|\]))/y, 'bool'],
    [/[-+]?(?:\d[\d_]*\.?[\d_]*|\.\d+)(?:[eE][-+]?\d+)?(?=\s*(?:#|$|,|\]))/y, 'number'],
    [/[{}[\],]/y, 'punct'],
  ],
}

function scan(line: string, from: number, rules: Rule[], out: HighlightToken[], hashComment: 'always' | 'spaced' | 'never') {
  let plain = ''
  const flush = () => {
    if (plain) out.push({ text: plain, cls: 'text' })
    plain = ''
  }
  let pos = from
  outer: while (pos < line.length) {
    if (line[pos] === '#' && (hashComment === 'always' || (hashComment === 'spaced' && (pos === 0 || /\s/.test(line[pos - 1]!))))) {
      flush()
      out.push({ text: line.slice(pos), cls: 'comment' })
      return
    }
    // Regeln nur am Wortanfang (keine Zahl mitten in einem Bezeichner).
    const boundary = pos === 0 || !/[\w$]/.test(line[pos - 1]!)
    for (const [re, cls] of rules) {
      if (!boundary && (cls === 'number' || cls === 'bool' || cls === 'key')) continue
      re.lastIndex = pos
      const m = re.exec(line)
      if (m && m[0].length) {
        flush()
        out.push({ text: m[0], cls })
        pos += m[0].length
        continue outer
      }
    }
    plain += line[pos]
    pos++
  }
  flush()
}

/** Wert hinter einem Schlüssel einfärben (Properties/Optionen/Forge-cfg). */
function valueToken(value: string): HighlightToken {
  const t = value.trim()
  if (/^(true|false)$/i.test(t)) return { text: value, cls: 'bool' }
  if (/^[-+]?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?$/.test(t)) return { text: value, cls: 'number' }
  return { text: value, cls: 'string' }
}

export function highlightLine(format: ConfigFormat, line: string): HighlightToken[] {
  const out: HighlightToken[] = []
  const indent = /^\s*/.exec(line)![0]
  const body = line.slice(indent.length)
  if (indent) out.push({ text: indent, cls: 'text' })
  if (!body) return out

  switch (format) {
    case 'json':
    case 'json5':
      scan(body, 0, RULES.json!, out, 'never')
      return out
    case 'toml': {
      if (body.startsWith('#')) return [...out, { text: body, cls: 'comment' }]
      const header = /^\[\[?[^\]]*\]\]?/.exec(body)
      if (header) {
        out.push({ text: header[0], cls: 'section' })
        scan(body, header[0].length, RULES.toml!, out, 'always')
        return out
      }
      const key = /^(?:"(?:[^"\\]|\\.)*"|'[^']*'|[\w.\- ]+?)(?=\s*=)/.exec(body)
      if (key) out.push({ text: key[0], cls: 'key' })
      scan(body, key ? key[0].length : 0, RULES.toml!, out, 'always')
      return out
    }
    case 'yaml': {
      if (body.startsWith('#')) return [...out, { text: body, cls: 'comment' }]
      let rest = body
      const dash = /^-(?:\s+|$)/.exec(rest)
      if (dash) {
        out.push({ text: dash[0], cls: 'punct' })
        rest = rest.slice(dash[0].length)
      }
      const key = /^(?:"(?:[^"\\]|\\.)*"|'(?:[^']|'')*'|[^\s#'"[\]{},][^#]*?)(?=:(?:\s|$))/.exec(rest)
      if (key) {
        out.push({ text: key[0], cls: 'key' }, { text: ':', cls: 'punct' })
        rest = rest.slice(key[0].length + 1)
      }
      const start = out.length
      scan(rest, 0, RULES.yaml!, out, 'spaced')
      // Ungequoteter Text als String färben.
      for (let i = start; i < out.length; i++) if (out[i]!.cls === 'text' && out[i]!.text.trim() && !/^[|>]/.test(out[i]!.text.trim())) out[i] = { ...out[i]!, cls: 'string' }
      return out
    }
    case 'properties':
    case 'options': {
      if (body.startsWith('#') || (format === 'properties' && body.startsWith('!'))) return [...out, { text: body, cls: 'comment' }]
      const sep = format === 'options' ? body.search(/[:=]/) : body.search(/(?<!\\)[=:\s]/)
      if (sep < 0) return [...out, { text: body, cls: 'key' }]
      out.push({ text: body.slice(0, sep), cls: 'key' }, { text: body[sep]!, cls: 'punct' })
      if (sep + 1 < body.length) out.push(valueToken(body.slice(sep + 1)))
      return out
    }
    case 'forgecfg': {
      if (body.startsWith('#')) return [...out, { text: body, cls: 'comment' }]
      if (/\{\s*$/.test(body) || body.trim() === '}' || body.trim() === '>') return [...out, { text: body, cls: body.trim().length === 1 ? 'punct' : 'section' }]
      const prop = /^([A-Za-z]:)("[^"]*"|[^=<]+?)(\s*)([=<])/.exec(body)
      if (!prop) return [...out, { text: body, cls: 'string' }]
      out.push({ text: prop[1]!, cls: 'punct' }, { text: prop[2]!, cls: 'key' })
      if (prop[3]) out.push({ text: prop[3], cls: 'text' })
      out.push({ text: prop[4]!, cls: 'punct' })
      const value = body.slice(prop[0].length)
      if (value) out.push(valueToken(value))
      return out
    }
  }
}
