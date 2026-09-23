import { defineEventHandler } from 'h3'
import { assertKnownUser, audit } from '../../../../../../lib/admin'
import { useCtx } from '../../../../../../lib/context'
import { cosmeticView, getCosmetic, grantCosmetic } from '../../../../../../lib/cosmetics'
import { badRequest, notFound } from '../../../../../../lib/errors'
import { created, paramWith, readJson, requireAdmin } from '../../../../../../lib/http'
import { grantCosmeticBody, uuidSchema } from '../../../../../../lib/schemas'

/** Mitgelieferte Kosmetik oder Emote (code/admin) direkt zuteilen. */
export default defineEventHandler(async (event) => {
  const actor = requireAdmin(event)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, grantCosmeticBody)
  const ctx = useCtx()
  assertKnownUser(ctx, uuid)
  const c = getCosmetic(ctx, body.cosmeticId)
  if (!c || c.kind !== 'builtin' || c.retired) throw notFound('cosmetic_not_found', 'Cosmetic not found')
  if (c.unlock === 'free') throw badRequest('cosmetic_is_free', 'This cosmetic is free for everyone')
  const granted = grantCosmetic(ctx, uuid, c.id, 'admin')
  audit(ctx, actor, 'cosmetic.grant', uuid, c.id)
  return created(event, { cosmetic: cosmeticView(ctx, c), alreadyOwned: !granted })
})
