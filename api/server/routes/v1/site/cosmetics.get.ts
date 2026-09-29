import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { publicHats } from '../../../lib/site'

/** Website: öffentliche Galerie der mitgelieferten Kopf-Kosmetik (Format v2, ohne versteckte Teile). */
export default defineEventHandler((event) => {
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { hats: publicHats(useCtx()) }
})
