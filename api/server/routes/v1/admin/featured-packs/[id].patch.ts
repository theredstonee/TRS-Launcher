import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { featuredPatchBody, updateFeatured } from '../../../../lib/featured'
import { limit, paramWith, readJson, requireStaff } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/** Texte (EN/DE), Abzeichen, Anheften, Zeitfenster ändern (§37.2). `null` leert ein Feld. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'featured.manage')
  limit(`adminFeatured:${staff.uuid}`, RULES.adminFeatured)
  const id = paramWith(event, 'id', z.string().max(40))
  const body = await readJson(event, featuredPatchBody)
  return { pack: updateFeatured(useCtx(), staff, id, body) }
})
