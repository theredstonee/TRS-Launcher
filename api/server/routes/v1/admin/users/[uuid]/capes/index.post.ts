import { defineEventHandler } from 'h3'
import { assertKnownUser, audit } from '../../../../../../lib/admin'
import { capeView, getCape, grantCape } from '../../../../../../lib/capes'
import { useCtx } from '../../../../../../lib/context'
import { badRequest, notFound } from '../../../../../../lib/errors'
import { created, paramWith, readJson, requireAdmin } from '../../../../../../lib/http'
import { grantCapeBody, uuidSchema } from '../../../../../../lib/schemas'

/** Standard-Umhang (code/admin) direkt zuteilen. */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, grantCapeBody)
  const ctx = useCtx()
  assertKnownUser(ctx, uuid)
  const cape = getCape(ctx, body.capeId)
  if (!cape || cape.kind !== 'builtin' || cape.retired) throw notFound('cape_not_found', 'Cape not found')
  if (cape.unlock === 'free') throw badRequest('cape_is_free', 'This cape is free for everyone')
  const granted = grantCape(ctx, uuid, cape.id, 'admin')
  audit(ctx, actor, 'cape.grant', uuid, cape.id)
  return created(event, { cape: capeView(ctx, cape), alreadyOwned: !granted })
})
