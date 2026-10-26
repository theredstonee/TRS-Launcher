import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson } from '../../../../../lib/http'
import { CAMPAIGN_ID, checkRefs, hydrate, patchBody, updateCampaign } from '../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../lib/mail/http'
import { actorId } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'
import { blogPost } from '../../../../../lib/site'

/** Entwurf ändern (§40.4): Name, Look, Inhalt. `rev` muss passen (sonst 409 `stale` mit `current`). */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const id = paramWith(event, 'id', z.string().regex(CAMPAIGN_ID))
  const body = await readJson(event, patchBody, 256 * 1024)
  const ctx = useCtx()
  const content = body.content ? await hydrate(body.content, blogPost) : undefined
  if (content) checkRefs(ctx, content)
  return { campaign: updateCampaign(ctx, a, id, { ...body, content }) }
})
