import { defineEventHandler } from 'h3'
import { z } from 'zod'
import { useCtx } from '../../../lib/context'
import { limit, readJson, requireStaff } from '../../../lib/http'
import { RULES } from '../../../lib/ratelimit'
import { uuidSchema } from '../../../lib/schemas'
import { storedSkins } from '../../../lib/skins'

const Body = z.strictObject({ uuids: z.array(uuidSchema).min(1).max(120) })

/** Neue Mojang-Abfragen je Aufruf (Rest kommt als `pending` und wird vom Browser kurz danach erneut gefragt). */
const FRESH_PER_CALL = 6
/** Gespeicherte Skins gelten einen Tag, danach wird (im Rahmen von FRESH_PER_CALL) neu gefragt. */
const STALE_MS = 24 * 60 * 60_000

/**
 * Köpfe für Listen im Team-Bereich (§26.3): gespeicherte Skin-Adressen aus der Datenbank, fehlende oder alte
 * werden in kleinen Portionen bei Mojang nachgeschlagen (und dabei gespeichert). → `{ heads, pending }`.
 */
export default defineEventHandler(async (event) => {
  const staff = requireStaff(event)
  limit(`admin-heads:${staff.uuid}`, RULES.adminHeads)
  const { uuids } = await readJson(event, Body)
  const ctx = useCtx()
  const wanted = [...new Set(uuids)]
  const stored = storedSkins(ctx.db, wanted)
  const heads: Record<string, { url: string | null, model: 'classic' | 'slim' } | null> = {}
  const refresh: string[] = []
  for (const u of wanted) {
    const s = stored.get(u)
    if (s && s.at !== null) heads[u] = { url: s.url, model: s.model }
    if (!s || s.at === null || ctx.now() - s.at > STALE_MS) refresh.push(u)
  }
  const now = refresh.slice(0, FRESH_PER_CALL)
  await Promise.all(now.map(async (u) => {
    try {
      const v = await ctx.skins.byUuid(u)
      heads[u] = { url: v.textureUrl, model: v.model }
    } catch {
      // Unbekannt, Mojang nicht erreichbar oder Limit: bleibt beim gespeicherten Stand bzw. Anfangsbuchstaben.
      if (!(u in heads)) heads[u] = null
    }
  }))
  const pending = refresh.slice(FRESH_PER_CALL).filter((u) => !(u in heads))
  return { heads, pending }
})
