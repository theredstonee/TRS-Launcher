import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { publicCompanions, publicHats, publicPets, publicWings } from '../../../lib/site'

/**
 * Website: öffentliche Galerie der mitgelieferten Kosmetik im Format v2 (ohne versteckte Teile): `hats` (Platz hat),
 * `companions` (Begleiter am Kopf), `wings` (Flügel) und `pets` (Begleiter am Boden, z. B. der Pinguin).
 */
export default defineEventHandler((event) => {
  // 60 s: Event-Teile erscheinen/verschwinden mit dem Event.
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  const ctx = useCtx()
  return { hats: publicHats(ctx), companions: publicCompanions(ctx), wings: publicWings(ctx), pets: publicPets(ctx) }
})
