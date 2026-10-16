import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { reviewQueue } from '../../../../../lib/creators'
import { limit, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Prüf-Warteschlange (§35.6): wartende Fan-Symbole und Fan-Umhänge, älteste zuerst. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'creators.review')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return reviewQueue(useCtx())
})
