import { defineEventHandler } from 'h3'
import { audit } from '../../../../../../lib/admin'
import { revokeCape } from '../../../../../../lib/capes'
import { useCtx } from '../../../../../../lib/context'
import { notFound } from '../../../../../../lib/errors'
import { noContent, paramWith, requireAdmin } from '../../../../../../lib/http'
import { capeIdSchema, uuidSchema } from '../../../../../../lib/schemas'

/** Zuteilung entziehen (egal ob per Code oder Admin); ist er aktiv, wird er abgelegt. */
export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const capeId = paramWith(event, 'capeId', capeIdSchema)
  const ctx = useCtx()
  if (!revokeCape(ctx, uuid, capeId)) throw notFound('grant_not_found', 'This user does not own this cape through a grant or code')
  audit(ctx, actor, 'cape.revoke', uuid, capeId)
  return noContent(event)
})
