import { joinURL } from 'ufo'

// Links zur Website (gleiche Domain) und nach außen – für Kopf- und Fußzeile.

export const WEBSITE_URL = 'https://trs-launcher.theredstonee.de'
export const REPO_URL = 'https://github.com/theredstonee/TRS-Launcher'
export const DISCORD_URL = 'https://dc.theredstonee.de'
export const IMPRINT_URL = 'https://theredstonee.de/imprint/'

type Lang = 'en' | 'de' | 'es'

const TEXT: Record<Lang, { website: string, download: string, privacy: string, imprint: string, tagline: string, notAffiliated: string, backToWebsite: string }> = {
  en: {
    website: 'Website',
    download: 'Download',
    privacy: 'Privacy',
    imprint: 'Imprint',
    tagline: 'TRS Launcher – The Redstone Launcher by TheRedstonee',
    notAffiliated: 'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.',
    backToWebsite: 'Back to the website',
  },
  de: {
    website: 'Website',
    download: 'Download',
    privacy: 'Datenschutz',
    imprint: 'Impressum',
    tagline: 'TRS Launcher – The Redstone Launcher by TheRedstonee',
    notAffiliated: 'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.',
    backToWebsite: 'Zurück zur Website',
  },
  es: {
    website: 'Sitio web',
    download: 'Descargar',
    privacy: 'Privacidad',
    imprint: 'Aviso legal',
    tagline: 'TRS Launcher – The Redstone Launcher by TheRedstonee',
    notAffiliated: 'Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.',
    backToWebsite: 'Volver al sitio web',
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

/** Datei aus public/ mit Basis-Pfad (/docs/…). */
export function useBaseUrl(path: string): string {
  return joinURL(useRuntimeConfig().app.baseURL, path)
}
