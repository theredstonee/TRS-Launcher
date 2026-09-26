import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'
import { myModeration } from '../../../lib/moderation'

/** Eigene Stummschaltung (falls aktiv) und Verwarnungen der letzten 90 Tage. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return myModeration(useCtx(), auth.uuid)
})
