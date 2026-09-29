import { contentRoutes } from './build/content-routes.mjs'
import { iconsToBundle } from './build/icons'

// TRS Launcher – Dokumentation (Docus-Layer, Nuxt 4). Wird statisch erzeugt (`npm run generate`) und von der
// Website-App (api/) unter https://trs-launcher.theredstonee.de/docs ausgeliefert – es gibt keinen eigenen Server.
// Aufbau, Build und Sicherheit: README.md; Regeln für Inhalte: CONTENT.md.

const SITE_URL = 'https://trs-launcher.theredstonee.de'

/** Die fünf Bereiche (Ordner `content/<lang>/<N>.<slug>/`) – Reihenfolge wie in der Navigation. */
const DOC_SECTIONS = [
  { slug: 'getting-started', title: 'Getting started' },
  { slug: 'launcher', title: 'Launcher' },
  { slug: 'client', title: 'TRS Client' },
  { slug: 'help', title: 'Help' },
  { slug: 'developers', title: 'Developers' },
] as const

export default defineNuxtConfig({
  extends: ['docus'],
  modules: ['@nuxtjs/i18n'],
  compatibilityDate: '2026-09-01',
  telemetry: false,
  devtools: { enabled: false },

  app: {
    // Alles liegt unter /docs/ – Links, Assets, Payloads und die Such-Datenbank.
    baseURL: '/docs/',
    head: {
      meta: [{ name: 'theme-color', content: '#111116' }],
    },
  },

  // Pixel-Schrift für Überschriften, lokal gebündelt (keine Google Fonts). Farben und Stil: app/app.css.
  css: ['@fontsource-variable/pixelify-sans'],

  // Standard dunkel wie die Website.
  colorMode: {
    preference: 'dark',
    fallback: 'dark',
  },

  // Site-Adresse ohne Pfad (nuxt-site-config verlangt das); den Basis-Pfad /docs ergänzt app/plugins/seo-base.ts in
  // canonical, hreflang, og:url und JSON-LD.
  site: {
    url: SITE_URL,
    name: 'TRS Launcher Docs',
  },

  i18n: {
    defaultLocale: 'en',
    baseUrl: SITE_URL,
    locales: [
      { code: 'en', name: 'English', language: 'en-US' },
      { code: 'de', name: 'Deutsch', language: 'de-DE' },
      { code: 'es', name: 'Español', language: 'es-ES' },
    ],
    // Docus erzwingt ohnehin „prefix“: /docs/en/…, /docs/de/…, /docs/es/…
    strategy: 'prefix',
    // Die Sprachwahl für /docs macht der Website-Server (Accept-Language), nicht die Seite.
    detectBrowserLanguage: false,
  },

  docus: {
    // KI-Assistent komplett aus (kein Chat, keine Server-Route).
    assistant: { enabled: false },
  },

  // MCP-Server aus – statisch gibt es ohnehin keinen Server.
  mcp: { enabled: false },

  // Nuxt Studio / Vorschau-Modus bleibt aus (kein `content.preview`).

  ui: {
    // Keine Web-Fonts von fremden Hosts (@nuxt/fonts). Schriften kommen lokal (@fontsource, app/app.css).
    fonts: false,
  },

  // Icons nur aus dem Bundle – kein Abruf von api.iconify.design (CSP!). Die Liste sammelt build/icons.ts
  // aus Inhalten, App-Dateien, Docus und Nuxt UI.
  icon: {
    provider: 'none',
    fallbackToApi: false,
    clientBundle: {
      icons: iconsToBundle(),
      scan: true,
      includeCustomCollections: true,
      sizeLimitKb: 1024,
    },
    serverBundle: { collections: ['lucide', 'simple-icons', 'vscode-icons'] },
  },

  // Bilder unverändert ausliefern (kein IPX, kein Vorrendern von Varianten) – fehlende Bilder brechen den Build nicht.
  image: { provider: 'none' },

  // Vorschaubilder (Open Graph) werden beim Generieren als PNG gerendert (nuxt-og-image, zeroRuntime).
  ogImage: {
    zeroRuntime: true,
  },

  llms: {
    domain: `${SITE_URL}/docs`,
    title: 'TRS Launcher Documentation',
    description:
      'Documentation for TRS Launcher (the redstone Minecraft launcher by TheRedstonee) and the TRS Client mod: installation, launcher features, client modules, help and developer notes.',
    full: {
      title: 'TRS Launcher Documentation – full text',
      description: 'All English documentation pages of TRS Launcher and TRS Client in one Markdown text.',
    },
    // Nur Englisch (Anforderung): je Bereich ein Abschnitt aus der Sammlung docs_en. Die Startseite (nur Karten)
    // bleibt draußen.
    sections: [
      ...DOC_SECTIONS.map((s) => ({
        title: s.title,
        contentCollection: 'docs_en',
        contentFilters: [
          { field: 'extension', operator: '=' as const, value: 'md' },
          { field: 'path', operator: 'LIKE' as const, value: `/en/${s.slug}%` },
        ],
      })),
    ],
    notes: [
      'Website: https://trs-launcher.theredstonee.de/ (overview of the website: https://trs-launcher.theredstonee.de/llms.txt)',
      'The documentation also exists in German (/docs/de/) and Spanish (/docs/es/).',
      'TRS Launcher is not an official Minecraft product and is not approved by or associated with Mojang or Microsoft.',
    ],
  },

  robots: {
    // robots.txt der Website gilt (api/); hier keine eigene Datei, Seiten bleiben indexierbar.
    robotsTxt: false,
  },

  nitro: {
    prerender: {
      crawlLinks: true,
      failOnError: false,
      // …/install/index.html statt …/install.html: der Static-Handler der Website (Nitro) sucht `<pfad>/index.html`.
      autoSubfolderIndex: true,
      // Jede Seite aus content/ vorrendern – auch ohne Link dorthin (Suche, Sitemap).
      routes: contentRoutes().map((r) => r.path),
    },
  },

  experimental: {
    // Payload je Seite als eigene JSON-Datei statt Inline-Skript (weniger Inline-Code, gleiche CSP-Hashes je Seite).
    payloadExtraction: true,
  },

  typescript: {
    strict: true,
  },
})
