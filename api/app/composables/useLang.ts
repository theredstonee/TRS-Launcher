import { messages, type Lang, type Messages } from '~/utils/messages'

export const LANGS: { code: Lang, name: string, flag: string }[] = [
  { code: 'en', name: 'English', flag: '/flags/gb.svg' },
  { code: 'de', name: 'Deutsch', flag: '/flags/de.svg' },
  { code: 'es', name: 'Español', flag: '/flags/es.svg' },
]

function isLang(v: unknown): v is Lang {
  return v === 'en' || v === 'de' || v === 'es'
}

/** Sprache aus der Adresse (?lang=), dem Cookie oder dem Browser; Standard Englisch. */
function detect(): Lang {
  const route = useRoute()
  const fromQuery = route.query.lang
  if (isLang(fromQuery)) return fromQuery
  const cookie = useCookie<string | null>('trs_lang')
  if (isLang(cookie.value)) return cookie.value
  const header = import.meta.server ? useRequestHeaders(['accept-language'])['accept-language'] ?? '' : navigator.language
  const first = header.toLowerCase().split(/[,;]/)[0] ?? ''
  if (first.startsWith('de')) return 'de'
  if (first.startsWith('es')) return 'es'
  return 'en'
}

export function useLang() {
  const lang = useState<Lang>('lang', detect)
  const cookie = useCookie<string | null>('trs_lang', { maxAge: 60 * 60 * 24 * 365, sameSite: 'lax', path: '/' })
  const m = computed<Messages>(() => messages[lang.value])

  function setLang(next: Lang) {
    lang.value = next
    cookie.value = next
  }

  /** Einfache Platzhalter: „{version}“ → Wert. */
  function fill(text: string, params: Record<string, string | number> = {}): string {
    return text.replace(/\{(\w+)\}/g, (_, k: string) => String(params[k] ?? `{${k}}`))
  }

  /** Datum ohne Uhrzeit in der Seitensprache. */
  function date(iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(/^\d{4}-\d{2}-\d{2}$/.test(iso) ? `${iso}T12:00:00Z` : iso)
    if (Number.isNaN(d.getTime())) return ''
    return new Intl.DateTimeFormat(lang.value === 'en' ? 'en-GB' : lang.value, { dateStyle: 'long' }).format(d)
  }

  return { lang, m, setLang, fill, date }
}
