import { describe, expect, it } from 'vitest'
import { banUser } from '../server/lib/admin'
import type { ApiEvent } from '../server/lib/events'
import {
  acceptRequest,
  block,
  broadcastPresence,
  cancelRequest,
  declineRequest,
  listBlocks,
  listFriends,
  removeFriend,
  sendRequest,
} from '../server/lib/friends'
import { deleteUser, getUser, updateSettings } from '../server/lib/users'
import { login, makeEnv, type TestEnv } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code: string }).code
  }
  return 'ok'
}

async function players(env: TestEnv, ...names: string[]) {
  const out = []
  for (const n of names) out.push(getUser(env.ctx, (await login(env, n)).user.uuid)!)
  return out
}

function listen(env: TestEnv, uuid: string): ApiEvent[] {
  const got: ApiEvent[] = []
  env.ctx.events.subscribe(uuid, (e) => got.push(e), () => {})
  return got
}

describe('friend requests', () => {
  it('request by name → accept → both are friends; events fire', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    const evB = listen(env, b!.uuid)
    const evA = listen(env, a!.uuid)
    expect(sendRequest(env.ctx, a!, { name: 'bob' })).toMatchObject({ status: 'sent', user: { uuid: b!.uuid, name: 'Bob' } })
    expect(evB).toEqual([{ type: 'friend_request', from: { uuid: a!.uuid, name: 'Alex' } }])
    expect(listFriends(env.ctx, b!.uuid).requests.incoming.map((r) => r.name)).toEqual(['Alex'])
    expect(listFriends(env.ctx, a!.uuid).requests.outgoing.map((r) => r.name)).toEqual(['Bob'])
    expect(code(() => sendRequest(env.ctx, a!, { uuid: b!.uuid }))).toBe('already_requested')

    acceptRequest(env.ctx, b!, a!.uuid)
    expect(evA.at(-1)).toEqual({ type: 'friend_added', friend: { uuid: b!.uuid, name: 'Bob' } })
    expect(listFriends(env.ctx, a!.uuid).friends.map((f) => f.name)).toEqual(['Bob'])
    expect(listFriends(env.ctx, b!.uuid).friends.map((f) => f.name)).toEqual(['Alex'])
    expect(listFriends(env.ctx, b!.uuid).requests.incoming).toEqual([])
    expect(code(() => sendRequest(env.ctx, a!, { name: 'Bob' }))).toBe('already_friends')
  })

  it('crossing requests become a friendship immediately', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    expect(sendRequest(env.ctx, b!, { uuid: a!.uuid }).status).toBe('accepted')
    expect(listFriends(env.ctx, a!.uuid).friends).toHaveLength(1)
  })

  it('decline, cancel, remove', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    declineRequest(env.ctx, b!.uuid, a!.uuid)
    expect(listFriends(env.ctx, a!.uuid).requests.outgoing).toEqual([])
    expect(code(() => declineRequest(env.ctx, b!.uuid, a!.uuid))).toBe('request_not_found')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    cancelRequest(env.ctx, a!.uuid, b!.uuid)
    expect(listFriends(env.ctx, b!.uuid).requests.incoming).toEqual([])
    expect(code(() => acceptRequest(env.ctx, b!, a!.uuid))).toBe('request_not_found')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    acceptRequest(env.ctx, b!, a!.uuid)
    removeFriend(env.ctx, b!.uuid, a!.uuid)
    expect(listFriends(env.ctx, a!.uuid).friends).toEqual([])
    expect(code(() => removeFriend(env.ctx, b!.uuid, a!.uuid))).toBe('friend_not_found')
  })

  it('only TRS users, never yourself', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    expect(code(() => sendRequest(env.ctx, a!, { name: 'Notch' }))).toBe('player_not_found')
    expect(code(() => sendRequest(env.ctx, a!, { uuid: a!.uuid }))).toBe('cannot_target_self')
  })

  it('blocking ends everything and hides the blocker', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bob')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    acceptRequest(env.ctx, b!, a!.uuid)
    const evB = listen(env, b!.uuid)
    block(env.ctx, a!.uuid, { name: 'Bob' })
    expect(listFriends(env.ctx, a!.uuid).friends).toEqual([])
    // Der Blockierte sieht nur „Freundschaft beendet“, keine Blockade.
    expect(evB).toEqual([{ type: 'friend_removed', uuid: a!.uuid }])
    expect(code(() => sendRequest(env.ctx, b!, { uuid: a!.uuid }))).toBe('player_not_found')
    expect(code(() => sendRequest(env.ctx, a!, { uuid: b!.uuid }))).toBe('blocked')
    expect(listBlocks(env.ctx, a!.uuid).blocked.map((x) => x.name)).toEqual(['Bob'])
  })

  it('enforces request and friend limits', async () => {
    const env = makeEnv({ limits: { maxOutgoingRequests: 2, maxIncomingRequests: 2, maxFriends: 1 } })
    const [a, b, c, d, e] = await players(env, 'A1', 'B1', 'C1', 'D1', 'E1')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    sendRequest(env.ctx, a!, { uuid: c!.uuid })
    expect(code(() => sendRequest(env.ctx, a!, { uuid: d!.uuid }))).toBe('too_many_requests')
    sendRequest(env.ctx, d!, { uuid: e!.uuid })
    sendRequest(env.ctx, b!, { uuid: e!.uuid })
    expect(code(() => sendRequest(env.ctx, c!, { uuid: e!.uuid }))).toBe('target_inbox_full')
    acceptRequest(env.ctx, b!, a!.uuid)
    expect(code(() => acceptRequest(env.ctx, c!, a!.uuid))).toBe('target_friend_limit')
    expect(code(() => sendRequest(env.ctx, b!, { uuid: d!.uuid }))).toBe('friend_limit')
  })

  it('banned and deleted users disappear from lists', async () => {
    const env = makeEnv()
    const [a, b, c] = await players(env, 'Alex', 'Bob', 'Cid')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    acceptRequest(env.ctx, b!, a!.uuid)
    sendRequest(env.ctx, c!, { uuid: a!.uuid })
    banUser(env.ctx, 'api-key', b!.uuid, undefined)
    expect(listFriends(env.ctx, a!.uuid).friends).toEqual([])
    deleteUser(env.ctx, c!.uuid)
    expect(listFriends(env.ctx, a!.uuid).requests.incoming).toEqual([])
  })
})

describe('presence privacy', () => {
  async function friends(env: TestEnv) {
    const [a, b] = await players(env, 'Alex', 'Bob')
    sendRequest(env.ctx, a!, { uuid: b!.uuid })
    acceptRequest(env.ctx, b!, a!.uuid)
    return [a!, b!] as const
  }

  it('friends see presence; the server only with shareServer', async () => {
    const env = makeEnv()
    const [a, b] = await friends(env)
    env.ctx.presence.set(b.uuid, 'in-game', { version: '1.21.1', loader: 'fabric', server: 'play.example.net' })
    expect(listFriends(env.ctx, a.uuid).friends[0]!.presence).toMatchObject({ state: 'in-game', game: { version: '1.21.1', loader: 'fabric' } })
    expect(listFriends(env.ctx, a.uuid).friends[0]!.presence!.game!.server).toBeUndefined()
    updateSettings(env.ctx, b.uuid, { shareServer: true })
    expect(listFriends(env.ctx, a.uuid).friends[0]!.presence!.game!.server).toBe('play.example.net')
    updateSettings(env.ctx, b.uuid, { shareServer: false })
    // Beim Abschalten wird der gespeicherte Server sofort verworfen.
    expect(env.ctx.presence.get(b.uuid)!.game!.server).toBeUndefined()
  })

  it('presenceVisibility=nobody hides the status completely', async () => {
    const env = makeEnv()
    const [a, b] = await friends(env)
    env.ctx.presence.set(b.uuid, 'online', null)
    updateSettings(env.ctx, b.uuid, { presenceVisibility: 'nobody' })
    expect(listFriends(env.ctx, a.uuid).friends[0]!.presence).toBeNull()
    const ev = listen(env, a.uuid)
    broadcastPresence(env.ctx, b.uuid)
    expect(ev).toEqual([{ type: 'presence', uuid: b.uuid, presence: null }])
  })

  it('presence expires after 3 minutes without heartbeat', async () => {
    const env = makeEnv()
    const [a, b] = await friends(env)
    env.ctx.presence.set(b.uuid, 'online', null)
    env.clock.advance(179_000)
    expect(listFriends(env.ctx, a.uuid).friends[0]!.presence).not.toBeNull()
    env.clock.advance(2000)
    expect(listFriends(env.ctx, a.uuid).friends[0]!.presence).toBeNull()
  })

  it('non-friends never receive presence events', async () => {
    const env = makeEnv()
    const [, b] = await friends(env)
    const [stranger] = await players(env, 'Stranger')
    const ev = listen(env, stranger!.uuid)
    env.ctx.presence.set(b.uuid, 'online', null)
    broadcastPresence(env.ctx, b.uuid)
    expect(ev).toEqual([])
  })

  it('event streams are capped per user', () => {
    const env = makeEnv()
    const subs = [1, 2, 3].map(() => env.ctx.events.subscribe('x'.repeat(32), () => {}, () => {}))
    expect(subs.every(Boolean)).toBe(true)
    expect(env.ctx.events.subscribe('x'.repeat(32), () => {}, () => {})).toBeNull()
    subs[0]!.close()
    expect(env.ctx.events.subscribe('x'.repeat(32), () => {}, () => {})).not.toBeNull()
  })
})
