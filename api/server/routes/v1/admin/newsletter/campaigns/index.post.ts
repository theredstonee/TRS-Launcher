import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { created, limit, readJson } from '../../../../../lib/http'
import { checkRefs, createBody, createCampaign, hydrate } from '../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../lib/mail/http'
import { actorId } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'
import { blogPost } from '../../../../../lib/site'

/** Entwurf anlegen (§40.4). Update-Bausteine nur mit `version` werden aus dem Changelog gefüllt. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const body = await readJson(event, createBody, 256 * 1024)
  const ctx = useCtx()
  const content = body.content ? await hydrate(body.content, blogPost) : undefined
  if (content) checkRefs(ctx, content)
  return created(event, { campaign: createCampaign(ctx, a, { ...body, content }) })
})
