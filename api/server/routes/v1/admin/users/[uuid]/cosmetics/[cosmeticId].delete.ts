import { defineEventHandler } from 'h3'
import { audit } from '../../../../../../lib/admin'
import { useCtx } from '../../../../../../lib/context'
import { revokeCosmetic } from '../../../../../../lib/cosmetics'
import { notFound } from '../../../../../../lib/errors'
import { noContent, paramWith, requireAdmin } from '../../../../../../lib/http'
import { cosmeticIdSchema, uuidSchema } from '../../../../../../lib/schemas'

/** Zuteilung entziehen (egal ob per Code oder Admin); ist das Teil ausgerüstet, wird es abgelegt. */
export default defineEventHandler((event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const id = paramWith(event, 'cosmeticId', cosmeticIdSchema)
  const ctx = useCtx()
  if (!revokeCosmetic(ctx, uuid, id)) {
    throw notFound('grant_not_found', 'This user does not own this cosmetic through a grant or code')
  }
  audit(ctx, actor, 'cosmetic.revoke', uuid, id)
  return noContent(event)
})
