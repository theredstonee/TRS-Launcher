import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../../../../../lib/context'
import { limit, paramWith, readJson, requireStaff } from '../../../../../../../lib/http'
import { gameSchema, removeScore } from '../../../../../../../lib/minigames'
import { RULES } from '../../../../../../../lib/ratelimit'
import { uuidSchema } from '../../../../../../../lib/schemas'

/** Rangliste: falschen Eintrag entfernen (§42.4): `{ reason }` → `{ ok: true }`. Recht `minigames.manage`. */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event, 'minigames.manage')
  limit(`admin-minigames:${staff.uuid}`, RULES.adminMinigames)
  const game = paramWith(event, 'game', gameSchema)
  const uuid = paramWith(event, 'uuid', uuidSchema)
  removeScore(useCtx(), staff, game, uuid, await readJson(event, z.unknown()))
  return { ok: true }
})
