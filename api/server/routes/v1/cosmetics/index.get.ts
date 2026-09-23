import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'
import { cosmeticCatalog } from '../../../lib/cosmetics'
import { requireUser } from '../../../lib/http'

/** Katalog aus Sicht des Nutzers: Vorlagen + mitgelieferte Teile und Emotes (mit `owned`) + eigene Uploads. */
export default defineEventHandler((event) => {
  const auth = requireUser(event)
  const ctx = useCtx()
  return { templates: ctx.templates.list, cosmetics: cosmeticCatalog(ctx, auth.uuid) }
})
