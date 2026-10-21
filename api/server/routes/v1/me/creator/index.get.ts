import { defineEventHandler } from 'h3'
import { useCtx } from '../../../../lib/context'
import { requireWebOrUser } from '../../../../lib/http'
import { myCreatorState } from '../../../../lib/creators'

/** Creator-Seite im Launcher (§35.2): eigene Unterstützung, Fan-Umhänge, Blockaden, Einstellung, ggf. eigene Creator-Sicht. */
export default defineEventHandler((event) => {
  const auth = requireWebOrUser(event)
  return myCreatorState(useCtx(), auth.uuid)
})
