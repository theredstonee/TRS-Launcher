import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { roomContent } from '../../../../../lib/hosting'
import { paramWith, requireUser } from '../../../../../lib/http'
import { roomIdSchema } from '../../../../../lib/schemas'

/** Geteilte Mods + Resource Pack einer Welt (§21.10) – für jeden, der die Welt sehen darf. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  return roomContent(useCtx(), auth.uuid, id)
})
