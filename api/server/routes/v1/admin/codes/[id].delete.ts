import { defineEventHandler } from 'h3'
import { audit } from '../../../../lib/admin'
import { revokeCode } from '../../../../lib/codes'
import { useCtx } from '../../../../lib/context'
import { noContent, paramWith, requireAdmin } from '../../../../lib/http'
import { codeIdSchema } from '../../../../lib/schemas'

/** Code widerrufen (bereits freigeschaltete Umhänge bleiben). */
export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  const id = paramWith(event, 'id', codeIdSchema)
  const ctx = useCtx()
  revokeCode(ctx, id)
  audit(ctx, actor, 'codes.revoke', null, String(id))
  return noContent(event)
})
