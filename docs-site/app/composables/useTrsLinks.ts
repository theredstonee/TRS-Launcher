import { joinURL } from 'ufo'

// Links zur Website (eigene Domain, die Doku liegt auf trs-launcher.wiki) und nach außen – für Kopf- und Fußzeile.

export const WEBSITE_URL = 'https://trs-launcher.theredstonee.de'
export const REPO_URL = 'https://github.com/theredstonee/TRS-Launcher'
export const DISCORD_URL = 'https://dc.theredstonee.de'
export const IMPRINT_URL = 'https://theredstonee.de/imprint/'

type Lang = 'en' | 'de' | 'es'

const TEXT: Record<Lang, { website: string, download: string, privacy: string, imprint: string, tagline: string, notAffiliated: string, backToWebsite: string, authorNoticeClient: string, authorNoticeLauncher: string }> = {
  en: {
    website: 'Website',
    download: 'Download',
    privacy: 'Privacy',
    imprint: 'Imprint',
    tagline: 'TRS Launcher – The Redstone Launcher by TheRedstonee',
    notAffiliated: 'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.',
    backToWebsite: 'Back to the website',
    authorNoticeClient: 'Original TRS Client by Ohev Tamerin (theredstonee), TRS Launcher. This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
    authorNoticeLauncher: 'Original TRS Launcher by Ohev Tamerin (theredstonee). This notice must remain visible in every copy and every modified version. It must not be removed under any circumstances, except with an additional written, signed confirmation from the author.',
  },
  de: {
    website: 'Website',
    download: 'Download',
    privacy: 'Datenschutz',
    imprint: 'Impressum',
    tagline: 'TRS Launcher – The Redstone Launcher by TheRedstonee',
    notAffiliated: 'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.',
    backToWebsite: 'Zurück zur Website',
    authorNoticeClient: 'Original TRS Client von Ohev Tamerin (theredstonee), TRS Launcher. Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
    authorNoticeLauncher: 'Original TRS Launcher von Ohev Tamerin (theredstonee). Dieser Hinweis muss in jeder Kopie und jeder veränderten Fassung sichtbar bleiben. Er darf unter keinen Umständen entfernt werden, außer mit einer zusätzlichen schriftlichen, unterschriebenen Bestätigung des Urhebers.',
  },
  es: {
    website: 'Sitio web',
    download: 'Descargar',
    privacy: 'Privacidad',
    imprint: 'Aviso legal',
    tagline: 'TRS Launcher – The Redstone Launcher by TheRedstonee',
    notAffiliated: 'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.',
    backToWebsite: 'Volver al sitio web',
    authorNoticeClient: 'TRS Client original de Ohev Tamerin (theredstonee), TRS Launcher. Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
    authorNoticeLauncher: 'TRS Launcher original de Ohev Tamerin (theredstonee). Este aviso debe permanecer visible en cada copia y en cada versión modificada. No puede eliminarse bajo ninguna circunstancia, salvo con una confirmación adicional, escrita y firmada por el autor.',
  },
}

/** Adresse einer Website-Seite in der Sprache der Doku (Englisch ohne `?lang=`, wie auf der Website). */
export function websiteUrl(path: string, lang: string): string {
  const url = joinURL(WEBSITE_URL, path)
  return lang === 'de' || lang === 'es' ? `${url}?lang=${lang}` : url
}

export function useTrsLinks() {
  const { locale } = useDocusI18n()
  const lang = computed<Lang>(() => (locale.value === 'de' || locale.value === 'es' ? locale.value : 'en'))
  const text = computed(() => TEXT[lang.value])
  return {
    text,
    website: computed(() => websiteUrl('/', lang.value)),
    download: computed(() => websiteUrl('/download', lang.value)),
    privacy: computed(() => websiteUrl('/privacy', lang.value)),
  }
}

/** Datei aus public/ mit Basis-Pfad der App (an der Wurzel der Doku-Domain). */
export function useBaseUrl(path: string): string {
  return joinURL(useRuntimeConfig().app.baseURL, path)
}
