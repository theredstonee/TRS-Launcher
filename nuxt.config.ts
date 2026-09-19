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
    head: {
      title: 'TRS Launcher',
      htmlAttrs: { lang: 'de' },
    },
  },
  devServer: { port: 3000 },
  ignore: ['**/src-tauri/**'],
  vite: {
    clearScreen: false,
    envPrefix: ['VITE_', 'TAURI_'],
    server: {
      strictPort: true,
      watch: { ignored: ['**/src-tauri/**'] },
    },
    plugins: [tailwindcss()],
  },
})
