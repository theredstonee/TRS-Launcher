import { describe, expect, it } from 'vitest'
import { approveCape, rejectCape } from '../server/lib/admin'
import {
  acceptOffer,
  declineOffer,
  listHolders,
  listOffers,
  offerCape,
  revokeShare,
} from '../server/lib/capeshares'
import { catalog, deleteOwnUpload, getCape, readTexture, setActiveCape, uploadCape } from '../server/lib/capes'
import type { ApiEvent } from '../server/lib/events'
import { acceptRequest, block, listFriends, removeFriend, sendRequest } from '../server/lib/friends'
import { lookupPlayers } from '../server/lib/lookup'
import { deleteUser, getUser, type UserRow } from '../server/lib/users'
import { ADMIN, login, makeEnv, seedFixtures, solidPng, type TestEnv } from './helpers'

function code(fn: () => unknown): string {
  try {
    fn()
  } catch (e) {
    return (e as { code?: string }).code ?? (e as Error).message
  }
  return 'ok'
}

async function players(env: TestEnv, ...names: string[]): Promise<UserRow[]> {
  const out: UserRow[] = []
  for (const n of names) out.push(getUser(env.ctx, (await login(env, n)).user.uuid)!)
  return out
}

function befriend(env: TestEnv, a: UserRow, b: UserRow): void {
  sendRequest(env.ctx, a, { uuid: b.uuid })
  acceptRequest(env.ctx, b, a.uuid)
}

function listen(env: TestEnv, uuid: string): ApiEvent[] {
  const got: ApiEvent[] = []
  env.ctx.events.subscribe(uuid, (e) => got.push(e), () => {})
  return got
}

let colour = 0
/** Freigegebener eigener Upload (jede Farbe anders → keine Duplikate). */
function approvedCape(env: TestEnv, owner: UserRow, name = 'Geteilt'): string {
  colour++
  const cape = uploadCape(env.ctx, owner.uuid, solidPng(64, 32, [colour % 256, 40, 90, 255]), name)
  approveCape(env.ctx, ADMIN, cape.id)
  return cape.id
}

/** Alex (Ersteller) – Bob (Freund) – Cleo (Freundin von Bob, nicht von Alex). */
async function world(opts: Parameters<typeof makeEnv>[0] = {}) {
  const env = makeEnv(opts)
  seedFixtures(env)
  const [alex, bob, cleo, dora] = await players(env, 'Alex', 'Bob', 'Cleo', 'Dora')
  befriend(env, alex!, bob!)
  befriend(env, bob!, cleo!)
  const capeId = approvedCape(env, alex!)
  return { env, alex: alex!, bob: bob!, cleo: cleo!, dora: dora!, capeId }
}

describe('offering capes', () => {
  it('only own approved uploads can be offered, and only to friends', async () => {
    const { env, alex, bob, cleo, capeId } = await world()
    const pending = uploadCape(env.ctx, alex.uuid, solidPng(64, 32, [1, 2, 3, 255]), 'Wartet')
    expect(code(() => offerCape(env.ctx, alex, pending.id, bob.uuid))).toBe('cape_not_approved')
    expect(code(() => offerCape(env.ctx, alex, 'redstone', bob.uuid))).toBe('cape_not_shareable')
    expect(code(() => offerCape(env.ctx, alex, 'nope', bob.uuid))).toBe('cape_not_found')
    // Fremder Upload, den man nicht hält, sieht aus wie „gibt es nicht“.
    expect(code(() => offerCape(env.ctx, bob, capeId, cleo.uuid))).toBe('cape_not_found')
    expect(code(() => offerCape(env.ctx, alex, capeId, cleo.uuid))).toBe('friend_not_found')
    expect(code(() => offerCape(env.ctx, alex, capeId, alex.uuid))).toBe('cannot_target_self')
    expect(code(() => offerCape(env.ctx, alex, capeId, 'f'.repeat(32)))).toBe('friend_not_found')
  })

  it('offer → friend sees it (event, list, count) → accept → wearable and visible to others', async () => {
    const { env, alex, bob, cleo, capeId } = await world()
    const evBob = listen(env, bob.uuid)
    const evAlex = listen(env, alex.uuid)
    const out = offerCape(env.ctx, alex, capeId, bob.uuid)
    expect(out).toMatchObject({ cape: { id: capeId, status: 'approved' }, to: { uuid: bob.uuid, name: 'Bob' } })
    expect(evBob).toEqual([{
      type: 'cape_offer',
      offer: expect.objectContaining({ cape: expect.objectContaining({ id: capeId }), from: { uuid: alex.uuid, name: 'Alex' }, creator: { uuid: alex.uuid, name: 'Alex' } }),
    }])
    expect(listOffers(env.ctx, bob.uuid).incoming.map((o) => o.cape.id)).toEqual([capeId])
    expect(listOffers(env.ctx, alex.uuid).outgoing.map((o) => [o.cape.id, o.to.name])).toEqual([[capeId, 'Bob']])
    expect(listFriends(env.ctx, bob.uuid).capeOffers).toBe(1)
    expect(code(() => offerCape(env.ctx, alex, capeId, bob.uuid))).toBe('already_shared')
    // Vor dem Annehmen: nicht in der Sammlung, nicht tragbar.
    expect(catalog(env.ctx, bob.uuid).some((c) => c.id === capeId)).toBe(false)
    expect(code(() => setActiveCape(env.ctx, bob.uuid, capeId))).toBe('cape_not_found')

    expect(acceptOffer(env.ctx, bob, capeId)).toMatchObject({ id: capeId })
    expect(evAlex.at(-1)).toEqual({ type: 'cape_offer_accepted', capeId, by: { uuid: bob.uuid, name: 'Bob' } })
    expect(listFriends(env.ctx, bob.uuid).capeOffers).toBe(0)
    expect(listOffers(env.ctx, alex.uuid).outgoing).toEqual([])
    const entry = catalog(env.ctx, bob.uuid).find((c) => c.id === capeId)!
    expect(entry).toMatchObject({
      owned: true,
      active: false,
      shareable: true,
      holders: 0,
      rejectReason: null,
      shared: { from: { uuid: alex.uuid, name: 'Alex' }, creator: { uuid: alex.uuid, name: 'Alex' } },
    })
    // Geteilte stehen hinter Standard-Designs und eigenen Uploads.
    expect(catalog(env.ctx, bob.uuid).at(-1)!.id).toBe(capeId)
    const own = catalog(env.ctx, alex.uuid).find((c) => c.id === capeId)!
    expect(own).toMatchObject({ shareable: true, shared: null, holders: 1 })

    setActiveCape(env.ctx, bob.uuid, capeId)
    expect(lookupPlayers(env.ctx, cleo.uuid, [bob.uuid]).players[0]!.cape!.id).toBe(capeId)
    expect(readTexture(env.ctx, capeId, null).public).toBe(true)
    expect(code(() => acceptOffer(env.ctx, bob, capeId))).toBe('offer_not_found')
  })

  it('decline is silent; offers to banned granters and not-approved capes are hidden', async () => {
    const { env, alex, bob, capeId } = await world()
    const evAlex = listen(env, alex.uuid)
    offerCape(env.ctx, alex, capeId, bob.uuid)
    declineOffer(env.ctx, bob.uuid, capeId)
    expect(evAlex).toEqual([])
    expect(listOffers(env.ctx, alex.uuid).outgoing).toEqual([])
    expect(code(() => declineOffer(env.ctx, bob.uuid, capeId))).toBe('offer_not_found')
    // Nach dem Ablehnen darf erneut angeboten werden.
    expect(code(() => offerCape(env.ctx, alex, capeId, bob.uuid))).toBe('ok')
  })

  it('the holder limit counts accepted holders and open offers; the inbox is limited too', async () => {
    const { env, alex, bob, cleo, dora, capeId } = await world({ limits: { maxCapeHolders: 2, maxIncomingCapeOffers: 1 } })
    befriend(env, alex, cleo)
    befriend(env, alex, dora)
    offerCape(env.ctx, alex, capeId, bob.uuid)
    acceptOffer(env.ctx, bob, capeId)
    offerCape(env.ctx, alex, capeId, cleo.uuid)
    expect(code(() => offerCape(env.ctx, alex, capeId, dora.uuid))).toBe('share_limit')
    // Weiterteilen zählt gegen dasselbe Limit.
    expect(code(() => offerCape(env.ctx, bob, capeId, dora.uuid))).toBe('friend_not_found')
    befriend(env, bob, dora)
    expect(code(() => offerCape(env.ctx, bob, capeId, dora.uuid))).toBe('share_limit')
    declineOffer(env.ctx, cleo.uuid, capeId)
    const second = approvedCape(env, alex, 'Zweiter')
    offerCape(env.ctx, alex, second, dora.uuid)
    // Dora hat schon ein offenes Angebot (Postfach 1).
    expect(code(() => offerCape(env.ctx, bob, capeId, dora.uuid))).toBe('offer_inbox_full')
    expect(listHolders(env.ctx, alex.uuid, capeId)).toMatchObject({ count: 1, limit: 2 })
  })
})

describe('re-sharing, holders and revoking', () => {
  async function chain() {
    const w = await world()
    const { env, alex, bob, cleo, capeId } = w
    offerCape(env.ctx, alex, capeId, bob.uuid)
    acceptOffer(env.ctx, bob, capeId)
    // Bob teilt an Cleo weiter (Cleo ist nicht mit Alex befreundet).
    offerCape(env.ctx, bob, capeId, cleo.uuid)
    acceptOffer(env.ctx, cleo, capeId)
    return w
  }

  it('re-shared capes carry the creator; holder lists show the whole tree to the creator and the own branch to holders', async () => {
    const { env, alex, bob, cleo, capeId } = await chain()
    expect(catalog(env.ctx, cleo.uuid).find((c) => c.id === capeId)!.shared).toEqual({
      from: { uuid: bob.uuid, name: 'Bob' },
      creator: { uuid: alex.uuid, name: 'Alex' },
    })
    const all = listHolders(env.ctx, alex.uuid, capeId)
    expect(all.holders.map((h) => [h.name, h.status, h.grantedBy.name])).toEqual([['Bob', 'accepted', 'Alex'], ['Cleo', 'accepted', 'Bob']])
    expect(all.count).toBe(2)
    expect(listHolders(env.ctx, bob.uuid, capeId).holders.map((h) => h.name)).toEqual(['Cleo'])
    expect(listHolders(env.ctx, cleo.uuid, capeId).holders).toEqual([])
    expect(catalog(env.ctx, bob.uuid).find((c) => c.id === capeId)!.holders).toBe(1)
    expect(code(() => listHolders(env.ctx, cleo.uuid, 'redstone'))).toBe('cape_not_found')
  })

  it('revoking a holder also revokes everything they re-shared; wearers fall back to no cape', async () => {
    const { env, alex, bob, cleo, capeId } = await chain()
    setActiveCape(env.ctx, cleo.uuid, capeId)
    const evBob = listen(env, bob.uuid)
    const evCleo = listen(env, cleo.uuid)
    const capeEvents: unknown[] = []
    env.ctx.watch.subscribe(alex.uuid, [cleo.uuid], (e) => capeEvents.push(e), () => {})
    // Cleo darf Bob nicht entziehen, ein Fremder sieht den Umhang gar nicht.
    expect(code(() => revokeShare(env.ctx, cleo.uuid, capeId, bob.uuid))).toBe('holder_not_found')
    revokeShare(env.ctx, alex.uuid, capeId, bob.uuid)
    expect(evBob).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(evCleo).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(capeEvents).toEqual([{ type: 'cape', uuid: cleo.uuid, cape: null }])
    expect(getUser(env.ctx, cleo.uuid)!.active_cape_id).toBeNull()
    expect(catalog(env.ctx, cleo.uuid).some((c) => c.id === capeId)).toBe(false)
    expect(listHolders(env.ctx, alex.uuid, capeId).holders).toEqual([])
    expect(code(() => revokeShare(env.ctx, alex.uuid, capeId, bob.uuid))).toBe('holder_not_found')
    expect(code(() => revokeShare(env.ctx, bob.uuid, capeId, cleo.uuid))).toBe('cape_not_found')
  })

  it('a holder can revoke their own branch, withdraw offers and give the cape back', async () => {
    const { env, alex, bob, cleo, dora, capeId } = await chain()
    revokeShare(env.ctx, bob.uuid, capeId, cleo.uuid)
    expect(listHolders(env.ctx, alex.uuid, capeId).holders.map((h) => h.name)).toEqual(['Bob'])
    befriend(env, bob, dora)
    offerCape(env.ctx, bob, capeId, dora.uuid)
    revokeShare(env.ctx, bob.uuid, capeId, dora.uuid)
    expect(listOffers(env.ctx, dora.uuid).incoming).toEqual([])
    // Zurückgeben: Bob entfernt sich selbst.
    revokeShare(env.ctx, bob.uuid, capeId, bob.uuid)
    expect(catalog(env.ctx, bob.uuid).some((c) => c.id === capeId)).toBe(false)
    expect(listHolders(env.ctx, alex.uuid, capeId).count).toBe(0)
  })

  it('unfriending or blocking cancels open offers between both; accepted capes stay', async () => {
    const { env, alex, bob, cleo, capeId } = await world()
    befriend(env, alex, cleo)
    offerCape(env.ctx, alex, capeId, bob.uuid)
    acceptOffer(env.ctx, bob, capeId)
    offerCape(env.ctx, alex, capeId, cleo.uuid)
    const evCleo = listen(env, cleo.uuid)
    removeFriend(env.ctx, alex.uuid, cleo.uuid)
    expect(evCleo).toContainEqual({ type: 'cape_share_removed', capeId })
    expect(listOffers(env.ctx, cleo.uuid).incoming).toEqual([])

    const second = approvedCape(env, alex, 'Zweiter')
    offerCape(env.ctx, alex, second, bob.uuid)
    block(env.ctx, bob.uuid, { uuid: alex.uuid })
    expect(listOffers(env.ctx, bob.uuid).incoming).toEqual([])
    // Der angenommene Umhang bleibt, bis jemand ihn entzieht.
    expect(catalog(env.ctx, bob.uuid).find((c) => c.id === capeId)!.owned).toBe(true)
  })

  it('an offer that became stale (friendship ended in between) cannot be accepted', async () => {
    const { env, alex, bob, capeId } = await world()
    offerCape(env.ctx, alex, capeId, bob.uuid)
    // Freundschaft direkt in der DB beenden (ohne den Aufräum-Pfad) → Annehmen prüft selbst.
    env.ctx.db.exec('DELETE FROM friendships')
    expect(code(() => acceptOffer(env.ctx, bob, capeId))).toBe('offer_not_found')
    expect(listOffers(env.ctx, bob.uuid).incoming).toEqual([])
  })
})

describe('cleanup', () => {
  async function shared() {
    const w = await world()
    const { env, alex, bob, cleo, capeId } = w
    offerCape(env.ctx, alex, capeId, bob.uuid)
    acceptOffer(env.ctx, bob, capeId)
    offerCape(env.ctx, bob, capeId, cleo.uuid)
    setActiveCape(env.ctx, bob.uuid, capeId)
    return w
  }

  it('deleting the cape removes every holding and offer', async () => {
    const { env, alex, bob, cleo, capeId } = await shared()
    const evBob = listen(env, bob.uuid)
    const evCleo = listen(env, cleo.uuid)
    deleteOwnUpload(env.ctx, alex.uuid, capeId)
    expect(evBob).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(evCleo).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(getUser(env.ctx, bob.uuid)!.active_cape_id).toBeNull()
    expect(env.ctx.db.prepare('SELECT COUNT(*) AS n FROM cape_shares').get()).toEqual({ n: 0 })
  })

  it('an admin rejection removes every holding and takes the cape off', async () => {
    const { env, bob, cleo, capeId } = await shared()
    const evCleo = listen(env, cleo.uuid)
    rejectCape(env.ctx, ADMIN, capeId, 'Urheberrecht')
    expect(evCleo).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(getUser(env.ctx, bob.uuid)!.active_cape_id).toBeNull()
    expect(catalog(env.ctx, bob.uuid).some((c) => c.id === capeId)).toBe(false)
    expect(getCape(env.ctx, capeId)!.status).toBe('rejected')
    expect(env.ctx.db.prepare('SELECT COUNT(*) AS n FROM cape_shares').get()).toEqual({ n: 0 })
  })

  it('deleting a holder account removes their branch; deleting the creator removes everything', async () => {
    const { env, alex, bob, cleo, capeId } = await shared()
    acceptOffer(env.ctx, cleo, capeId)
    setActiveCape(env.ctx, cleo.uuid, capeId)
    const evCleo = listen(env, cleo.uuid)
    deleteUser(env.ctx, bob.uuid)
    expect(evCleo).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(getUser(env.ctx, cleo.uuid)!.active_cape_id).toBeNull()
    expect(listHolders(env.ctx, alex.uuid, capeId).count).toBe(0)

    const [eve] = await players(env, 'Eve')
    befriend(env, alex, eve!)
    offerCape(env.ctx, alex, capeId, eve!.uuid)
    acceptOffer(env.ctx, eve!, capeId)
    setActiveCape(env.ctx, eve!.uuid, capeId)
    const evEve = listen(env, eve!.uuid)
    deleteUser(env.ctx, alex.uuid)
    expect(evEve).toEqual([{ type: 'cape_share_removed', capeId }])
    expect(getUser(env.ctx, eve!.uuid)!.active_cape_id).toBeNull()
    expect(env.ctx.db.prepare('SELECT COUNT(*) AS n FROM cape_shares').get()).toEqual({ n: 0 })
  })
})
