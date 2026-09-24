/** Sicherheits-Header für jede Antwort (JSON, PNG, Fehler). HTML-Seiten setzen eine eigene CSP. */
export const SECURITY_HEADERS: Record<string, string> = {
  'Strict-Transport-Security': 'max-age=63072000; includeSubDomains; preload',
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
  'Cross-Origin-Opener-Policy': 'same-origin',
  'Cross-Origin-Resource-Policy': 'same-origin',
  'Cache-Control': 'no-store',
}

/** API-Pfade (JSON, strenge Header); alles andere sind Seiten und Dateien der Website. */
export function isApiPath(path: string | undefined): boolean {
  const p = (path ?? '').split('?')[0]!
  return p === '/v1' || p.startsWith('/v1/')
}

export const CORS_ALLOWED_METHODS = 'GET, POST, PUT, PATCH, DELETE'
export const CORS_ALLOWED_HEADERS = 'Authorization, Content-Type'
export const CORS_EXPOSED_HEADERS = 'Retry-After, ETag, X-Request-Id'
