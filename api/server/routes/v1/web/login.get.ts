import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'

/** Website: Ist die Anmeldung mit Microsoft eingerichtet? (Ohne MS_CLIENT_ID/MS_CLIENT_SECRET ist sie aus.) */
export default defineEventHandler(() => ({ microsoft: useCtx().config.microsoft !== null }))
