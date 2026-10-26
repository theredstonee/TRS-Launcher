import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { limit, readJson } from '../../../../lib/http'
import { previewBody, previewContent } from '../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../lib/mail/http'
import { actorId } from '../../../../lib/mail/keys'
import { RULES } from '../../../../lib/ratelimit'

/** Live-Vorschau ungespeicherter Inhalte (§40.4): `{ content, look, lang }` → `{ subject, preheader, html, text, look }`. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`nlPreview:${actorId(a)}`, RULES.newsletterPreview)
  const body = await readJson(event, previewBody, 256 * 1024)
  return previewContent(useCtx(), body.content, body.look, body.lang)
})
