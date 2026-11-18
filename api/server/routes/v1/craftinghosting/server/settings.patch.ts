import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { craftingSetSettings, settingsBody } from '../../../../lib/craftinghosting'
import { readJson, requireWebOrUser } from '../../../../lib/http'

export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const body = await readJson(event, settingsBody)
  return { settings: await craftingSetSettings(useCtx(), auth.user, body) }
})
