import { hostingTexts, type HostingTexts } from '~/utils/hosting-i18n'

/** Texte der Seite „Hosting“ (/hosting) in der Sprache der Website. */
export function useHostingText() {
  const { lang } = useLang()
  const h = computed<HostingTexts>(() => hostingTexts[lang.value])
  return { h, lang }
}
