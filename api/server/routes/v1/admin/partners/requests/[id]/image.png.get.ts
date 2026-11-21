import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../lib/context'
import { limit, paramWith, requireStaff, sendPng } from '../../../../../../lib/http'
import { adminRequestImage } from '../../../../../../lib/partners'
import { RULES } from '../../../../../../lib/ratelimit'

/** Bild eines offenen Logo-/Umhang-Antrags. */
export default defineEventHandler((event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return sendPng(event, `request-${id}.png`, adminRequestImage(useCtx(), staff, id))
})
