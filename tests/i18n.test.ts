import fs from 'node:fs'
import path from 'node:path'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { createI18n } from 'vue-i18n'
import {
  completionOf,
  countMessages,
  detectSystemLocale,
  i18n,
  languageList,
  pluralForms,
  setLocale,
  supportedLocales,
  t,
  type Locale,
} from '../app/utils/i18n'
import { formatBytes as formatBytesRaw, formatCount, formatMemory, formatPlayTime, formatRelative, stageLabel } from '../app/utils/format'

const root = path.resolve(__dirname, '..')
const localeDir = path.join(root, 'app', 'locales')

type Tree = { [key: string]: string | Tree }

function load(locale: string): Tree {
  return JSON.parse(fs.readFileSync(path.join(localeDir, `${locale}.json`), 'utf8'))
}

function flatten(tree: Tree, prefix = '', out = new Map<string, string>()): Map<string, string> {
  for (const [k, v] of Object.entries(tree)) {
    const p = prefix ? `${prefix}.${k}` : k
    if (typeof v === 'string') out.set(p, v)
    else flatten(v, p, out)
  }
  return out
}

/** `{name}`-Platzhalter, ohne Literale wie `{'@'}`. */
function placeholders(message: string): string[] {
  return [...message.matchAll(/\{\s*([A-Za-z_][\w]*)\s*\}/g)].map((m) => m[1]!).sort()
}

/** Pluralformen: an `|` trennen, außer in `{'|'}`. */
function forms(message: string): string[] {
  return message.replace(/\{'\|'\}/g, '\u0000').split('|')
}

const en = flatten(load('en'))
const locales = Object.fromEntries(supportedLocales.map((l) => [l, flatten(load(l))])) as Record<Locale, Map<string, string>>

function walk(dir: string, exts: string[], out: string[] = []): string[] {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'target' || entry.name.startsWith('.')) continue
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) walk(full, exts, out)
    else if (exts.some((e) => entry.name.endsWith(e))) out.push(full)
  }
  return out
}

describe('Sprachdateien', () => {
  it('gibt es für jede unterstützte Sprache, mit Flagge', () => {
    const files = fs.readdirSync(localeDir).filter((f) => f.endsWith('.json')).map((f) => f.replace(/\.json$/, ''))
    expect(files.sort()).toEqual([...supportedLocales].sort())
    for (const l of languageList) expect(fs.existsSync(path.join(root, 'public', 'flags', `${l.flag}.svg`)), l.flag).toBe(true)
    expect(languageList.map((l) => l.code)).toEqual([...supportedLocales])
  })

  it('haben keine Schlüssel, die es im Englischen nicht (mehr) gibt', () => {
    for (const locale of supportedLocales) {
      const extra = [...locales[locale].keys()].filter((k) => !en.has(k))
      expect(extra, `${locale}: veraltete Schlüssel`).toEqual([])
    }
  })

  it('Deutsch und Spanisch sind vollständig, Beta-Sprachen melden ihren Stand', () => {
    const report: string[] = []
    for (const locale of supportedLocales) {
      const missing = [...en.keys()].filter((k) => !locales[locale].get(k)?.trim())
      const percent = Math.floor(((en.size - missing.length) / en.size) * 100)
      report.push(`${locale}: ${percent} % (${missing.length} fehlen${missing.length ? `, z. B. ${missing.slice(0, 5).join(', ')}` : ''})`)
      const beta = languageList.find((l) => l.code === locale)!.beta
      if (!beta) expect(missing, `${locale}: fehlende Schlüssel`).toEqual([])
      expect(completionOf(load(locale))).toBe(percent)
    }
    console.info(`Übersetzungsstand:\n  ${report.join('\n  ')}`)
  })

  it('verwenden dieselben Platzhalter wie Englisch', () => {
    const problems: string[] = []
    for (const locale of supportedLocales) {
      for (const [key, message] of locales[locale]) {
        const reference = en.get(key)
        if (reference === undefined) continue
        const expected = [...new Set(forms(reference).flatMap(placeholders))].sort()
        const actual = [...new Set(forms(message).flatMap(placeholders))].sort()
        // Pluralformen dürfen {n} weglassen („ein Mod“), aber keine fremden Platzhalter erfinden.
        const unknown = actual.filter((p) => !expected.includes(p) && p !== 'n' && p !== 'count')
        const lost = expected.filter((p) => !actual.includes(p) && p !== 'n' && p !== 'count')
        if (unknown.length || lost.length) problems.push(`${locale} ${key}: erwartet {${expected.join(', ')}} – hat {${actual.join(', ')}}`)
      }
    }
    expect(problems).toEqual([])
  })

  it('haben die passende Zahl an Pluralformen', () => {
    const problems: string[] = []
    const plural = [...en].filter(([, m]) => forms(m).length > 1).map(([k]) => k)
    for (const key of plural) {
      if (forms(en.get(key)!).length !== 2) problems.push(`en ${key}: Englisch braucht genau 2 Formen`)
      for (const locale of supportedLocales) {
        const message = locales[locale].get(key)
        if (message === undefined) continue
        const count = forms(message).length
        if (count !== pluralForms[locale].length) problems.push(`${locale} ${key}: ${count} statt ${pluralForms[locale].length} Formen`)
      }
    }
    expect(problems).toEqual([])
  })

  it('lassen sich alle übersetzen (Syntax von vue-i18n)', () => {
    const messages = Object.fromEntries(supportedLocales.map((l) => [l, load(l)]))
    const check = createI18n({ legacy: false, locale: 'en', messages, missingWarn: false, fallbackWarn: false })
    const translate = check.global.t as unknown as (key: string, named: Record<string, unknown>, plural?: number) => string
    const problems: string[] = []
    for (const locale of supportedLocales) {
      check.global.locale.value = locale
      for (const [key, message] of locales[locale]) {
        const named = Object.fromEntries(placeholders(message).map((p) => [p, 'X']))
        try {
          const out = forms(message).length > 1 ? translate(key, named, 2) : translate(key, named)
          if (typeof out !== 'string' || (message.trim() && !out.trim())) problems.push(`${locale} ${key}: leer`)
        } catch (e) {
          problems.push(`${locale} ${key}: ${(e as Error).message.split('\n')[0]}`)
        }
      }
    }
    expect(problems).toEqual([])
  })
})

describe('Schlüssel im Code', () => {
  const appFiles = walk(path.join(root, 'app'), ['.vue', '.ts'])
  const rustFiles = walk(path.join(root, 'src-tauri'), ['.rs'])
  const appSource = appFiles.map((f) => fs.readFileSync(f, 'utf8')).join('\n')
  const rustSource = rustFiles.map((f) => fs.readFileSync(f, 'utf8')).join('\n')

  it('jeder Fehlercode aus dem Kern ist übersetzt – mit denselben Parametern', () => {
    const problems: string[] = []
    const re = /msg!\(\s*"([A-Za-z0-9_.]+)"\s*,\s*"((?:[^"\\]|\\.)*)"/g
    let codes = 0
    for (const m of rustSource.matchAll(re)) {
      codes++
      const [, code, text] = m
      const message = en.get(`errors.${code}`)
      if (message === undefined) {
        // Test-Codes aus den Rust-Tests (z. B. `test.range`) brauchen keine Übersetzung.
        if (!code!.startsWith('test.')) problems.push(`errors.${code} fehlt in en.json`)
        continue
      }
      const rustParams = [...new Set([...text!.matchAll(/\{([A-Za-z_]\w*)\}/g)].map((p) => p[1]!))].sort()
      const enParams = [...new Set(forms(message).flatMap(placeholders))].sort()
      if (rustParams.join() !== enParams.join()) problems.push(`errors.${code}: Rust {${rustParams}} ≠ en {${enParams}}`)
    }
    expect(codes).toBeGreaterThan(20)
    expect(problems).toEqual([])
  })

  it('es gibt keine ungenutzten Schlüssel', () => {
    const literals = new Set([...appSource.matchAll(/['"`]([A-Za-z][\w-]*(?:\.[\w-]+)+)['"`]/g)].map((m) => m[1]!))
    // Dynamische Schlüssel: t(`launchStage.${stage}`) → alles unter „launchStage.“ gilt als benutzt.
    const prefixes = [...appSource.matchAll(/`([A-Za-z][\w-]*(?:\.[\w-]+)*\.)\$\{/g)]
      .map((m) => m[1]!)
      .filter((p) => p !== 'errors.')
    const rustLiterals = new Set([...rustSource.matchAll(/"([A-Za-z][\w.]*)"/g)].map((m) => m[1]!))
    // Feste Fehlercodes im Frontend, z. B. new BackendError('no_backend', …, 'noBackend').
    for (const m of appSource.matchAll(/new BackendError\([^)]*?,\s*'([A-Za-z]\w*)'\s*[,)]/g)) literals.add(m[1]!)
    const unused = [...en.keys()].filter((key) => {
      if (literals.has(key)) return false
      if (prefixes.some((p) => key.startsWith(p))) return false
      if (key.startsWith('errors.')) {
        const code = key.slice('errors.'.length)
        // Feste Codes aus dem Frontend (unexpected, noBackend) und aus dem Kern.
        if (rustLiterals.has(code) || literals.has(code)) return false
        // TRS-API-Codes kommen als `trsApi.<code>` vom Server-Code.
        if (code.startsWith('trsApi.') && rustLiterals.has(code)) return false
      }
      return true
    })
    expect(unused).toEqual([])
  })
})

describe('Formatierung je Sprache', () => {
  let before: string
  beforeAll(() => {
    before = i18n.global.locale.value
  })
  afterAll(async () => {
    await setLocale(before)
  })

  it('Spielzeit, Größen und Zahlen', async () => {
    const formatBytes = (b: number) => formatBytesRaw(b).replace(/ /g, ' ')
    await setLocale('en')
    expect(formatPlayTime(30)).toBe('under 1 min')
    expect(formatPlayTime(3 * 3600 + 5 * 60)).toBe('3 h 5 min')
    expect(formatPlayTime(0)).toBe('–')
    expect(formatBytes(1_610_612_736)).toBe('1.5 GB')
    expect(formatMemory(4096)).toBe('4 GB')
    expect(formatCount(1_234_567)).toBe('1.2M')
    expect(formatRelative(null)).toBe('Never played')
    expect(stageLabel('libraries')).toBe('Libraries')

    await setLocale('de')
    expect(formatPlayTime(3 * 3600 + 5 * 60)).toBe('3 Std. 5 Min.')
    expect(formatBytes(1_610_612_736)).toBe('1,5 GB')
    expect(formatRelative(null)).toBe('Noch nie gespielt')
    expect(formatRelative(new Date(Date.now() - 3 * 86_400_000).toISOString())).toBe('vor 3 Tagen')
    expect(stageLabel('libraries')).toBe('Bibliotheken')

    await setLocale('es')
    expect(formatPlayTime(30)).toBe('menos de 1 min')
    expect(formatBytes(1_610_612_736)).toBe('1,5 GB')
    expect(formatRelative(new Date(Date.now() - 3 * 86_400_000).toISOString())).toBe('hace 3 días')
    expect(t('format.neverPlayed')).toBe('Nunca jugado')
  })

  it('Pluralregeln (auch Polnisch mit drei Formen)', async () => {
    const plural = createI18n({
      legacy: false,
      locale: 'pl',
      messages: { pl: { files: '{n} plik | {n} pliki | {n} plików' }, en: { files: '{n} file | {n} files' } },
      pluralRules: i18n.global.pluralRules,
    })
    const tp = plural.global.t as unknown as (key: string, n: number) => string
    expect([1, 2, 5, 22, 25].map((n) => tp('files', n))).toEqual(['1 plik', '2 pliki', '5 plików', '22 pliki', '25 plików'])
    plural.global.locale.value = 'en'
    expect([1, 2].map((n) => tp('files', n))).toEqual(['1 file', '2 files'])
  })

  it('erkennt die Windows-Sprache', () => {
    expect(detectSystemLocale(['de-AT', 'en-US'])).toBe('de')
    expect(detectSystemLocale(['pt-PT'])).toBe('pt-BR')
    expect(detectSystemLocale(['pt-BR'])).toBe('pt-BR')
    expect(detectSystemLocale(['ja-JP', 'es-MX'])).toBe('es')
    expect(detectSystemLocale(['ja-JP'])).toBe('en')
    expect(detectSystemLocale([])).toBe('en')
  })

  it('zählt den Übersetzungsstand', () => {
    expect(countMessages({ a: 'x', b: { c: 'y', d: '' } })).toBe(2)
    expect(completionOf({ a: 'x' }, { a: 'x', b: { c: 'y' } })).toBe(50)
    expect(completionOf(load('en'))).toBe(100)
  })
})
