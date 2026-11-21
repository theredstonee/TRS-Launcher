import { defineEventHandler, getQuery } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, limit, paramWith, readPng, requireStaff } from '../../../../../lib/http'
import { adminUploadPartnerCape, CAPE_MAX_BYTES, PARTNER_SLUG } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Team: Partner-Umhang direkt hochladen (`?name=&frames=&frameTimeMs=`, raw PNG, HD/animiert) → 201 `{ partner, cape }`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, adminUploadPartnerCape(useCtx(), staff, slug, await readPng(event, CAPE_MAX_BYTES), { ...getQuery<Record<string, unknown>>(event) }))
})
