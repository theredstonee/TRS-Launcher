import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../../lib/context'
import { leave } from '../../../../../lib/hosting'
import { noContent, paramWith, requireUser } from '../../../../../lib/http'
import { roomIdSchema } from '../../../../../lib/schemas'

/** Verlassen, Anfrage zurückziehen oder Einladung ablehnen. */
export default defineEventHandler((event) => {
  const auth = requireUser(event, 'hosting')
  const id = paramWith(event, 'id', roomIdSchema)
  leave(useCtx(), auth.uuid, id)
  return noContent(event)
})
