import { fileURLToPath } from 'node:url'
import tailwindcss from '@tailwindcss/vite'

// Tauri hat keine Server-Runtime: SPA-Modus + `nuxt generate`.
export default defineNuxtConfig({
  compatibilityDate: '2026-09-01',
  ssr: false,
  telemetry: false,
  devtools: { enabled: false },
  modules: [
    '@pinia/nuxt',
    // Nur `nuxt dev`: Vorschau der Start-Animation unter /intro-preview (wird nicht mit ausgeliefert).
    (_options, nuxt) => {
      if (!nuxt.options.dev) return
      nuxt.hook('pages:extend', (pages) => {
        pages.push({ name: 'intro-preview', path: '/intro-preview', file: fileURLToPath(new URL('./app/dev/intro-preview.vue', import.meta.url)) })
      })
    },
  ],
  css: ['~/assets/css/main.css'],
  app: {
    pageTransition: { name: "page", mode: "out-in" },
    head: {
      title: 'TRS Launcher',
      htmlAttrs: { lang: 'en' },
      // Handy: bis unter Notch/Gestenleiste zeichnen (Ränder über env(safe-area-inset-*)),
      // die Tastatur verkleinert die Seite – das Eingabefeld im Chat bleibt sichtbar.
      viewport: 'width=device-width, initial-scale=1, viewport-fit=cover, interactive-widget=resizes-content',
    },
  },
  devServer: { port: 3000 },
  ignore: ['**/src-tauri/**'],
  // Das REC-Overlay lädt als eigenes Fenster direkt `/clip-overlay`; die Route wird sonst von
  // keiner Seite verlinkt, daher explizit mit vorbauen (eigene index.html).
  nitro: { prerender: { routes: ['/clip-overlay'] } },
  vite: {
    clearScreen: false,
    // vue-i18n: nur Composition API; Nachrichten werden ohne eval übersetzt (CSP bleibt streng).
    define: {
      __VUE_I18N_FULL_INSTALL__: true,
      __VUE_I18N_LEGACY_API__: false,
      __INTLIFY_PROD_DEVTOOLS__: false,
      __INTLIFY_DROP_MESSAGE_COMPILER__: false,
    },
    envPrefix: ['VITE_', 'TAURI_'],
    server: {
      strictPort: true,
      watch: { ignored: ['**/src-tauri/**'] },
    },
    plugins: [tailwindcss()],
  },
})
