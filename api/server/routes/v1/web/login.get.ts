import { defineEventHandler } from 'h3'
import { useCtx } from '../../../lib/context'

/**
 * Website: Welche Anmeldungen gibt es? Microsoft nur mit MS_CLIENT_ID/MS_CLIENT_SECRET; die Anmeldung per TRS Launcher
 * (§29) ist immer da.
 */
export default defineEventHandler(() => ({ microsoft: useCtx().config.microsoft !== null, launcher: true }))
