import { buildPageHead, DEFAULT_SITE_URL, isSeoLang, softwareLd, withLang, type JsonLdNode, type PageSeo } from '#shared/seo'
import type { Lang, Messages } from '~/utils/messages'
import { postGallery, type BlogPostSummary, type LatestRelease } from './useSite'

// Suchmaschinen-Kopf je Seite (Titel, Beschreibung, kanonische Adresse, hreflang, Open Graph,
// Twitter, JSON-LD). Die Bausteine stehen in shared/seo.ts, siehe docs/seo.md.

/** Adresse der Website (SITE_URL des Servers, im Browser aus dem Zustand übernommen). */
export function useSiteUrl(): string {
  const state = useState<string>('site-url', () => {
    const fromServer = import.meta.server ? (useRequestEvent()?.context.siteUrl as string | undefined) : undefined
    return fromServer || DEFAULT_SITE_URL
  })
  return state.value
}

/** Kopf einer Seite aus einem reaktiven Getter; `siteUrl` und `lang` werden ergänzt. */
export function usePageSeo(input: () => Omit<PageSeo, 'siteUrl' | 'lang'>) {
  const siteUrl = useSiteUrl()
  const { lang } = useLang()
  const head = computed(() => buildPageHead({ ...input(), siteUrl, lang: lang.value }))
  // Die Einträge sind schlichte Objekte (shared/seo.ts, ohne unhead-Typen testbar); unheads Typen
  // unterscheiden jede rel/name-Kombination einzeln, deshalb hier einmal umgewandelt.
  useHead({
    title: () => head.value.title,
    link: () => head.value.link,
    meta: () => head.value.meta,
    script: () => head.value.script,
  } as unknown as HeadInput)
}

type HeadInput = Parameters<typeof useHead>[0]

/** Eigene Screenshots in public/shots (Ersatz, wenn der neueste Beitrag keine hat). */
const SITE_SHOTS = ['/shots/library.png', '/shots/title-screen.png', '/shots/emote-wheel.png', '/shots/redstone-overlay.png', '/shots/cape-physics.png', '/shots/zoom.png']

/** SoftwareApplication-Knoten mit Version und Screenshots des neuesten Updates. */
export function launcherLd(
  siteUrl: string,
  lang: Lang,
  m: Messages,
  release: LatestRelease | null | undefined,
  latestPost: BlogPostSummary | null | undefined,
): JsonLdNode {
  const postShots = latestPost ? postGallery(latestPost, lang).map((s) => s.src) : []
  const screenshots = [...new Set([...postShots.slice(0, 4), ...SITE_SHOTS])].slice(0, 6)
  return softwareLd({
    siteUrl,
    lang,
    description: m.seo.home.description,
    version: release?.version ?? latestPost?.version ?? null,
    releasedAt: release?.publishedAt ?? latestPost?.date ?? null,
    screenshots,
    featureList: m.seo.featureList,
    keywords: m.seo.keywords,
  })
}

/**
 * Interne Links: Wurde die Sprache über die Adresse gewählt (`?lang=de`), behalten Links sie –
 * so bleiben Crawler und geteilte Links in derselben Sprachfassung.
 */
export function useLocalePath() {
  const route = useRoute()
  return (to: string) => {
    const q = route.query.lang
    return withLang(to, isSeoLang(q) ? q : null)
  }
}
