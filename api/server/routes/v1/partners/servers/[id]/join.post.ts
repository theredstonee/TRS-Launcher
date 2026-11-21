import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { clientIp, limit, noContent, optionalUser, paramWith } from '../../../../../lib/http'
import { recordServerJoin } from '../../../../../lib/partner-servers'
import { RULES } from '../../../../../lib/ratelimit'

/** Beitritt über die Partner-Liste (§44.3). Mit Bearer: zählt einmal je Spieler, Server und UTC-Tag. Immer 204. */
export default defineEventHandler((event) => {
  limit(`partner-count:${clientIp(event)}`, RULES.partnerCountIp)
  const id = paramWith(event, 'id', z.string().regex(/^ps_[0-9a-f]{16}$/))
  recordServerJoin(useCtx(), id, optionalUser(event)?.uuid ?? null)
  return noContent(event)
})
