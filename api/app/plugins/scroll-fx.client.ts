import { LANDING_IDS, landingPath } from '#shared/landing'
import type { ScrollFx } from '~/utils/scroll-fx/engine'

// Sanftes Scrollen + Scroll-Animationen nur auf den Inhaltsseiten. Admin, Docs, Issues, Chat & Co. bleiben
// unberührt; GSAP/Lenis werden erst geladen, wenn eine solche Seite offen ist (eigener Chunk).
const FX_PATHS = new Set(['/', '/features', '/download', '/servers', '/faq', '/privacy', '/blog', '/team', '/cosmetics', ...LANDING_IDS.map(landingPath)])

function wantsFx(path: string): boolean {
  const p = path.replace(/\/+$/, '') || '/'
  return FX_PATHS.has(p) || p.startsWith('/blog/')
}

export default defineNuxtPlugin((nuxtApp) => {
  const router = useRouter()
  const lang = useState('lang')
  let fx: ScrollFx | null = null
  let loading: Promise<ScrollFx> | null = null
  /** Pfad, für den die Effekte gerade aufgebaut sind (null = keine). */
  let mountedPath: string | null = null

  const load = () => (loading ??= import('~/utils/scroll-fx/engine').then((mod) => (fx = mod.createScrollFx())))

  async function mount() {
    const path = router.currentRoute.value.path
    if (!wantsFx(path) || mountedPath === path) return
    let inst: ScrollFx
    try {
      inst = await load()
    } catch (err) {
      // Chunk nicht ladbar (z. B. nach einem Deploy) – Seite funktioniert auch ohne.
      console.warn('[scroll-fx]', err)
      loading = null
      return
    }
    inst.enableSmooth()
    // Erst nach dem Scrollen des Routers (nach oben bzw. zum Anker) messen – der läuft ein Bild nach page:finish.
    await nextTick()
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)))
    const root = document.getElementById('main')
    if (!root || router.currentRoute.value.path !== path || mountedPath === path) return
    try {
      inst.mount(root)
      mountedPath = path
    } catch (err) {
      console.warn('[scroll-fx]', err)
      inst.unmount()
    }
  }

  function unmount() {
    if (mountedPath === null) return
    mountedPath = null
    fx?.unmount()
  }

  router.beforeEach((to, from) => {
    if (to.path === from.path) return
    unmount()
    if (!wantsFx(to.path)) fx?.disableSmooth()
  })
  nuxtApp.hook('app:suspense:resolve', () => void mount())
  nuxtApp.hook('page:finish', () => void mount())

  // Sprachwechsel: vor dem Neuzeichnen abbauen (zerlegte Titel zurück), danach neu aufbauen.
  watch(
    lang,
    () => {
      if (mountedPath === null) return
      unmount()
      void nextTick(() => void mount())
    },
    { flush: 'pre' },
  )

  // Sprungmarken auf derselben Seite über Lenis (mit Platz für die Kopfzeile). Tastatur (detail 0)
  // bleibt beim nativen Sprung, damit der Fokus mitwandert (z. B. „Zum Inhalt springen“).
  document.addEventListener(
    'click',
    (e) => {
      if (mountedPath === null || !fx || e.defaultPrevented || e.button !== 0 || e.detail === 0) return
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return
      const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null
      if (!a || (a.target && a.target !== '_self')) return
      const url = new URL(a.href, location.href)
      if (url.origin !== location.origin || url.pathname !== location.pathname || url.search !== location.search) return
      if (!url.hash || url.hash === '#' || !fx.scrollToHash(url.hash)) return
      e.preventDefault()
      e.stopPropagation()
      if (url.hash !== location.hash) history.replaceState(history.state, '', url.hash)
    },
    true,
  )
})
