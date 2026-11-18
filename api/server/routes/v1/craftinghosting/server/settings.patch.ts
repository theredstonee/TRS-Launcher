import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingSetSettings, settingsBody } from '../../../../lib/craftinghosting'
import { readJson, requireUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'craftinghosting')
  const body = await readJson(event, settingsBody)
  return { settings: await craftingSetSettings(useCtx(), auth.user, body) }
})
