import { defineEventHandler } from 'h3'
import { touchAchievements } from '../../../lib/achievements'
import { setActiveCape } from '../../../lib/capes'
import { useCtx } from '../../../lib/context'
import { readJson, requireUser } from '../../../lib/http'
import { setCapeBody } from '../../../lib/schemas'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, setCapeBody)
  const ctx = useCtx()
  const activeCape = setActiveCape(ctx, auth.uuid, body.capeId)
  if (activeCape) touchAchievements(ctx, auth.uuid, ['cape_worn_flag'])
  return { activeCape }
})
