import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { createFeatured, featuredCreateBody } from '../../../../lib/featured'
import { created, limit, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Pack per Modrinth-/CurseForge-Link hinzufügen (§37.2): Daten + Bilder holen, ans Ende der Liste. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'featured.manage')
  limit(`adminFeatured:${staff.uuid}`, RULES.adminFeatured)
  const body = await readJson(event, featuredCreateBody)
  return created(event, { pack: await createFeatured(useCtx(), staff, body) })
})
