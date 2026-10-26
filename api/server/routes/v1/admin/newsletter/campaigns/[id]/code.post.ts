import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../../../lib/http'
import { CAMPAIGN_ID, codeBody, generateBlockCode } from '../../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../../lib/mail/http'
import { actorId } from '../../../../../../lib/mail/keys'
import { RULES } from '../../../../../../lib/ratelimit'

/** Code für eine Kosmetik-Karte erzeugen (§40.4) über das Code-System: `{ blockId, maxUses, expiresAt?, rev }`. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const body = await readJson(event, codeBody)
  return { campaign: generateBlockCode(useCtx(), a, id, body) }
})
