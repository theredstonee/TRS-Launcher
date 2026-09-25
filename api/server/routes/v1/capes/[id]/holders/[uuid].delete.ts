import { defineEventHandler } from 'h3'
import { revokeShare } from '../../../../../lib/capeshares'
import { useCtx } from '../../../../../lib/context'
import { limit, noContent, paramWith, requireUser } from '../../../../../lib/http'
import { RULES } from '../../../../../lib/ratelimit'
import { capeIdSchema, uuidSchema } from '../../../../../lib/schemas'

/**
 * Teilung entziehen bzw. offenes Angebot zurückziehen – samt allem, was dieser Inhaber
 * weitergeteilt hat. Mit der eigenen UUID: geteilten Umhang zurückgeben.
 */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'write')
  limit(`capeshare:${auth.uuid}`, RULES.capeShareUser)
  const id = paramWith(event, 'id', capeIdSchema)
  const holder = paramWith(event, 'uuid', uuidSchema)
  revokeShare(useCtx(), auth.uuid, id, holder)
  return noContent(event)
})
