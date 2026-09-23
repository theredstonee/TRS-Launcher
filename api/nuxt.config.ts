// TRS API – reiner Nitro-Server (keine Vue-Seiten). Läuft im Docker-Container
// hinter einem Cloudflare-Tunnel, siehe README.md.
export default defineNuxtConfig({
  compatibilityDate: '2026-09-01',
  ssr: false,
  pages: false,
  telemetry: false,
  devtools: { enabled: false },
  experimental: {
    // Kein Vue-Renderer im Server: jede Route ist eine Nitro-Route.
    noVueServer: true,
  },
  devServer: { host: '127.0.0.1', port: 3000 },
  nitro: {
    preset: 'node-server',
    // Eigene Fehlerbehandlung: nie Stack-Traces an Clients.
    errorHandler: '~~/server/error-handler.ts',
    serverAssets: [{ baseName: 'capes', dir: '../assets/capes' }],
    experimental: { asyncContext: false },
    externals: { external: ['node:sqlite'] },
  },
  typescript: {
    strict: true,
  },
  hooks: {
    // Kein Vue-Client: die (ungenutzten) Client-Bundles werden nicht ausgeliefert.
    'nitro:config'(config) {
      config.publicAssets = []
    },
  },
})
