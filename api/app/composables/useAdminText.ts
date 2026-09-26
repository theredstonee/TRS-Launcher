import { adminTexts, type AdminTexts } from '~/utils/admin-i18n'

/**
 * Texte + Formatierung für den Team-Bereich. Deutsch und Englisch; die übrigen Sprachen der
 * Website zeigen hier Englisch. Meldungs-Texte (Gründe, Status) kommen aus `m.admin.mod`.
 */
export function useAdminText() {
  const { lang, fill, m } = useLang()
  const a = computed<AdminTexts>(() => adminTexts[lang.value === 'de' ? 'de' : 'en'])
  const locale = computed(() => (lang.value === 'de' ? 'de' : 'en-GB'))

  /** Datum + Uhrzeit, kurz. */
  function when(iso: string | null | undefined): string {
    return dateTime(iso, lang.value === 'de' ? 'de' : 'en')
  }

  /** Nur Datum. */
  function day(iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return ''
    return new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(d)
  }

  /** „vor 5 Minuten“ / „in 3 Tagen“. */
  function rel(iso: string | null | undefined): string {
    if (!iso) return ''
    const diff = new Date(iso).getTime() - Date.now()
    if (Number.isNaN(diff)) return ''
    const f = new Intl.RelativeTimeFormat(locale.value, { numeric: 'auto' })
    const abs = Math.abs(diff)
    if (abs < 60_000) return f.format(Math.round(diff / 1000), 'second')
    if (abs < 3_600_000) return f.format(Math.round(diff / 60_000), 'minute')
    if (abs < 86_400_000) return f.format(Math.round(diff / 3_600_000), 'hour')
    if (abs < 30 * 86_400_000) return f.format(Math.round(diff / 86_400_000), 'day')
    return day(iso)
  }

  const num = (n: number) => new Intl.NumberFormat(locale.value).format(n)

  /** Akteur lesbar (Name, System, API-Schlüssel oder gekürzte UUID). */
  function actor(x: { uuid: string, name: string | null } | null | undefined): string {
    return actorLabel(x, a.value.common)
  }

  return { a, m, fill, lang, locale, when, day, rel, num, actor }
}
