import { defineEventHandler, setResponseHeader } from 'h3'
import { useCtx } from '../../../../lib/context'
import { publicFeatured, refreshStaleInBackground, type FeaturedPack } from '../../../../lib/featured'
import { clientIp, limit } from '../../../../lib/http'
import { RULES } from '../../../../lib/ratelimit'

/**
 * Empfohlene Modpacks (§37.1) für die Startseiten von Launcher und Website: nur aktive Packs, angeheftet zuerst, dann
 * nach Reihenfolge, höchstens 20. Alte Plattform-Daten (> 24 h) frischt der Server danach still im Hintergrund auf.
 */
export default defineEventHandler((event) => {
  limit(`featuredPublic:${clientIp(event)}`, RULES.featuredPublicIp)
  setResponseHeader(event, 'Cache-Control', 'public, max-age=300')
  const ctx = useCtx()
  let packs: FeaturedPack[] = []
  try {
    packs = publicFeatured(ctx)
  } catch (err) {
    console.error('[trs-api] featured packs could not be read', (err as Error).message)
  }
  try {
    refreshStaleInBackground(ctx)
  } catch {
    // Auffrischen ist nur Beiwerk.
  }
  return { packs }
})
