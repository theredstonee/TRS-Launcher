import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { adminDecideRequest } from '../../../../../lib/partners'
import { RULES } from '../../../../../lib/ratelimit'

/** Antrag entscheiden: `{ status: "accepted"|"rejected", reason? }` (Ablehnen mit Grund). */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'partners.manage')
  limit(`admin-partners:${staff.uuid}`, RULES.adminPartners)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return adminDecideRequest(useCtx(), staff, id, await readJson(event, z.unknown()))
})
