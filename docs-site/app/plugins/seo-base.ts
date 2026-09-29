import { joinURL, withoutTrailingSlash } from 'ufo'

const OG_LOCALE: Record<string, string> = { en: 'en_US', de: 'de_DE', es: 'es_ES' }

// Docus baut canonical, hreflang, og:url und JSON-LD aus `site.url` + Routenpfad – ohne den Basis-Pfad der App
// (/docs/). Hier wird er in allen absoluten Adressen der eigenen Domain ergänzt, die noch nicht darunter liegen.
// Beispiel: https://trs-launcher.theredstonee.de/en/help → https://trs-launcher.theredstonee.de/docs/en/help
export default defineNuxtPlugin(() => {
  const site = withoutTrailingSlash(useSiteConfig().url || '')
  const base = withoutTrailingSlash(useRuntimeConfig().app.baseURL || '/')
  if (!site || !base || base === '/') return

  const prefix = `${site}${base}`
  const fix = (url: string): string => {
    if (!url.startsWith(site)) return url
    const rest = url.slice(site.length)
    if (rest === base || rest.startsWith(`${base}/`)) return url
    return joinURL(prefix, rest)
  }
  const escaped = site.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
  const inJson = new RegExp(`"${escaped}(/[^"]*)?"`, 'g')

  const head = injectHead()
  head.hooks.hook('tags:resolve', (ctx) => {
    for (const tag of ctx.tags) {
      const props = tag.props as Record<string, unknown>
      if (tag.tag === 'link' && (props.rel === 'canonical' || props.rel === 'alternate') && typeof props.href === 'string') {
        props.href = fix(props.href)
      }
      else if (tag.tag === 'meta' && (props.property === 'og:url') && typeof props.content === 'string') {
        props.content = fix(props.content)
      }
      else if (tag.tag === 'meta' && props.property === 'og:locale' && typeof props.content === 'string') {
        // Wie auf der Website: en_US, de_DE, es_ES statt nur des Sprachcodes.
        props.content = OG_LOCALE[props.content] ?? props.content
      }
      else if (tag.tag === 'script' && props.type === 'application/ld+json' && typeof tag.innerHTML === 'string') {
        tag.innerHTML = tag.innerHTML.replace(inJson, (_m, rest: string | undefined) => `"${fix(`${site}${rest ?? ''}`)}"`)
      }
    }
  })
})
