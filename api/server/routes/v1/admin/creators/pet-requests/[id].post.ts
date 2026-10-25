import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminDecidePetRequest } from '../../../../../lib/creator-pets'
import { limit, paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Haustier-Wunsch bearbeiten (§35.13): `{ status, reply?, cosmeticId? (nur mit done) }`. → `{ requests }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  const id = paramWith(event, 'id', z.coerce.number().int().min(1).max(Number.MAX_SAFE_INTEGER))
  return adminDecidePetRequest(useCtx(), staff, id, await readJson(event, z.unknown()))
})
