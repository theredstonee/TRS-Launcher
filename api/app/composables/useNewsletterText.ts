import { newsletterTexts, type NewsletterTexts } from '~/utils/newsletter-i18n'

/** Texte des Newsletters (öffentlich + Team-Bereich), EN/DE/ES. */
export function useNewsletterText() {
  const { lang, fill, date } = useLang()
  const n = computed<NewsletterTexts>(() => newsletterTexts[lang.value])
  return { n, lang, fill, date }
}
