import { defineEventHandler, setResponseHeaders } from 'h3'
import { buildRobots } from '../../shared/seo'
import { useCtx } from '../lib/context'

/** Website: alles außer API und Admin darf gecrawlt werden (Ausnahmen siehe shared/seo.ts). */
export default defineEventHandler((event) => {
  setResponseHeaders(event, { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'public, max-age=86400' })
  return buildRobots(useCtx().config.siteUrl)
})
