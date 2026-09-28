import { issueTexts, type IssueTexts } from '~/utils/issue-i18n'

/** Texte des Issue-Trackers (§28) in der Seitensprache + lesbare Fehlermeldung aus einem $fetch-Fehler. */
export function useIssueText() {
  const { lang, fill, date } = useLang()
  const it = computed<IssueTexts>(() => issueTexts[lang.value])

  function errorText(e: unknown): string {
    const c = apiCode(e)
    return it.value.errors[c] ?? fill(it.value.errors.generic!, { error: apiMessage(e) })
  }

  /** Datum mit Uhrzeit (kurz) in der Seitensprache. */
  function dateTime(iso: string | null | undefined): string {
    if (!iso) return ''
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return ''
    return new Intl.DateTimeFormat(lang.value === 'en' ? 'en-GB' : lang.value, { dateStyle: 'medium', timeStyle: 'short' }).format(d)
  }

  return { it, lang, fill, date, dateTime, errorText }
}
