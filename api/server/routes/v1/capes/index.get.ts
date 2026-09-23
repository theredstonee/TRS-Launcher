import { defineEventHandler } from 'h3'
import { catalog } from '../../../lib/capes'
import { useCtx } from '../../../lib/context'
import { requireUser } from '../../../lib/http'

/** Katalog aus Sicht des Nutzers: Standard-Designs (mit `owned`) + eigene Uploads (mit Status). */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  return { capes: catalog(useCtx(), auth.uuid) }
})
