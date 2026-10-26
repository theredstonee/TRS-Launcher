import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { notFound } from '../../../../../lib/errors'
import { limit, paramWith } from '../../../../../lib/http'
import { updateBlockFromPost } from '../../../../../lib/mail/campaigns'
import { requireNewsletter } from '../../../../../lib/mail/http'
import { actorId } from '../../../../../lib/mail/keys'
import { RULES } from '../../../../../lib/ratelimit'
import { blogPost } from '../../../../../lib/site'

/** Update-Baustein aus dem Changelog (§40.4): Banner + Punkte je Sprache für `version`. */
export default defineEventHandler(async (event) => {
  const a = requireNewsletter(event, 'newsletter.manage')
  limit(`adminNl:${actorId(a)}`, RULES.adminNewsletter)
  const version = paramWith(event, 'version', z.string().regex(/^\d{1,3}\.\d{1,3}\.\d{1,3}$/))
  const post = await blogPost(version).catch(() => null)
  if (!post) throw notFound('unknown_version', 'Version not in the changelog')
  return { block: updateBlockFromPost(post, 'update') }
})
