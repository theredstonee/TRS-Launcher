import { serversTexts, type ServersTexts } from '~/utils/servers-i18n'

/** Texte der Seite „Für Server“ (/servers) in der Sprache der Website. */
export function useServersText() {
  const { lang } = useLang()
  const s = computed<ServersTexts>(() => serversTexts[lang.value])
  return { s, lang }
}
