import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { adminCreatePreview } from '../../../../../lib/creators'
import { created, limit, readJson, requireStaff } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Vorschau-Build eintragen (§35.6): `{ product, version, notes?, url, sha256, publishedAt? }`. → 201 `{ preview }` */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'creators.manage')
  limit(`admin-creators:${staff.uuid}`, RULES.adminCreators)
  return created(event, adminCreatePreview(useCtx(), staff, await readJson(event, z.unknown())))
})
