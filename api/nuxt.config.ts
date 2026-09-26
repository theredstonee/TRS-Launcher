import tailwindcss from '@tailwindcss/vite'

// TRS – Website (trs-launcher.theredstonee.de) und API (/v1/…) in einer Nuxt-App.
// Die Seiten werden serverseitig gerendert; die API bleibt reine Nitro-Routen unter /v1
// mit eigener JSON-Fehlerbehandlung und eigenen Sicherheits-Headern. Läuft hinter einem
// Cloudflare-Tunnel, siehe README.md.
export default defineNuxtConfig({
  compatibilityDate: '2026-09-01',
  telemetry: false,
  devtools: { enabled: false },
  modules: ['nuxt-security'],
  css: ['~/assets/css/main.css'],
  vite: {
    plugins: [tailwindcss()],
  },
  app: {
    head: {
      htmlAttrs: { lang: 'en' },
      meta: [
        { name: 'viewport', content: 'width=device-width, initial-scale=1' },
        { name: 'theme-color', content: '#111116' },
      ],
      link: [{ rel: 'icon', type: 'image/png', href: '/icon.png' }],
    },
  },
  devServer: { host: '127.0.0.1', port: 3000 },
  // Sicherheits-Header der Seiten (die API setzt ihre eigenen, strengeren in server/middleware).
  security: {
    nonce: true,
    // Nur die Header – Rate-Limits, CORS und Größenlimits macht die API selbst.
    rateLimiter: false,
    requestSizeLimiter: false,
    xssValidator: false,
    corsHandler: false,
    allowedMethodsRestricter: false,
    hidePoweredBy: true,
    headers: {
      crossOriginEmbedderPolicy: false,
      crossOriginResourcePolicy: 'same-origin',
      referrerPolicy: 'strict-origin-when-cross-origin',
      strictTransportSecurity: { maxAge: 63072000, includeSubdomains: true, preload: true },
      xFrameOptions: 'DENY',
      permissionsPolicy: {
        camera: [],
        microphone: [],
        geolocation: [],
        payment: [],
        usb: [],
      },
      contentSecurityPolicy: {
        'default-src': ["'self'"],
        'script-src': ["'self'", "'nonce-{{nonce}}'", "'strict-dynamic'"],
        'style-src': ["'self'", "'unsafe-inline'"],
        'img-src': ["'self'", 'data:', 'blob:', 'https://raw.githubusercontent.com', 'https://textures.minecraft.net'],
        'font-src': ["'self'"],
        'connect-src': ["'self'"],
        'object-src': ["'none'"],
        'base-uri': ["'none'"],
        'form-action': ["'self'"],
        'frame-ancestors': ["'none'"],
        'upgrade-insecure-requests': true,
      },
    },
  },
  routeRules: {
    // API: eigene Header aus server/middleware/00.security.ts, keine Seiten-CSP.
    '/v1/**': { security: { headers: false } },
    // Admin nur im Browser rendern (Sitzung steckt im httpOnly-Cookie, nichts vorab ausliefern).
    '/admin': { ssr: false },
  },
  nitro: {
    preset: 'node-server',
    serverAssets: [
      { baseName: 'capes', dir: '../assets/capes' },
      // templates.json + catalog.json + PNGs der mitgelieferten Kosmetik
      { baseName: 'cosmetics', dir: '../assets/cosmetics' },
      // WebP-Dekoder für Chat-Bilder (libwebp als Wasm, Apache-2.0)
      { baseName: 'codecs', dir: '../node_modules/@jsquash/webp/codec/dec', pattern: '*.wasm' },
    ],
    experimental: { asyncContext: false },
    externals: { external: ['node:sqlite'] },
  },
  hooks: {
    // API-Fehler als JSON (nur /v1 …), danach Nuxts eigener Handler für die Fehlerseite der Website.
    // Nuxt setzt seinen Handler nur, wenn keiner konfiguriert ist – daher hier als Kette.
    'nitro:config'(config) {
      const nuxtHandler = config.errorHandler
      config.errorHandler = ['~~/server/error-handler.ts', ...(nuxtHandler ? [nuxtHandler].flat() : [])]
    },
  },
  typescript: {
    strict: true,
  },
})
