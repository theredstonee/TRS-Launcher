import { computed, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { z } from 'zod'
// Relativ importiert, damit Tests die Datei ohne Nuxt laden können.
import en from '../locales/en.json'

// Übersetzungen der Oberfläche. Englisch ist Standard und Rückfall: fehlt ein
// Schlüssel in einer (Beta-)Sprache, erscheint der englische Text. Alle
// anderen Sprachen werden erst beim Umschalten nachgeladen.

export type MessageSchema = typeof en

type Leaves<T, P extends string = ''> = {
  [K in keyof T & string]: T[K] extends string ? `${P}${K}` : Leaves<T[K], `${P}${K}.`>
}[keyof T & string]

/** Jeder gültige Schlüssel aus `app/locales/en.json` – Tippfehler fallen beim Typecheck auf. */
export type MessageKey = Leaves<MessageSchema>

export type NamedParams = Record<string, string | number>

export const supportedLocales = ['en', 'de', 'es', 'fr', 'pl', 'pt-BR', 'tr', 'nl'] as const
export type Locale = (typeof supportedLocales)[number]

export interface LanguageInfo {
  code: Locale
  /** Name in der Sprache selbst. */
  native: string
  /** Englischer Name (grau daneben, auch für die Suche). */
  english: string
  /** Datei in `public/flags/` (flag-icons, MIT). */
  flag: string
  /** Maschinell übersetzt – noch nicht vollständig geprüft. */
  beta: boolean
}

export const languageList: LanguageInfo[] = [
  { code: 'en', native: 'English', english: 'English', flag: 'gb', beta: false },
  { code: 'de', native: 'Deutsch', english: 'German', flag: 'de', beta: false },
  { code: 'es', native: 'Español', english: 'Spanish', flag: 'es', beta: false },
  { code: 'fr', native: 'Français', english: 'French', flag: 'fr', beta: true },
  { code: 'pl', native: 'Polski', english: 'Polish', flag: 'pl', beta: true },
  { code: 'pt-BR', native: 'Português (Brasil)', english: 'Portuguese (Brazil)', flag: 'br', beta: true },
  { code: 'tr', native: 'Türkçe', english: 'Turkish', flag: 'tr', beta: true },
  { code: 'nl', native: 'Nederlands', english: 'Dutch', flag: 'nl', beta: true },
]

export function isLocale(value: unknown): value is Locale {
  return typeof value === 'string' && (supportedLocales as readonly string[]).includes(value)
}

/**
 * Pluralformen je Sprache in der Reihenfolge, in der sie in den JSON-Dateien
 * stehen („eins | viele“ bzw. im Polnischen „eins | wenige | viele“).
 * Kategorien, die hier fehlen (z. B. `many` im Französischen), nehmen die
 * letzte Form.
 */
export const pluralForms: Record<Locale, Intl.LDMLPluralRule[]> = {
  en: ['one', 'other'],
  de: ['one', 'other'],
  es: ['one', 'other'],
  fr: ['one', 'other'],
  pl: ['one', 'few', 'many'],
  'pt-BR': ['one', 'other'],
  tr: ['one', 'other'],
  nl: ['one', 'other'],
}

function pluralRule(locale: Locale) {
  const rules = new Intl.PluralRules(locale)
  const forms = pluralForms[locale]
  return (choice: number, choicesLength: number): number => {
    const index = forms.indexOf(rules.select(Math.abs(choice)))
    const last = choicesLength - 1
    return index < 0 ? last : Math.min(index, last)
  }
}

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: 'en',
  fallbackLocale: 'en',
  messages: { en } as Record<string, MessageSchema>,
  missingWarn: false,
  fallbackWarn: false,
  pluralRules: Object.fromEntries(supportedLocales.map((l) => [l, pluralRule(l)])),
})

type LooseT = (key: string, ...args: unknown[]) => string
const translate = i18n.global.t as unknown as LooseT
const exists = i18n.global.te as unknown as (key: string, locale?: string) => boolean

/**
 * Übersetzt einen Schlüssel. `t(key, 3)` wählt die Pluralform und setzt `{n}`;
 * `t(key, { name })` setzt benannte Werte ein; `t(key, { name }, 3)` beides.
 * Reaktiv: In Templates und `computed` wird beim Sprachwechsel neu gerendert.
 */
export function t(key: MessageKey, count: number): string
export function t(key: MessageKey, params?: NamedParams, count?: number): string
export function t(key: MessageKey, params?: NamedParams | number, count?: number): string {
  return tKey(key, params as NamedParams, count)
}

/** Wie `t`, aber für Schlüssel, die erst zur Laufzeit feststehen (z. B. Fehlercodes vom Kern). */
export function tKey(key: string, params?: NamedParams | number, count?: number): string {
  if (typeof params === 'number') return translate(key, params, { named: { n: params, count: params } })
  if (count !== undefined) return translate(key, { n: count, count, ...params }, count)
  return params ? translate(key, params) : translate(key)
}

/** Gibt es den Schlüssel (in der aktuellen Sprache oder im englischen Rückfall)? */
export function hasKey(key: string): boolean {
  return exists(key) || exists(key, 'en')
}

const localeState = ref<Locale>('en')

/** Aktuelle Sprache (reaktiv). */
export const currentLocale = computed(() => localeState.value)

/** Sprache für `Intl.*` – Englisch mit britischem Datumsformat wäre überraschend, also „en“. */
export function intlLocale(): string {
  return localeState.value
}

type LocaleModule = { default: MessageSchema }
const loaders = import.meta.glob<LocaleModule>(['../locales/*.json', '!../locales/en.json'])

function loaderFor(locale: Locale): (() => Promise<LocaleModule>) | undefined {
  return loaders[`../locales/${locale}.json`]
}

const loaded = new Set<Locale>(['en'])

async function ensureMessages(locale: Locale): Promise<void> {
  if (loaded.has(locale)) return
  const load = loaderFor(locale)
  if (!load) throw new Error(`Keine Übersetzung für ${locale}`)
  const messages = (await load()).default
  i18n.global.setLocaleMessage(locale, messages)
  loaded.add(locale)
}

/** Eingebaute zod-Meldungen (Prüfungen ohne eigenen Text) in derselben Sprache. */
const zodLocales: Record<Locale, () => Parameters<typeof z.config>[0]> = {
  en: z.locales.en,
  de: z.locales.de,
  es: z.locales.es,
  fr: z.locales.fr,
  pl: z.locales.pl,
  'pt-BR': z.locales.pt,
  tr: z.locales.tr,
  nl: z.locales.nl,
}

const HINT_KEY = 'trs.locale'

/** Merkt die Sprache für den nächsten Start (vor dem Laden der Einstellungen). */
function writeHint(locale: Locale) {
  try {
    localStorage.setItem(HINT_KEY, locale)
  } catch {
    // Nicht speicherbar – beim nächsten Start kurz Englisch, bis die Einstellungen da sind.
  }
}

export function readLocaleHint(): Locale | null {
  try {
    const value = localStorage.getItem(HINT_KEY)
    return isLocale(value) ? value : null
  } catch {
    return null
  }
}

let switching: Promise<void> = Promise.resolve()

/** Wechselt die Sprache sofort (lädt sie bei Bedarf nach). Unbekanntes → Englisch. */
export function setLocale(locale: string): Promise<void> {
  const target: Locale = isLocale(locale) ? locale : 'en'
  switching = switching
    .catch(() => {})
    .then(async () => {
      try {
        await ensureMessages(target)
      } catch (e) {
        console.error('Übersetzung konnte nicht geladen werden', e)
        return
      }
      i18n.global.locale.value = target
      localeState.value = target
      z.config(zodLocales[target]())
      if (typeof document !== 'undefined') document.documentElement.lang = target
      writeHint(target)
    })
  return switching
}

/**
 * Welche unterstützte Sprache zur Windows-/Browser-Sprache passt
 * (`de-AT` → `de`, `pt-PT` → `pt-BR`); sonst Englisch.
 */
export function detectSystemLocale(preferred: readonly string[] = typeof navigator === 'undefined' ? [] : navigator.languages ?? [navigator.language]): Locale {
  for (const raw of preferred) {
    if (!raw) continue
    const tag = raw.toLowerCase()
    const exact = supportedLocales.find((l) => l.toLowerCase() === tag)
    if (exact) return exact
    const base = tag.split('-')[0]
    const match = supportedLocales.find((l) => l.toLowerCase().split('-')[0] === base)
    if (match) return match
  }
  return 'en'
}

/** Anzahl der übersetzten Texte (Blätter) in einem Nachrichtenbaum. */
export function countMessages(tree: unknown): number {
  if (typeof tree === 'string') return tree.trim() ? 1 : 0
  if (!tree || typeof tree !== 'object') return 0
  return Object.values(tree).reduce<number>((sum, v) => sum + countMessages(v), 0)
}

/** Anteil der englischen Schlüssel, die `tree` übersetzt (nur gleiche Pfade zählen). */
export function completionOf(tree: unknown, reference: unknown = en): number {
  const total = countMessages(reference)
  if (!total) return 100
  const shared = (ref: unknown, other: unknown): number => {
    if (typeof ref === 'string') return typeof other === 'string' && other.trim() ? 1 : 0
    if (!ref || typeof ref !== 'object' || !other || typeof other !== 'object') return 0
    return Object.entries(ref).reduce<number>((sum, [k, v]) => sum + shared(v, (other as Record<string, unknown>)[k]), 0)
  }
  return Math.floor((shared(reference, tree) / total) * 100)
}

/** Fortschritt je Sprache in Prozent – lädt dazu alle Sprachdateien (klein, lokal). */
export async function loadCompletion(): Promise<Record<Locale, number>> {
  const entries = await Promise.all(
    supportedLocales.map(async (l) => {
      if (l === 'en') return [l, 100] as const
      const load = loaderFor(l)
      if (!load) return [l, 0] as const
      try {
        return [l, completionOf((await load()).default)] as const
      } catch {
        return [l, 0] as const
      }
    }),
  )
  return Object.fromEntries(entries) as Record<Locale, number>
}
