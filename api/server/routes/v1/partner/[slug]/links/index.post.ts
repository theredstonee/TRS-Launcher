import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../lib/context'
import { created, paramWith, readJson, requireWebOrUser } from '../../../../../lib/http'
import { addCampaign, PARTNER_SLUG } from '../../../../../lib/partners'

/** Kampagne anlegen: `{ campaign }` → 201 `{ campaigns }` (max. 20). Recht `links`. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  const slug = paramWith(event, 'slug', z.string().regex(PARTNER_SLUG))
  return created(event, addCampaign(useCtx(), auth.uuid, slug, await readJson(event, z.unknown())))
})
