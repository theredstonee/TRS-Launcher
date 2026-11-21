import { partnerTexts, type PartnerTexts } from '~/utils/partner-i18n'

/** Texte des Partnerprogramms (§44) in der Sprache der Website. */
export function usePartnerText() {
  const { lang } = useLang()
  const p = computed<PartnerTexts>(() => partnerTexts[lang.value])
  return { p, lang }
}
