import { beforeAll, describe, expect, it, vi } from 'vitest'
import { chatMessageSchema, messagePageSchema, messageSummary } from '../app/utils/chat'
import { adminReportListSchema, reportActions } from '../app/utils/moderation'
import { setLocale } from '../app/utils/i18n'
import { daysLeft, ShareQueue, shareBlockedBy, shareGate, sharedImageSchema, sharesPageSchema, type SharedImage } from '../app/utils/share'
import { chatWaypointSchema, dimensionLabel, waypointColor, waypointCoords, waypointCoordsLabel, waypointPlace } from '../app/utils/waypoint'
import samples from './fixtures/chat-live.json'

beforeAll(async () => {
  await setLocale('de')
})

const WP = {
  name: 'Basis',
  x: 100,
  y: 64,
  z: -20,
  dimension: 'minecraft:overworld',
  world: { type: 'server', address: 'play.example.net:25566' },
  color: 0xe0281e,
} as const

describe('Wegpunkt-Karte', () => {
  it('Schema prüft Grenzen, Dimension und Welt', () => {
    expect(chatWaypointSchema.parse(WP).color).toBe(0xe0281e)
    expect(chatWaypointSchema.parse({ ...WP, color: undefined }).color).toBeNull()
    const bad = [
      { ...WP, x: 30_000_001 },
      { ...WP, y: 4097 },
      { ...WP, z: 1.5 },
      { ...WP, name: '' },
      { ...WP, name: 'x'.repeat(33) },
      { ...WP, dimension: 'Minecraft:Overworld' },
      { ...WP, dimension: 'overworld' },
      { ...WP, world: { type: 'world', id: 'Mein Weltname' } },
      { ...WP, world: { type: 'moon' } },
    ]
    for (const b of bad) expect(chatWaypointSchema.safeParse(b).success, JSON.stringify(b)).toBe(false)
    expect(chatWaypointSchema.safeParse({ ...WP, world: { type: 'world', id: '0123456789abcdef' } }).success).toBe(true)
  })

  it('formatiert Koordinaten, Dimension, Ort und Farbe', () => {
    const w = chatWaypointSchema.parse(WP)
    expect(waypointCoords(w)).toBe('100 64 -20')
    expect(waypointCoordsLabel(w)).toBe('X 100 · Y 64 · Z -20')
    expect(dimensionLabel('minecraft:overworld')).toBe('Oberwelt')
    expect(dimensionLabel('minecraft:the_nether')).toBe('Nether')
    expect(dimensionLabel('minecraft:the_end')).toBe('End')
    expect(dimensionLabel('twilightforest:twilight_forest')).toBe('twilightforest:twilight_forest')
    expect(waypointPlace(w)).toBe('play.example.net:25566')
    expect(waypointPlace({ world: { type: 'world', id: '0123456789abcdef' } })).toBe('Einzelspielerwelt')
    expect(waypointColor(w)).toBe('#e0281e')
    expect(waypointColor({ color: 0x0000ff })).toBe('#0000ff')
    expect(waypointColor({ color: null })).toBe('#e0281e')
  })

  it('Nachricht mit Karte: Schema, Kurzfassung, ältere Nachrichten ohne Feld', () => {
    const page = messagePageSchema.parse(samples.messagePage)
    const m = page.messages.find((x) => x.waypoint)!
    expect(m.waypoint?.name).toBe('Basis')
    expect(messageSummary(m)).toBe('📍 Basis')
    // Nachrichten ohne das Feld (ältere Kerne) bekommen null.
    const old = page.messages.find((x) => !x.waypoint)!
    expect(old.waypoint).toBeNull()
    // Kaputte Karte → ganze Nachricht ungültig (der Kern hätte sie schon verworfen).
    expect(chatMessageSchema.safeParse({ ...m, waypoint: { ...WP, y: 99999 } }).success).toBe(false)
  })

  it('Admin-Liste verträgt Meldungen geteilter Bilder', () => {
    const list = adminReportListSchema.parse(samples.adminReports)
    const share = list.reports.find((r) => r.kind === 'share')
    expect(share?.shareId).toBe('Qm9vLWJhei1xdXV4LTEyMw')
    expect(reportActions).toContain('delete_share')
  })
})

function shared(id = 'Qm9vLWJhei1xdXV4LTEyMw', expiresAt = '2026-10-27T10:00:00.000Z'): SharedImage {
  return sharedImageSchema.parse({
    id,
    url: `https://trs-launcher.theredstonee.de/s/${id}`,
    imageUrl: `https://trs-launcher.theredstonee.de/v1/shares/${id}/image`,
    thumbUrl: `https://trs-launcher.theredstonee.de/v1/shares/${id}/image?thumb=1`,
    mime: 'image/jpeg',
    width: 1920,
    height: 1080,
    bytes: 412_345,
    createdAt: '2026-09-27T10:00:00.000Z',
    expiresAt,
  })
}

describe('Screenshot als Link teilen', () => {
  it('Schema: nur 22-stellige IDs und http(s)-Links', () => {
    expect(() => shared('../../x')).toThrow()
    expect(sharedImageSchema.safeParse({ ...shared(), url: 'javascript:alert(1)' }).success).toBe(false)
    const page = sharesPageSchema.parse({ shares: [shared()], limits: { active: 1, maxActive: 50, uploadsToday: 3, maxPerDay: 20 } })
    expect(page.shares).toHaveLength(1)
  })

  it('Galerie-Knopf: Hinweis statt Anfrage ohne Einwilligung/Konto', () => {
    const base = { statusLoaded: true, enabled: true, hasAccount: true, banned: false }
    expect(shareGate(base)).toBe('ok')
    expect(shareGate({ ...base, statusLoaded: false })).toBe('loading')
    expect(shareGate({ ...base, enabled: false })).toBe('consent')
    expect(shareGate({ ...base, hasAccount: false })).toBe('account')
    expect(shareGate({ ...base, banned: true })).toBe('banned')
  })

  it('Grenzen und Ablauf', () => {
    expect(shareBlockedBy(null)).toBeNull()
    expect(shareBlockedBy({ active: 50, maxActive: 50, uploadsToday: 0, maxPerDay: 20 })).toBe('active')
    expect(shareBlockedBy({ active: 3, maxActive: 50, uploadsToday: 20, maxPerDay: 20 })).toBe('daily')
    expect(shareBlockedBy({ active: 3, maxActive: 50, uploadsToday: 2, maxPerDay: 20 })).toBeNull()
    const now = Date.parse('2026-09-27T10:00:00.000Z')
    expect(daysLeft('2026-10-27T10:00:00.000Z', now)).toBe(30)
    expect(daysLeft('2026-09-27T12:00:00.000Z', now)).toBe(1)
    expect(daysLeft('2026-09-01T00:00:00.000Z', now)).toBe(0)
    expect(daysLeft(null, now)).toBeNull()
    expect(daysLeft('kaputt', now)).toBeNull()
  })

  it('Warteschlange: kein doppelter Upload, Link wird wiederverwendet, Löschen vergisst', async () => {
    const q = new ShareQueue()
    let resolve!: (s: SharedImage) => void
    const upload = vi.fn(() => new Promise<SharedImage>((r) => (resolve = r)))
    const first = q.run('inst/a.png', upload)
    expect(q.busy('inst/a.png')).toBe(true)
    expect(await q.run('inst/a.png', upload)).toBeNull()
    resolve(shared())
    expect((await first)?.id).toBe('Qm9vLWJhei1xdXV4LTEyMw')
    expect(q.busy('inst/a.png')).toBe(false)
    // Zweites Teilen desselben Bildes: gleicher Link, keine neue Anfrage.
    expect((await q.run('inst/a.png', upload))?.id).toBe('Qm9vLWJhei1xdXV4LTEyMw')
    expect(upload).toHaveBeenCalledTimes(1)
    q.forget('Qm9vLWJhei1xdXV4LTEyMw')
    expect(q.shared('inst/a.png')).toBeNull()
    // Fehler: nichts gemerkt, Knopf wieder frei.
    await expect(q.run('inst/b.png', () => Promise.reject(new Error('share_limit')))).rejects.toThrow('share_limit')
    expect(q.busy('inst/b.png')).toBe(false)
    expect(q.shared('inst/b.png')).toBeNull()
  })
})
