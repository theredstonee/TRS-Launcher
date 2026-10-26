import { getHeader, type H3Event } from 'h3'
import { useCtx } from '../context'
import { limit, requireStaff } from '../http'
import { RULES } from '../ratelimit'
import { assertActorCan, authenticateKey, type KeyPermission, type NewsletterActor } from './keys'

/**
 * Zugriff auf die Newsletter-Routen (§40.4): Team (Website-Sitzung + CSRF, Bearer `trs_…`, `X-Admin-Key`) mit dem
 * Recht `need` – oder ein Newsletter-API-Schlüssel `Bearer trsk_…` mit genau diesem Recht. Nur diese Routen rufen
 * `requireNewsletter` auf; damit gilt ein Schlüssel nirgendwo sonst.
 */
export function requireNewsletter(event: H3Event, need: KeyPermission): NewsletterActor {
  const auth = getHeader(event, 'authorization')
  if (auth?.startsWith('Bearer trsk_')) {
    const actor = authenticateKey(useCtx(), auth.slice('Bearer '.length).trim())
    if (actor.kind === 'key') limit(`mailKey:${actor.id}`, RULES.newsletterKey)
    assertActorCan(actor, need)
    event.context.uuid = actor.kind === 'key' ? `key:${actor.id}` : undefined
    return actor
  }
  const staff = requireStaff(event, need)
  return { kind: 'staff', staff }
}
