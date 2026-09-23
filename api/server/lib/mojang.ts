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
}

const hasJoinedResponse = z.object({
  id: z.string(),
  name: z.string().regex(/^[A-Za-z0-9_]{1,16}$/),
})

export class MojangUnavailable extends Error {}

export function createMojangClient(baseUrl: string, fetchImpl: typeof fetch = fetch): MojangClient {
  return {
    async hasJoined(username, serverId) {
      const url = new URL('/session/minecraft/hasJoined', baseUrl)
      url.searchParams.set('username', username)
      url.searchParams.set('serverId', serverId)
      let res: Response
      try {
        res = await fetchImpl(url, {
          headers: { 'User-Agent': 'TRS-API/1 (+https://github.com/theredstonee/TRS-Launcher)', Accept: 'application/json' },
          redirect: 'error',
          signal: AbortSignal.timeout(5000),
        })
      } catch (err) {
        throw new MojangUnavailable('hasJoined request failed', { cause: err })
      }
      if (res.status === 204 || res.status === 404 || res.status === 403) return null
      if (!res.ok) throw new MojangUnavailable(`hasJoined returned HTTP ${res.status}`)
      const text = await res.text()
      if (text.length === 0) return null
      if (text.length > 64 * 1024) throw new MojangUnavailable('hasJoined response too large')
      let json: unknown
      try {
        json = JSON.parse(text)
      } catch {
        throw new MojangUnavailable('hasJoined returned invalid JSON')
      }
      const parsed = hasJoinedResponse.safeParse(json)
      if (!parsed.success) throw new MojangUnavailable('hasJoined returned an unexpected shape')
      const uuid = normalizeUuid(parsed.data.id)
      if (!uuid) throw new MojangUnavailable('hasJoined returned an invalid id')
      return { uuid, name: parsed.data.name }
    },
  }
}
