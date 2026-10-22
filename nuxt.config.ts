import { createHash, randomBytes } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import fs from 'node:fs'
import tailwindcss from '@tailwindcss/vite'

// Messe-Build (TRS_MESSE=1, alternativ NUXT_PUBLIC_MESSE=1): Präsentation + Kiosk-Ebene. Normale Builds bekommen
// weder die Präsentation (.messe-pitch/) noch aktive Messe-Pfade – MESSE ist dort zur Bauzeit fest `false`.
const messe = process.env.TRS_MESSE === '1' || process.env.NUXT_PUBLIC_MESSE === '1'
const messePitchDir = fileURLToPath(new URL('./.messe-pitch', import.meta.url))
if (messe && !fs.existsSync(`${messePitchDir}/index.html`)) {
  throw new Error('Messe-Build ohne Präsentation: erst `node scripts/messe/import-pitch.mjs <Ordner>` ausführen.')
}

// Eingebaute Personal-PIN (TRS_MESSE_PIN, 4–8 Ziffern) – landet nur als gesalzener Hash im Build
// (gleiches Format wie hashPin in app/utils/messe.ts).
const messePin = messe ? (process.env.TRS_MESSE_PIN ?? '') : ''
if (messePin && !/^\d{4,8}$/.test(messePin)) throw new Error('TRS_MESSE_PIN: 4–8 Ziffern erwartet.')
const messePinSalt = messePin ? randomBytes(16).toString('hex') : ''
const messePinRecord = messePin
  ? JSON.stringify({ salt: messePinSalt, hash: createHash('sha256').update(`trs-messe:${messePinSalt}:${messePin}`).digest('hex') })
  : ''

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
  nitro: {
    prerender: { routes: ['/clip-overlay'] },
    // Nur im Messe-Build: Präsentation unter /messe-pitch/ (eingebettet per iframe).
    publicAssets: messe ? [{ dir: messePitchDir, baseURL: '/messe-pitch', maxAge: 0 }] : [],
  },
  vite: {
    clearScreen: false,
    // vue-i18n: nur Composition API; Nachrichten werden ohne eval übersetzt (CSP bleibt streng).
    define: {
      'import.meta.env.VITE_TRS_MESSE': JSON.stringify(messe ? '1' : ''),
      'import.meta.env.VITE_TRS_MESSE_PIN': JSON.stringify(messePinRecord),
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
