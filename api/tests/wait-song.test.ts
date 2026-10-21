import { randomBytes } from 'node:crypto'
import { mkdirSync, writeFileSync } from 'node:fs'
import { createServer, type Server } from 'node:http'
import { join } from 'node:path'
import { createApp, createRouter, send, setResponseHeaders, setResponseStatus, toNodeListener } from 'h3'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { WAIT_SONG_UUID, beatAt, parseWaitTimeline, sectionAt, type WaitTimeline } from '../shared/wait-song'
import { setContext } from '../server/lib/context'
import { isApiError } from '../server/lib/errors'
import { ownerStaff, setMemberRoles } from '../server/lib/team'
import { parseRange, readWaitTimeline, waitSongAccess } from '../server/lib/wait-song'
import { createWebSession } from '../server/lib/weblogin'
import songRoute from '../server/routes/v1/me/wait-song/index.get'
import timelineRoute from '../server/routes/v1/me/wait-song/timeline.get'
import { ADMIN, login, makeEnv, type TestEnv } from './helpers'

const TL = {
  intro: 0, verse1: 9.6, pre1: 38.4, chorus1: 48, verse2: 67.2, pre2: 96, chorus2: 105.6,
  bridge: 124.8, final: 144, outro: 163.2, end: 182,
}

// ---------------------------------------------------------------- shared/wait-song.ts

describe('wait-song: parseWaitTimeline', () => {
  it('accepts a valid timeline, intro defaults to 0, extra keys ignored', () => {
    expect(parseWaitTimeline(TL)).toEqual(TL)
    const { intro: _, ...noIntro } = TL
    expect(parseWaitTimeline(noIntro)?.intro).toBe(0)
    const extra = parseWaitTimeline({ ...TL, comment: 'x', foo: [1] }) as Record<string, unknown>
    expect(extra.comment).toBeUndefined()
    expect(extra.foo).toBeUndefined()
    expect(parseWaitTimeline({ ...TL, verse1: 9.61234 })?.verse1).toBe(9.612)
  })

  it('rejects non-objects, missing, non-increasing, negative and too long values', () => {
    for (const bad of [null, undefined, 1, 'x', [], [TL]]) expect(parseWaitTimeline(bad)).toBeNull()
    const { pre1: _, ...missing } = TL
    expect(parseWaitTimeline(missing)).toBeNull()
    expect(parseWaitTimeline({ ...TL, pre1: TL.verse1 })).toBeNull()
    expect(parseWaitTimeline({ ...TL, pre1: 5 })).toBeNull()
    expect(parseWaitTimeline({ ...TL, intro: -1 })).toBeNull()
    expect(parseWaitTimeline({ ...TL, end: 15 * 60 + 0.5 })).toBeNull()
    expect(parseWaitTimeline({ ...TL, end: 15 * 60 })?.end).toBe(900)
    expect(parseWaitTimeline({ ...TL, chorus1: '48' })).toBeNull()
    expect(parseWaitTimeline({ ...TL, chorus1: Number.NaN })).toBeNull()
    expect(parseWaitTimeline({ ...TL, end: Number.POSITIVE_INFINITY })).toBeNull()
  })

  it('validates beats', () => {
    expect(parseWaitTimeline({ ...TL, beats: { bpm: 100, offset: 0.42 } })?.beats).toEqual({ bpm: 100, offset: 0.42 })
    expect(parseWaitTimeline({ ...TL, beats: { bpm: 120 } })?.beats).toEqual({ bpm: 120, offset: 0 })
    expect(parseWaitTimeline({ ...TL, beats: { bpm: 40, offset: 60 } })?.beats).toEqual({ bpm: 40, offset: 60 })
    expect(parseWaitTimeline(TL)?.beats).toBeUndefined()
    for (const beats of [null, 5, 'x', {}, { bpm: 39 }, { bpm: 241 }, { bpm: '100' }, { bpm: 100, offset: -0.1 }, { bpm: 100, offset: 61 }, { bpm: 100, offset: 'a' }]) {
      expect(parseWaitTimeline({ ...TL, beats })).toBeNull()
    }
  })
})

describe('wait-song: sectionAt / beatAt', () => {
  const tl = parseWaitTimeline(TL)!

  it('picks the section at each boundary', () => {
    const keys = ['intro', 'verse1', 'pre1', 'chorus1', 'verse2', 'pre2', 'chorus2', 'bridge', 'final', 'outro'] as const
    keys.forEach((key, index) => {
      const s = sectionAt(tl, tl[key])
      expect(s.key).toBe(key)
      expect(s.index).toBe(index)
      expect(s.t).toBe(0)
      expect(s.p).toBe(0)
      if (index > 0) expect(sectionAt(tl, tl[key] - 0.001).key).toBe(keys[index - 1])
    })
    const mid = sectionAt(tl, 58.8)
    expect(mid).toMatchObject({ key: 'chorus1', start: 48, end: 67.2 })
    expect(mid.p).toBeCloseTo(0.5625, 6)
    expect(sectionAt(tl, 170)).toMatchObject({ key: 'outro', start: 163.2, end: 182 })
  })

  it('before 0 → intro, after end → outro with p = 1', () => {
    expect(sectionAt(tl, -5)).toMatchObject({ key: 'intro', index: 0, t: 0, p: 0 })
    expect(sectionAt(tl, 182)).toMatchObject({ key: 'outro', p: 1 })
    expect(sectionAt(tl, 500)).toMatchObject({ key: 'outro', index: 9, p: 1 })
  })

  it('beatAt with and without beats', () => {
    // Ohne beats: 100 BPM ab Strophe 1.
    const a = beatAt(tl, tl.verse1 + 0.9)
    expect(a.bpm).toBe(100)
    expect(a.count).toBeCloseTo(1.5, 9)
    expect(a.index).toBe(1)
    expect(a.phase).toBeCloseTo(0.5, 9)
    const before = beatAt(tl, 0)
    expect(before.index).toBe(-16)
    expect(before.phase).toBeGreaterThanOrEqual(0)
    expect(before.phase).toBeLessThan(1)
    const withBeats: WaitTimeline = { ...tl, beats: { bpm: 120, offset: 0.5 } }
    const b = beatAt(withBeats, 2)
    expect(b).toMatchObject({ bpm: 120, index: 3 })
    expect(b.count).toBeCloseTo(3, 9)
    expect(b.phase).toBeCloseTo(0, 9)
  })
})

// ---------------------------------------------------------------- server/lib/wait-song.ts

describe('wait-song: parseRange', () => {
  it('parses single ranges', () => {
    expect(parseRange(undefined, 1000)).toBeNull()
    expect(parseRange('', 1000)).toBeNull()
    expect(parseRange('bytes=0-99', 1000)).toEqual({ start: 0, end: 99 })
    expect(parseRange('Bytes=100-', 1000)).toEqual({ start: 100, end: 999 })
    expect(parseRange('bytes=-50', 1000)).toEqual({ start: 950, end: 999 })
    expect(parseRange('bytes=-5000', 1000)).toEqual({ start: 0, end: 999 })
    expect(parseRange('bytes=900-5000', 1000)).toEqual({ start: 900, end: 999 })
    expect(parseRange('bytes=999-999', 1000)).toEqual({ start: 999, end: 999 })
  })

  it('ignores multi-range and junk, flags unsatisfiable', () => {
    expect(parseRange('bytes=0-1,5-6', 1000)).toBeNull()
    expect(parseRange('items=0-1', 1000)).toBeNull()
    expect(parseRange('bytes=abc', 1000)).toBeNull()
    expect(parseRange('bytes=-', 1000)).toBeNull()
    expect(parseRange('bytes=5-2', 1000)).toBeNull()
    expect(parseRange('bytes=1000-', 1000)).toBe('unsatisfiable')
    expect(parseRange('bytes=2000-3000', 1000)).toBe('unsatisfiable')
    expect(parseRange('bytes=-0', 1000)).toBe('unsatisfiable')
  })
})

describe('wait-song: access + timeline file', () => {
  it('creator (with or without dashes, any case), owner and admin role – nobody else', async () => {
    const env = makeEnv()
    expect(waitSongAccess(env.ctx, WAIT_SONG_UUID)).toBe(true)
    const dashed = `${WAIT_SONG_UUID.slice(0, 8)}-${WAIT_SONG_UUID.slice(8, 12)}-${WAIT_SONG_UUID.slice(12, 16)}-${WAIT_SONG_UUID.slice(16, 20)}-${WAIT_SONG_UUID.slice(20)}`
    expect(waitSongAccess(env.ctx, dashed.toUpperCase())).toBe(true)
    expect(waitSongAccess(env.ctx, ADMIN)).toBe(true)
    const adm = await login(env, 'Adm')
    const mod = await login(env, 'Moddy')
    const plain = await login(env, 'Plain')
    setMemberRoles(env.ctx, ownerStaff(ADMIN), adm.user.uuid, ['admin'])
    setMemberRoles(env.ctx, ownerStaff(ADMIN), mod.user.uuid, ['moderator'])
    expect(waitSongAccess(env.ctx, adm.user.uuid)).toBe(true)
    expect(waitSongAccess(env.ctx, mod.user.uuid)).toBe(false)
    expect(waitSongAccess(env.ctx, plain.user.uuid)).toBe(false)
  })

  it('readWaitTimeline: missing → null, invalid → null + one warning, too large → null', () => {
    const env = makeEnv()
    expect(readWaitTimeline(env.ctx)).toBeNull()
    const dir = songDir(env)
    expect(readWaitTimeline(env.ctx)).toBeNull()
    writeFileSync(join(dir, 'timeline.json'), JSON.stringify({ ...TL, beats: { bpm: 100, offset: 0.4 } }))
    expect(readWaitTimeline(env.ctx)).toEqual({ ...TL, beats: { bpm: 100, offset: 0.4 } })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      writeFileSync(join(dir, 'timeline.json'), '{ nope')
      expect(readWaitTimeline(env.ctx)).toBeNull()
      expect(readWaitTimeline(env.ctx)).toBeNull()
      expect(warn).toHaveBeenCalledTimes(1)
      writeFileSync(join(dir, 'timeline.json'), JSON.stringify({ ...TL, pre1: 1 }))
      expect(readWaitTimeline(env.ctx)).toBeNull()
      writeFileSync(join(dir, 'timeline.json'), JSON.stringify({ ...TL, pad: 'x'.repeat(17 * 1024) }))
      expect(readWaitTimeline(env.ctx)).toBeNull()
    } finally {
      warn.mockRestore()
    }
  })
})

// ---------------------------------------------------------------- Routen über echten HTTP-Server

/** `<DATA_DIR>/private-assets/wait-song` (privateAssetsDir im Test). */
function songDir(env: TestEnv): string {
  const dir = join(env.ctx.config.privateAssetsDir, 'wait-song')
  mkdirSync(dir, { recursive: true })
  return dir
}

const open: { close: () => Promise<void> }[] = []
afterEach(async () => {
  for (const s of open.splice(0)) await s.close()
})

async function serve(env: TestEnv): Promise<string> {
  const app = createApp({
    onError: async (error, event) => {
      const api = isApiError(error) ? error : isApiError((error as { cause?: unknown }).cause) ? (error as { cause: never }).cause : null
      if (api?.headers) setResponseHeaders(event, api.headers)
      setResponseStatus(event, (api as { status?: number } | null)?.status ?? 500)
      await send(event, JSON.stringify({ error: { code: (api as { code?: string } | null)?.code ?? 'internal_error' } }), 'application/json')
    },
  })
  const router = createRouter()
  router.get('/v1/me/wait-song', songRoute)
  router.get('/v1/me/wait-song/timeline', timelineRoute)
  app.use(router)
  const server: Server = createServer(toNodeListener(app))
  await new Promise<void>((r) => server.listen(0, '127.0.0.1', r))
  open.push({ close: () => new Promise<void>((r) => server.close(() => r())) })
  setContext(env.ctx)
  return `http://127.0.0.1:${(server.address() as { port: number }).port}`
}

const errCode = async (res: Response) => ((await res.json()) as { error: { code: string } }).error.code

describe('wait-song: routes', () => {
  it('auth: 401 anonymous, 404 for others, 404 while the file is missing', async () => {
    const env = makeEnv()
    const base = await serve(env)
    const other = await login(env, 'Other')
    const creator = await login(env, 'Creator', WAIT_SONG_UUID)
    const otherWeb = createWebSession(env.ctx, other.user.uuid)

    expect((await fetch(`${base}/v1/me/wait-song`)).status).toBe(401)
    expect((await fetch(`${base}/v1/me/wait-song/timeline`)).status).toBe(401)

    // Fremde sehen 404 – auch wenn die Datei da ist.
    songDir(env)
    writeFileSync(join(songDir(env), 'song.mp3'), randomBytes(64))
    for (const headers of [{ authorization: `Bearer ${other.token}` }, { cookie: `trs_session=${otherWeb.token}` }]) {
      const a = await fetch(`${base}/v1/me/wait-song`, { headers })
      expect(a.status).toBe(404)
      expect(await errCode(a)).toBe('not_found')
      expect((await fetch(`${base}/v1/me/wait-song/timeline`, { headers })).status).toBe(404)
    }

    // Fehlende Datei: 404 auch für den Creator; Zeitraster sagt audio: false.
    const env2 = makeEnv()
    const base2 = await serve(env2)
    const c2 = await login(env2, 'Creator', WAIT_SONG_UUID)
    const missing = await fetch(`${base2}/v1/me/wait-song`, { headers: { authorization: `Bearer ${c2.token}` } })
    expect(missing.status).toBe(404)
    expect(await errCode(missing)).toBe('not_found')
    const tl = await fetch(`${base2}/v1/me/wait-song/timeline`, { headers: { authorization: `Bearer ${c2.token}` } })
    expect(tl.status).toBe(200)
    expect(tl.headers.get('cache-control')).toBe('private, no-store')
    expect(await tl.json()).toEqual({ audio: false, timeline: null })
    expect(creator.user.uuid).toBe(WAIT_SONG_UUID)
  })

  it('streams the MP3 via cookie and Bearer, owner allowed; ranges, 416, ETag/304', async () => {
    const env = makeEnv()
    const base = await serve(env)
    const song = randomBytes(5000)
    writeFileSync(join(songDir(env), 'song.mp3'), song)
    const creator = await login(env, 'Creator', WAIT_SONG_UUID)
    const owner = await login(env, 'Theredstonee', ADMIN)
    const web = createWebSession(env.ctx, WAIT_SONG_UUID)
    const ownerWeb = createWebSession(env.ctx, ADMIN)

    for (const headers of [
      { cookie: `trs_session=${web.token}` },
      { authorization: `Bearer ${creator.token}` },
      { authorization: `Bearer ${owner.token}` },
      { cookie: `trs_session=${ownerWeb.token}` },
    ]) {
      const res = await fetch(`${base}/v1/me/wait-song`, { headers })
      expect(res.status).toBe(200)
      expect(res.headers.get('content-type')).toBe('audio/mpeg')
      expect(res.headers.get('accept-ranges')).toBe('bytes')
      expect(res.headers.get('content-length')).toBe('5000')
      expect(res.headers.get('cache-control')).toBe('private, no-cache')
      expect(res.headers.get('content-disposition')).toMatch(/^inline/)
      expect(res.headers.get('x-content-type-options')).toBe('nosniff')
      expect(res.headers.get('last-modified')).toBeTruthy()
      expect(res.headers.get('etag')).toMatch(/^"ws-[0-9a-f]+-[0-9a-f]+"$/)
      expect(Buffer.from(await res.arrayBuffer()).equals(song)).toBe(true)
    }

    const auth = { authorization: `Bearer ${creator.token}` }
    const range = async (r: string, extra: Record<string, string> = {}) => fetch(`${base}/v1/me/wait-song`, { headers: { ...auth, range: r, ...extra } })

    const first = await range('bytes=0-99')
    expect(first.status).toBe(206)
    expect(first.headers.get('content-range')).toBe('bytes 0-99/5000')
    expect(first.headers.get('content-length')).toBe('100')
    expect(first.headers.get('content-type')).toBe('audio/mpeg')
    expect(Buffer.from(await first.arrayBuffer()).equals(song.subarray(0, 100))).toBe(true)

    const tail = await range('bytes=100-')
    expect(tail.status).toBe(206)
    expect(tail.headers.get('content-range')).toBe('bytes 100-4999/5000')
    expect(tail.headers.get('content-length')).toBe('4900')
    expect(Buffer.from(await tail.arrayBuffer()).equals(song.subarray(100))).toBe(true)

    const suffix = await range('bytes=-50')
    expect(suffix.status).toBe(206)
    expect(suffix.headers.get('content-range')).toBe('bytes 4950-4999/5000')
    expect(Buffer.from(await suffix.arrayBuffer()).equals(song.subarray(4950))).toBe(true)

    const unsat = await range('bytes=5000-')
    expect(unsat.status).toBe(416)
    expect(unsat.headers.get('content-range')).toBe('bytes */5000')
    expect(await errCode(unsat)).toBe('range_not_satisfiable')

    // Mehrere Bereiche → ganze Datei.
    const multi = await range('bytes=0-1,4-5')
    expect(multi.status).toBe(200)
    expect((await multi.arrayBuffer()).byteLength).toBe(5000)

    // ETag: passendes If-None-Match → 304; If-Range mit altem Stand → ganze Datei.
    const etag = first.headers.get('etag')!
    const cached = await fetch(`${base}/v1/me/wait-song`, { headers: { ...auth, 'if-none-match': etag } })
    expect(cached.status).toBe(304)
    expect((await cached.arrayBuffer()).byteLength).toBe(0)
    expect((await fetch(`${base}/v1/me/wait-song`, { headers: { ...auth, 'if-none-match': `W/${etag}` } })).status).toBe(304)
    const stale = await fetch(`${base}/v1/me/wait-song`, { headers: { ...auth, 'if-none-match': '"old"' } })
    expect(stale.status).toBe(200)
    await stale.arrayBuffer()
    const ifRangeOk = await range('bytes=0-9', { 'if-range': etag })
    expect(ifRangeOk.status).toBe(206)
    await ifRangeOk.arrayBuffer()
    const ifRangeOld = await range('bytes=0-9', { 'if-range': '"old"' })
    expect(ifRangeOld.status).toBe(200)
    expect((await ifRangeOld.arrayBuffer()).byteLength).toBe(5000)
  })

  it('timeline route: valid, invalid → null, missing → null, audio flag', async () => {
    const env = makeEnv()
    const base = await serve(env)
    const creator = await login(env, 'Creator', WAIT_SONG_UUID)
    const get = async () => {
      const res = await fetch(`${base}/v1/me/wait-song/timeline`, { headers: { authorization: `Bearer ${creator.token}` } })
      expect(res.status).toBe(200)
      expect(res.headers.get('cache-control')).toBe('private, no-store')
      return res.json() as Promise<{ audio: boolean, timeline: WaitTimeline | null }>
    }
    const dir = songDir(env)
    expect(await get()).toEqual({ audio: false, timeline: null })
    writeFileSync(join(dir, 'timeline.json'), JSON.stringify({ ...TL, beats: { bpm: 100, offset: 0.42 }, note: 'x' }))
    expect(await get()).toEqual({ audio: false, timeline: { ...TL, beats: { bpm: 100, offset: 0.42 } } })
    writeFileSync(join(dir, 'song.mp3'), randomBytes(10))
    expect((await get()).audio).toBe(true)
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    try {
      writeFileSync(join(dir, 'timeline.json'), 'not json')
      expect(await get()).toEqual({ audio: true, timeline: null })
    } finally {
      warn.mockRestore()
    }
  })
})
