import { defineEventHandler } from 'h3'
import { listHolders } from '../../../../../lib/capeshares'
import { useCtx } from '../../../../../lib/context'
import { paramWith, requireUser } from '../../../../../lib/http'
import { capeIdSchema } from '../../../../../lib/schemas'

/** Wer hat diesen Umhang von mir (Ersteller: alle; Inhaber: der eigene Ast), angenommen oder angeboten? */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return listHolders(useCtx(), auth.uuid, paramWith(event, 'id', capeIdSchema))
})
