import { z } from 'zod'
import { normalizeUuid } from './ids'

export interface MojangProfile {
  uuid: string
  name: string
}

export interface MojangClient {
  /**
   * Fragt den Session-Server, ob `username` mit `serverId` beigetreten ist
   * (wie ein Minecraft-Server beim Login). `null` = nicht beigetreten.
   * Wirft bei Netzwerk-/Serverfehlern.
   */
  hasJoined(username: string, serverId: string): Promise<MojangProfile | null>
  /** Name → UUID (api.mojang.com). `null` = unbekannter Name. */
  profileByName(name: string): Promise<MojangProfile | null>
  /** Skin-Daten eines Profils (Session-Server). `null` = unbekannte UUID. */
  skinProfile(uuid: string): Promise<SkinProfile | null>
}

export interface SkinProfile {
  uuid: string
  name: string
  model: 'classic' | 'slim'
  /** `https://textures.minecraft.net/texture/<hash>` oder `null` (Standard-Skin). */
  skinUrl: string | null
  capeUrl: string | null
}

const hasJoinedResponse = z.object({
  id: z.string(),
  name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/),
})

const skinProfileResponse = z.object({
  id: z.string(),
  name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/),
  properties: z.array(z.object({ name: z.string(), value: z.string().max(16 * 1024) })).max(16).default([]),
})

const texturesPayload = z.object({
  textures: z
    .object({
      SKIN: z.object({ url: z.string(), metadata: z.object({ model: z.string() }).partial().optional() }).optional(),
      CAPE: z.object({ url: z.string() }).optional(),
    })
    .default({}),
})

export class MojangUnavailable extends Error {}

/** Nur echte Mojang-Textur-URLs durchlassen (immer als https). */
export function textureUrl(raw: string | undefined): string | null {
  if (!raw) return null
  const m = /^https?:\/\/textures\.minecraft\.net\/texture\/([0-9a-f]{1,128})$/.exec(raw)
  return m ? `https://textures.minecraft.net/texture/${m[1]}` : null
}

export function createMojangClient(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch,
  apiUrl = 'https://api.mojang.com',
): MojangClient {
  /** GET mit Zeitlimit; `null` bei 204/404 (bzw. 403 für hasJoined), JSON sonst. */
  async function getJson(what: string, url: URL, nullOn: number[]): Promise<unknown> {
    let res: Response
    try {
      res = await fetchImpl(url, {
        headers: { 'User-Agent': 'TRS-API/1 (+https://github.com/theredstonee/TRS-Launcher)', Accept: 'application/json' },
        redirect: 'error',
        signal: AbortSignal.timeout(5000),
      })
    } catch (err) {
      throw new MojangUnavailable(`${what} request failed`, { cause: err })
    }
    if (nullOn.includes(res.status)) return null
    if (!res.ok) throw new MojangUnavailable(`${what} returned HTTP ${res.status}`)
    const text = await res.text()
    if (text.length === 0) return null
    if (text.length > 64 * 1024) throw new MojangUnavailable(`${what} response too large`)
    try {
      return JSON.parse(text)
    } catch {
      throw new MojangUnavailable(`${what} returned invalid JSON`)
    }
  }

  return {
    async hasJoined(username, serverId) {
      const url = new URL('/session/minecraft/hasJoined', baseUrl)
      url.searchParams.set('username', username)
      url.searchParams.set('serverId', serverId)
      const json = await getJson('hasJoined', url, [204, 404, 403])
      if (json === null) return null
      const parsed = hasJoinedResponse.safeParse(json)
      if (!parsed.success) throw new MojangUnavailable('hasJoined returned an unexpected shape')
      const uuid = normalizeUuid(parsed.data.id)
      if (!uuid) throw new MojangUnavailable('hasJoined returned an invalid id')
      return { uuid, name: parsed.data.name }
    },

    async profileByName(name) {
      const url = new URL(`/users/profiles/minecraft/${encodeURIComponent(name)}`, apiUrl)
      const json = await getJson('profileByName', url, [204, 404])
      if (json === null) return null
      const parsed = hasJoinedResponse.safeParse(json)
      if (!parsed.success) throw new MojangUnavailable('profileByName returned an unexpected shape')
      const uuid = normalizeUuid(parsed.data.id)
      if (!uuid) throw new MojangUnavailable('profileByName returned an invalid id')
      return { uuid, name: parsed.data.name }
    },

    async skinProfile(uuid) {
      const url = new URL(`/session/minecraft/profile/${uuid}`, baseUrl)
      const json = await getJson('skinProfile', url, [204, 404])
      if (json === null) return null
      const parsed = skinProfileResponse.safeParse(json)
      if (!parsed.success) throw new MojangUnavailable('skinProfile returned an unexpected shape')
      const id = normalizeUuid(parsed.data.id)
      if (!id) throw new MojangUnavailable('skinProfile returned an invalid id')
      const prop = parsed.data.properties.find((p) => p.name === 'textures')
      let skin: z.infer<typeof texturesPayload>['textures'] = {}
      if (prop) {
        try {
          const t = texturesPayload.safeParse(JSON.parse(Buffer.from(prop.value, 'base64').toString('utf8')))
          if (t.success) skin = t.data.textures
        } catch {
          // Kaputte Eigenschaft → wie Standard-Skin behandeln.
        }
      }
      return {
        uuid: id,
        name: parsed.data.name,
        model: skin.SKIN?.metadata?.model === 'slim' ? 'slim' : 'classic',
        skinUrl: textureUrl(skin.SKIN?.url),
        capeUrl: textureUrl(skin.CAPE?.url),
      }
    },
  }
}
