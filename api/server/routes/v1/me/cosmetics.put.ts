import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { equipCosmetics, ownedEmotes } from '../../../lib/cosmetics'
import { readJson, requireUser } from '../../../lib/http'
import { equipBody } from '../../../lib/schemas'

/** Plätze setzen: `{ hat?, wings?, back?, aura? }` – ID anlegen, `null` ablegen, fehlend unverändert. */
export default defineEventHandler(async (event) => {
  const auth = requireUser(event, 'write')
  const body = await readJson(event, equipBody)
  const ctx = useCtx()
  return { equipped: equipCosmetics(ctx, auth.uuid, body), emotes: ownedEmotes(ctx, auth.uuid) }
})
