/**
 * Fachlicher Fehler mit stabilem Code für Clients. Alles andere, was geworfen
 * wird, landet als generisches `internal_error` beim Client (Details nur im Log).
 */
export class ApiError extends Error {
  readonly isApiError = true
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly details?: Record<string, unknown>,
    readonly headers?: Record<string, string>,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export function isApiError(e: unknown): e is ApiError {
  return typeof e === 'object' && e !== null && (e as { isApiError?: unknown }).isApiError === true
}

export const badRequest = (code: string, message: string, details?: Record<string, unknown>) =>
  new ApiError(400, code, message, details)
export const unauthorized = (message = 'Authentication required') =>
  new ApiError(401, 'unauthorized', message, undefined, { 'WWW-Authenticate': 'Bearer' })
export const forbidden = (code = 'forbidden', message = 'Not allowed') => new ApiError(403, code, message)
export const notFound = (code = 'not_found', message = 'Not found') => new ApiError(404, code, message)
export const conflict = (code: string, message: string) => new ApiError(409, code, message)
export const tooLarge = (message = 'Request body too large') => new ApiError(413, 'payload_too_large', message)
export const unsupportedMedia = (message: string) => new ApiError(415, 'unsupported_media_type', message)
export const tooMany = (retryAfterSec: number) =>
  new ApiError(429, 'rate_limited', 'Too many requests', { retryAfter: retryAfterSec }, {
    'Retry-After': String(retryAfterSec),
  })
export const upstreamFailed = () =>
  new ApiError(502, 'upstream_unavailable', 'Mojang session server is not reachable, try again later')
export const unavailable = (code: string, message: string) => new ApiError(503, code, message)
