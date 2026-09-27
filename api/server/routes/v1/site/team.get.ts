import { defineEventHandler, setResponseHeader } from 'h3'
import { publicJobs } from '../../../lib/applications'
import { useCtx } from '../../../lib/context'
import { publicTeam } from '../../../lib/team'

/** Skin-Adressen der Team-Mitglieder (für die Köpfe), 5 min im Speicher – so fragt die Seite Mojang kaum. */
const skinCache = new Map<string, { url: string | null, at: number }>()
const SKIN_TTL_MS = 5 * 60_000

async function skinOf(uuid: string): Promise<string | null> {
  const ctx = useCtx()
  const hit = skinCache.get(uuid)
  if (hit && ctx.now() - hit.at < SKIN_TTL_MS) return hit.url
  let url: string | null
  try {
    url = (await ctx.skins.byUuid(uuid)).textureUrl
  } catch {
    // Mojang nicht erreichbar oder Limit: Kopf fällt auf den Anfangsbuchstaben zurück.
    url = hit?.url ?? null
  }
  if (skinCache.size > 500) skinCache.clear()
  skinCache.set(uuid, { url, at: ctx.now() })
  return url
}

/** Website: Team-Seite (§23.3) – öffentliche Rollen mit Mitgliedern (Name, Kopf) und offene Stellen. 60 s cachebar. */
export default defineEventHandler(async (event) => {
  const ctx = useCtx()
  const team = publicTeam(ctx)
  const roles = await Promise.all(team.roles.map(async (r) => ({
    ...r,
    members: await Promise.all(r.members.map(async (m) => ({ ...m, skin: await skinOf(m.uuid) }))),
  })))
  setResponseHeader(event, 'Cache-Control', 'public, max-age=60')
  return { team: { roles }, jobs: publicJobs(ctx) }
})
