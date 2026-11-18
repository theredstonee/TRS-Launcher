import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { craftingWorldFiles, worldNameQuery } from '../../../../../lib/craftinghosting'
import { limit, queryWith, requireWebOrUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'

/** Dateiliste einer Welt für den Download im Launcher. */
export default defineEventHandler(async (event) => {
  const auth = requireWebOrUser(event, 'craftinghosting')
  const q = queryWith(event, worldNameQuery)
  limit(`craftinghostingFiles:${auth.uuid}`, RULES.craftinghostingFilesUser)
  return craftingWorldFiles(useCtx(), auth.user, q.name)
})
