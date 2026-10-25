import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminSetPetLimits } from '../../../../../lib/creator-pets'
import { limit, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Monatsgrenzen für Haustier-Codes je Stufe (§35.13): `{ starter, partner, top }` (0 bis 1000000). → `{ limits }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return adminSetPetLimits(useCtx(), staff, await readJson(event, z.unknown()))
})
