import tailwindcss from '@tailwindcss/vite'

// Tauri hat keine Server-Runtime: SPA-Modus + `nuxt generate`.
export default defineNuxtConfig({
  compatibilityDate: '2026-09-01',
  ssr: false,
  telemetry: false,
  devtools: { enabled: false },
  modules: ['@pinia/nuxt'],
  css: ['~/assets/css/main.css'],
  app: {
    pageTransition: { name: "page", mode: "out-in" },
    head: {
      title: 'TRS Launcher',
      htmlAttrs: { lang: 'en' },
    },
  },
  devServer: { port: 3000 },
  ignore: ['**/src-tauri/**'],
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
