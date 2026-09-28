import { blogTexts, type BlogTexts } from '~/utils/blog-i18n'

/** Texte der News-Beiträge (öffentlich + Editor im Team-Bereich), EN/DE/ES. */
export function useBlogText() {
  const { lang, fill, date } = useLang()
  const b = computed<BlogTexts>(() => blogTexts[lang.value])
  return { b, lang, fill, date }
}
