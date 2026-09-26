import type { ApiEvent } from '../server/lib/events'
import { acceptRequest, sendRequest } from '../server/lib/friends'
import { getUser, type UserRow } from '../server/lib/users'
import { login, type TestEnv } from './helpers'

/** Fehlercode eines Aufrufs (`ok` ohne Fehler). */
export function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? String(e)
  }
  return 'ok'
}

export async function codeAsync(fn: () => Promise<unknown>): Promise<string> {
  try {
    await fn()
  } catch (e) {
    return (e as { code?: string }).code ?? String(e)
  }
  return 'ok'
}

export async function players(env: TestEnv, ...names: string[]): Promise<UserRow[]> {
  const out: UserRow[] = []
  for (const n of names) out.push(getUser(env.ctx, (await login(env, n)).user.uuid)!)
  return out
}

export function befriend(env: TestEnv, a: UserRow, b: UserRow): void {
  sendRequest(env.ctx, a, { uuid: b.uuid })
  acceptRequest(env.ctx, getUser(env.ctx, b.uuid)!, a.uuid)
}

export interface Listened {
  events: { e: ApiEvent, id: string | null }[]
  of: <T extends ApiEvent['type']>(type: T) => Extract<ApiEvent, { type: T }>[]
  clear: () => void
}

/** Abonniert wie `GET /v1/events/me` und sammelt die Ereignisse. */
export function listen(env: TestEnv, uuid: string): Listened {
  const events: { e: ApiEvent, id: string | null }[] = []
  env.ctx.events.subscribe(uuid, (e, id) => events.push({ e, id }), () => {}, 'me')
  return {
    events,
    of: <T extends ApiEvent['type']>(type: T) => events.filter((x) => x.e.type === type).map((x) => x.e) as Extract<ApiEvent, { type: T }>[],
    clear: () => {
      events.length = 0
    },
  }
}
