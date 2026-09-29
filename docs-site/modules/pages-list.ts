import { addTemplate, defineNuxtModule } from '@nuxt/kit'
import { contentRoutes } from '../build/content-routes.mjs'

// Liste aller Doku-Seiten (`/en/launcher/instances` …) als Modul `#docs-pages` – der Sprachumschalter führt damit auf die
// Startseite der anderen Sprache, wenn es die Seite dort (noch) nicht gibt. Landet im JS-Bundle, nicht als Inline-Skript.
export default defineNuxtModule({
  meta: { name: 'docs-pages-list' },
  setup(_options, nuxt) {
    const template = addTemplate({
      filename: 'docs-pages.mjs',
      write: true,
      getContents: () => `export default ${JSON.stringify(contentRoutes().map((r) => r.path))}\n`,
    })
    nuxt.options.alias['#docs-pages'] = template.dst
    addTemplate({
      filename: 'types/docs-pages.d.ts',
      getContents: () => "declare module '#docs-pages' {\n  const pages: string[]\n  export default pages\n}\n",
    })
    nuxt.hook('prepare:types', ({ references }) => {
      references.push({ path: 'types/docs-pages.d.ts' })
    })
  },
})
