import { defineEventHandler } from 'h3'
import { banUser, userInfo } from '../../../../../lib/admin'
import { useCtx } from '../../../../../lib/context'
import { paramWith, readJson, requireStaff } from '../../../../../lib/http'
import { reasonBody, uuidSchema } from '../../../../../lib/schemas'

/** Sperrt ein Konto (auch vorsorglich, wenn es TRS noch nie benutzt hat): alle Funktionen blockiert, Sitzungen beendet. */
export default defineEventHandler(async (event) => {
  const actor = requireStaff(event, 'sanctions.ban')
  const uuid = paramWith(event, 'uuid', uuidSchema)
  const body = await readJson(event, reasonBody)
  const ctx = useCtx()
  banUser(ctx, actor, uuid, body?.reason)
  return { user: userInfo(ctx, uuid) }
})
