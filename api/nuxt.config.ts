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
      link: [
        { rel: 'icon', type: 'image/png', href: '/icon.png' },
        { rel: 'apple-touch-icon', href: '/icon.png' },
      ],
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
  // Zeitpunkt des Builds = lastmod der Seiten in der Sitemap (server/routes/sitemap.xml.get.ts).
  runtimeConfig: {
    buildTime: new Date().toISOString(),
  },
  routeRules: {
    // API: eigene Header aus server/middleware/00.security.ts, keine Seiten-CSP.
    '/v1/**': { security: { headers: false } },
    // Admin nur im Browser rendern (Sitzung steckt im httpOnly-Cookie, nichts vorab ausliefern), nie indexieren.
    '/admin': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    '/admin/**': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    // Anmeldung und eigene Bewerbungen: persönlich, nie indexieren (Inhalt lädt erst im Browser).
    '/login': { headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    '/applications': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    // Schaltungen einreichen + eigene Einreichungen (§25): persönlich, nur im Browser, nie indexieren.
    '/circuits/submit': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    '/circuits/mine': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    // Issue anlegen + eigene/gefolgte Issues (§28): persönlich, nur im Browser, nie indexieren.
    '/issues/new': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    '/issues/mine': { ssr: false, headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    '/auth/**': { headers: { 'X-Robots-Tag': 'noindex, nofollow' } },
    // Geteilte Screenshots (§23): öffentlich per Link, aber nie indexieren; Adresse nicht weiterreichen.
    '/s/**': { headers: { 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'no-referrer' } },
    '/p/**': { headers: { 'X-Robots-Tag': 'noindex, nofollow', 'Referrer-Policy': 'no-referrer' } },
    // Bilder aus public/ haben keinen Hash im Namen – einen Tag zwischenspeichern, danach neu prüfen (ETag).
    '/shots/**': { headers: { 'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800' } },
    '/flags/**': { headers: { 'Cache-Control': 'public, max-age=604800' } },
    '/og.png': { headers: { 'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800' } },
    '/icon.png': { headers: { 'Cache-Control': 'public, max-age=86400, stale-while-revalidate=604800' } },
  },
  nitro: {
    preset: 'node-server',
    serverAssets: [
      { baseName: 'capes', dir: '../assets/capes' },
      // templates.json + catalog.json + PNGs der mitgelieferten Kosmetik
      { baseName: 'cosmetics', dir: '../assets/cosmetics' },
      // Mitgelieferte Schaltungen (index.json + <id>.json), beim Start eingespielt (§25)
      { baseName: 'circuits', dir: '../assets/circuits', pattern: '*.json' },
      // WebP-Dekoder für Chat-Bilder (libwebp als Wasm, Apache-2.0)
      { baseName: 'codecs', dir: '../node_modules/@jsquash/webp/codec/dec', pattern: '*.wasm' },
    ],
    experimental: { asyncContext: false },
    externals: { external: ['node:sqlite'] },
    hooks: {
      // Typisierte Routen ($fetch/useFetch): Nitro prüft jeden Literal-Pfad gegen ALLE Routen. Ab etwa 250 Routen bricht
      // TypeScript mit TS2589 („excessively deep“) ab. Team-Routen (/v1/admin/**) ruft die Website nur über
      // useAdmin().api mit string-Pfad auf – ohne sie in den Typen bleibt genug Luft für weitere Routen.
      'types:extend'(types) {
        const routes = (types as { routes?: Record<string, unknown> }).routes ?? {}
        for (const key of Object.keys(routes)) if (key.startsWith('/v1/admin/')) delete routes[key]
      },
    },
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
