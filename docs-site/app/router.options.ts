import type { RouterConfig } from '@nuxt/schema'

// Docus legt seine Seiten als `[[lang]]/…` an; @nuxtjs/i18n setzt die Sprache davor. Ergebnis: `/en/:lang?` für die
// Startseite und `/en/:lang?/:slug(.*)*` für Doku-Seiten. Dann passt `/en/getting-started` (Bereichs-Übersicht)
// auf die Startseite statt auf die Doku-Seite. Ohne den überflüssigen `:lang?` sind die Routen eindeutig.
export default {
  routes: (routes) =>
    routes.map((route) => {
      const match = /^lang-(index|slug)___([a-z]{2}(?:-[A-Za-z]+)?)$/.exec(String(route.name ?? ''))
      if (!match) return route
      const [, kind, code] = match
      return { ...route, path: kind === 'index' ? `/${code}` : `/${code}/:slug(.*)*` }
    }),
} satisfies RouterConfig
