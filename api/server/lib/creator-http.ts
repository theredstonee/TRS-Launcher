import { getHeader, getQuery, type H3Event } from 'h3'
import { badRequest } from './errors'
import { normalizeUuid } from './ids'

/**
 * Für welchen Creator handelt der Aufrufer (§35.9)? `?as=<uuid>` oder Header `X-Creator-As`; ohne = für sich selbst.
 * Ob er das darf, prüft `actorFor` in creators.ts je Route und Recht.
 */
export function actingAs(event: H3Event): string | null {
  const q = getQuery<Record<string, unknown>>(event).as
  const raw = q !== undefined ? q : getHeader(event, 'x-creator-as')
  if (raw === undefined || raw === '') return null
  const uuid = typeof raw === 'string' && raw.length <= 36 ? normalizeUuid(raw) : null
  if (!uuid) throw badRequest('invalid_request', '`as` must be the UUID of the creator you help')
  return uuid
}
