import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../lib/context'
import { actingAs } from '../../../../lib/creator-http'
import { limit, readJson, requireWebOrUser } from '../../../../lib/http'
import { setShowPetExtras } from '../../../../lib/pet-extras'
import { RULES } from '../../../../lib/ratelimit'

/** Schalter „Gefolge zeigen“ (§35.13): `{ show: boolean }`, nur der Creator selbst. → `{ extras, showExtras, max }` */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'write')
  limit(`creator-manage:${auth.uuid}`, RULES.creatorManageUser)
  return setShowPetExtras(useCtx(), auth.uuid, await readJson(event, z.unknown()), actingAs(event))
})
