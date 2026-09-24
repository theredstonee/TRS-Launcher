import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../lib/context'
import { publicCapes } from '../../../lib/site'

/** Website: öffentliche Galerie der mitgelieferten TRS-Umhänge. */
export default defineEventHandler((event) => {
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  return { capes: publicCapes(useCtx()) }
})
