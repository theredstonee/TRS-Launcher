import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { publicCompanions, publicHats } from '../../../lib/site'

/** Website: öffentliche Galerie der mitgelieferten Kopf-Kosmetik (Format v2, ohne versteckte Teile): `hats` (Platz hat) und `companions` (Begleiter). */
export default defineEventHandler((event) => {
  // 60 s: Event-Teile erscheinen/verschwinden mit dem Event.
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  const ctx = useCtx()
  return { hats: publicHats(ctx), companions: publicCompanions(ctx) }
})
