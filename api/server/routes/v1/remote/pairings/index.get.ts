import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../../lib/context'
import { listPeers } from '../../../../lib/remote'
import { requireDevice } from '../../../../lib/remote-http'

/** Gekoppelte Geräte (§33.3): am PC die Handys, am Handy die PCs mit `online` und letztem Status. */
export default defineEventHandler((event) => {
  const { device } = requireDevice(event, 'read')
  setResponseHeader(event, 'Cache-Control', 'no-store')
  return listPeers(useCtx(), device)
})
