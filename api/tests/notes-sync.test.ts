import { DatabaseSync } from 'node:sqlite'
import { describe, expect, it } from 'vitest'
import { sweepExpired } from '../server/lib/auth'
import { one, run } from '../server/lib/db'
import type { ApiEvent } from '../server/lib/events'
import { migrateSyncNotes } from '../server/lib/migrations'
import {
  MAX_NOTES_PER_ACCOUNT,
  MAX_NOTES_PER_WORLD,
  NOTE_TOMBSTONE_TTL_MS,
  listNotes,
  pushNotes,
  type NoteEntry,
} from '../server/lib/notes-sync'
import { deleteUser } from '../server/lib/users'
import getRoute from '../server/routes/v1/me/sync/notes.get'
import postRoute from '../server/routes/v1/me/sync/notes.post'
import { players } from './chathelpers'
import { callRoute } from './circuithelpers'
import { login, makeEnv, type TestEnv } from './helpers'

const HOUR = 60 * 60 * 1000
const SERVER = { type: 'server', address: 'play.example.net' } as const
const WORLD = { type: 'world', id: 'abcdef0123456789', name: 'Meine Welt' } as const

let n = 0
const nid = () => (++n).toString(16).padStart(16, '0')

function note(env: TestEnv, over: Record<string, unknown> = {}) {
  const t = new Date(env.clock.t).toISOString()
  return { id: nid(), world: SERVER, title: 'Base', text: 'Coords 100 64 -20\nNether hub', createdAt: t, updatedAt: t, ...over }
}

const all = (env: TestEnv, uuid: string, since?: string) => listNotes(env.ctx, uuid, { limit: 500, ...(since ? { since } : {}) })

describe('notes sync (§17.5)', () => {
  it('stores notes, lists them with a cursor, then only changes; tombstones included', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const n1 = note(env)
    const n2 = note(env, { world: WORLD, title: '' })
    expect(pushNotes(env.ctx, a!.uuid, [n1, n2]).results).toEqual([{ id: n1.id, status: 'ok' }, { id: n2.id, status: 'ok' }])
    const first = all(env, a!.uuid)
    expect(first).toMatchObject({ more: false, reset: false, cursor: 'n1.2' })
    expect(first.notes).toEqual([
      { id: n1.id, world: SERVER, title: 'Base', text: n1.text, createdAt: n1.createdAt, updatedAt: n1.updatedAt },
      { id: n2.id, world: WORLD, title: '', text: n2.text, createdAt: n2.createdAt, updatedAt: n2.updatedAt },
    ])
    expect(all(env, a!.uuid, first.cursor).notes).toEqual([])

    env.clock.advance(1000)
    const t = new Date(env.clock.t).toISOString()
    pushNotes(env.ctx, a!.uuid, [{ id: n1.id, world: SERVER, deleted: true, updatedAt: t }])
    const next = all(env, a!.uuid, first.cursor)
    expect(next.notes).toEqual([{ id: n1.id, world: SERVER, deleted: true, updatedAt: t }])
    expect(next.cursor).toBe('n1.3')
    // Ohne Cursor: alles inkl. Grabstein.
    expect(all(env, a!.uuid).notes.map((x) => ('deleted' in x ? 'dead' : 'live'))).toEqual(['live', 'dead'])
  })

  it('pages with limit and more', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    pushNotes(env.ctx, a!.uuid, Array.from({ length: 5 }, () => note(env)))
    const p1 = listNotes(env.ctx, a!.uuid, { limit: 2 })
    expect(p1).toMatchObject({ more: true, cursor: 'n1.2' })
    const p2 = listNotes(env.ctx, a!.uuid, { limit: 2, since: p1.cursor })
    const p3 = listNotes(env.ctx, a!.uuid, { limit: 2, since: p2.cursor })
    expect(p3).toMatchObject({ more: false, cursor: 'n1.5' })
    expect([...p1.notes, ...p2.notes, ...p3.notes]).toHaveLength(5)
  })

  it('last writer wins: newer stored → stale with current, equal overwrites, older delete is stale', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const n1 = note(env)
    pushNotes(env.ctx, a!.uuid, [n1])
    const older = new Date(env.clock.t - 1000).toISOString()
    const r = pushNotes(env.ctx, a!.uuid, [{ ...n1, text: 'old', updatedAt: older }, { id: n1.id, world: SERVER, deleted: true, updatedAt: older }]).results
    expect(r[0]).toEqual({ id: n1.id, status: 'stale', current: expect.objectContaining({ text: n1.text }) })
    expect(r[1]!.status).toBe('stale')
    expect(pushNotes(env.ctx, a!.uuid, [{ ...n1, text: 'same time' }]).results[0]!.status).toBe('ok')
    expect((all(env, a!.uuid).notes[0] as { text: string }).text).toBe('same time')
    // Tombstone ist neuer als ein späteres altes Update → stale mit Grabstein.
    env.clock.advance(1000)
    const del = new Date(env.clock.t).toISOString()
    pushNotes(env.ctx, a!.uuid, [{ id: n1.id, world: SERVER, deleted: true, updatedAt: del }])
    const res = pushNotes(env.ctx, a!.uuid, [{ ...n1 }]).results[0] as { status: string, current: NoteEntry }
    expect(res).toEqual({ id: n1.id, status: 'stale', current: { id: n1.id, world: SERVER, deleted: true, updatedAt: del } })
  })

  it('rejects bad entries one by one (invalid) and keeps the order', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const future = new Date(env.clock.t + 25 * HOUR).toISOString()
    const bad = [
      note(env, { updatedAt: future }),
      note(env, { title: 'x'.repeat(65) }),
      note(env, { title: 'a\nb' }),
      note(env, { text: 'tab\there' }),
      note(env, { text: 'crlf\r\n' }),
      note(env, { title: `ls${String.fromCharCode(0x2028)}` }),
      note(env, { text: `c1${String.fromCharCode(0x85)}` }),
      note(env, { text: '😀'.repeat(20_001) }),
      note(env, { world: { type: 'server', address: 'evil host' } }),
      note(env, { world: { type: 'server', address: 'a/b' } }),
      note(env, { world: { type: 'server', address: 'x'.repeat(256) } }),
      note(env, { world: { type: 'world', id: 'xyz' } }),
      note(env, { world: { type: 'world', id: 'abcdef0123456789', name: 'n'.repeat(65) } }),
      note(env, { extra: 1 }),
      { id: nid(), world: SERVER, deleted: true, updatedAt: future },
      { id: nid(), world: SERVER, deleted: true, title: 'x', updatedAt: new Date(env.clock.t).toISOString() },
    ]
    const ok = note(env, { text: '😀'.repeat(20_000), world: { type: 'world', id: 'ABCDEF0123456789' } })
    const r = pushNotes(env.ctx, a!.uuid, [...bad, ok]).results
    expect(r.map((x) => x.status)).toEqual([...bad.map(() => 'invalid'), 'ok'])
    expect(r.map((x) => x.id)).toEqual([...bad, ok].map((x) => x.id))
    expect(all(env, a!.uuid).notes[0]!.world).toEqual({ type: 'world', id: 'abcdef0123456789' })
  })

  it('enforces 200 notes per world and 2000 per account; tombstones and updates do not count', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const batch = (k: number, world: unknown) => Array.from({ length: k }, () => note(env, { world }))
    for (let i = 0; i < MAX_NOTES_PER_WORLD / 50; i++) pushNotes(env.ctx, a!.uuid, batch(50, SERVER))
    const extra = note(env)
    expect(pushNotes(env.ctx, a!.uuid, [extra]).results[0]!.status).toBe('note_limit')
    // Update einer bestehenden Notiz geht weiter.
    const existing = all(env, a!.uuid).notes[0]!
    expect(pushNotes(env.ctx, a!.uuid, [{ ...(existing as object), text: 'changed' }]).results[0]!.status).toBe('ok')
    // Löschen macht Platz.
    pushNotes(env.ctx, a!.uuid, [{ id: existing.id, world: SERVER, deleted: true, updatedAt: new Date(env.clock.t + 1).toISOString() }])
    expect(pushNotes(env.ctx, a!.uuid, [extra]).results[0]!.status).toBe('ok')

    // Kontogrenze: 2000 über viele Welten.
    let w = 0
    while (one<{ n: number }>(env.ctx.db, 'SELECT COUNT(*) AS n FROM sync_notes WHERE uuid = ? AND deleted = 0', a!.uuid)!.n < MAX_NOTES_PER_ACCOUNT) {
      pushNotes(env.ctx, a!.uuid, batch(50, { type: 'server', address: `s${++w}.example.net` }))
    }
    expect(pushNotes(env.ctx, a!.uuid, [note(env, { world: { type: 'server', address: 'new.example.net' } })]).results[0]!.status).toBe('note_limit')
  })

  it('sweeps tombstones after 90 days; an older cursor gets the full list with reset', async () => {
    const env = makeEnv()
    const [a] = await players(env, 'Alex')
    const n1 = note(env)
    const n2 = note(env)
    pushNotes(env.ctx, a!.uuid, [n1, n2])
    const cursor = all(env, a!.uuid).cursor
    env.clock.advance(1000)
    pushNotes(env.ctx, a!.uuid, [{ id: n1.id, world: SERVER, deleted: true, updatedAt: new Date(env.clock.t).toISOString() }])
    env.clock.advance(NOTE_TOMBSTONE_TTL_MS - 2000)
    sweepExpired(env.ctx)
    expect(all(env, a!.uuid, cursor).notes).toHaveLength(1)
    env.clock.advance(2000)
    sweepExpired(env.ctx)
    const page = all(env, a!.uuid, cursor)
    expect(page.reset).toBe(true)
    expect(page.notes.map((x) => x.id)).toEqual([n2.id])
    // Cursor aus der Zukunft (fremd) → ebenfalls komplett.
    expect(all(env, a!.uuid, 'n1.999').reset).toBe(true)
  })

  it('owner only, account deletion removes everything; migration part is idempotent', async () => {
    const env = makeEnv()
    const [a, b] = await players(env, 'Alex', 'Bea')
    pushNotes(env.ctx, a!.uuid, [note(env)])
    expect(all(env, b!.uuid).notes).toEqual([])
    deleteUser(env.ctx, a!.uuid)
    expect(one<{ n: number }>(env.ctx.db, 'SELECT COUNT(*) AS n FROM sync_notes WHERE uuid = ?', a!.uuid)!.n).toBe(0)
    expect(one(env.ctx.db, 'SELECT 1 AS x FROM sync_note_state WHERE uuid = ?', a!.uuid)).toBeUndefined()

    const db = new DatabaseSync(':memory:')
    db.exec('CREATE TABLE users (uuid TEXT PRIMARY KEY)')
    migrateSyncNotes(db)
    migrateSyncNotes(db)
    run(db, "INSERT INTO users (uuid) VALUES ('u')")
  })

  it('routes: validation, body envelope, event to own devices', async () => {
    const env = makeEnv()
    const alex = await login(env, 'Alex')
    const got: ApiEvent[] = []
    env.ctx.events.subscribe(alex.user.uuid, (e) => got.push(e), () => {}, 'me')
    const headers = { authorization: `Bearer ${alex.token}`, 'content-type': 'application/json' }
    const post = (body: unknown) => callRoute(env, postRoute, { method: 'POST', url: '/v1/me/sync/notes', headers, body: JSON.stringify(body) })
    const get = (qs: string) => callRoute(env, getRoute, { url: `/v1/me/sync/notes${qs}`, headers })

    expect((await post({ changes: [] })).error?.code).toBe('invalid_request')
    expect((await post({ changes: Array.from({ length: 51 }, () => note(env)) })).error?.code).toBe('invalid_request')
    expect((await post({ changes: [{ id: 'nope' }] })).error?.code).toBe('invalid_request')
    expect((await post({ changes: [note(env)], x: 1 })).error?.code).toBe('invalid_request')
    const n1 = note(env)
    expect((await post({ changes: [n1, { id: nid(), world: SERVER }] })).body).toEqual({
      results: [{ id: n1.id, status: 'ok' }, { id: expect.any(String), status: 'invalid' }],
    })
    expect(got.filter((e) => e.type === 'notes_changed')).toEqual([{ type: 'notes_changed', cursor: 'n1.1' }])

    expect((await get('?limit=501')).error?.code).toBe('invalid_request')
    expect((await get('?since=garbage')).error?.code).toBe('invalid_request')
    expect((await get('?limit=10')).body).toMatchObject({ cursor: 'n1.1', more: false, notes: [{ id: n1.id }] })
    expect((await callRoute(env, getRoute, { url: '/v1/me/sync/notes' })).error?.code).toBe('unauthorized')
  })
})
