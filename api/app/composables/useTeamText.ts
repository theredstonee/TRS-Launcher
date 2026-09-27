import { teamTexts, type TeamTexts } from '~/utils/team-i18n'

/** Texte für Anmeldung, Team-Seite, Bewerbungen und Rollen (EN/DE/ES). */
export function useTeamText() {
  const { lang, fill, date } = useLang()
  const t = computed<TeamTexts>(() => teamTexts[lang.value])
  return { t, lang, fill, date }
}
