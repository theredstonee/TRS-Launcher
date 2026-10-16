import { randomInt } from 'node:crypto'
import {
  BADGES,
  HALLOWEEN_END_ISO,
  HALLOWEEN_REWARD,
  HUNTER_CAPE,
  MONDNACHT_CAPE_IDS,
  MONDNACHT_POOL_ID,
  PUMPKINS,
  PUMPKIN_TOTAL,
  RIDDLES,
  SOUR_ONE_IN,
  STICKERS,
  TRICK_POOL,
  isPumpkinId,
  isStickerId,
  rewardName,
  type BadgeId,
  type Lang3,
  type RewardKind,
} from '../../shared/halloween'
import { badgeFxFor, badgesOf, effectsOf, halloweenFeaturesActive } from './badge-lookup'
import { grantCape } from './capes'
import type { AppContext } from './context'
import { grantCosmetic } from './cosmetics'
import { all, one, run, tx } from './db'
import { ApiError, forbidden, notFound } from './errors'
import { emitToWatchers } from './playerevents'
import { getUser } from './users'

/**
 * Halloween 2026 „Nacht der langen Schatten“ (API.md §36): Profil-Abzeichen, Kürbis-Suche (13), „Süßes oder Saures“
 * (einmal je Berliner Kalendertag), Spuk-Effekt-Freigaben und Chat-Sticker. Alle Funktionen laufen nur, solange das
 * Event `halloween` für den Spieler aktiv ist UND vor `HALLOWEEN_END_MS`; Gewonnenes bleibt für immer.
 */

export { badgesOf, halloweenFeaturesActive } from './badge-lookup'

export interface RewardView {
  kind: RewardKind
  id: string
  name: Lang3
}

export interface HalloweenView {
  active: boolean
  endsAt: string
  pumpkins: { total: number, found: string[], nextHint: Lang3 | null }
  trick: { usedToday: boolean, result?: 'sweet' | 'sour', reward?: RewardView, next: string }
  badges: string[]
  effects: { hits: boolean, trail: boolean }
}

// ---------------------------------------------------------------- Berliner Kalendertag

const BERLIN = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Europe/Berlin',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
})

function berlinParts(ms: number): { y: number, mo: number, d: number, h: number, mi: number, s: number } {
  const p: Record<string, number> = {}
  for (const x of BERLIN.formatToParts(new Date(ms))) if (x.type !== 'literal') p[x.type] = Number(x.value)
  return { y: p.year!, mo: p.month!, d: p.day!, h: p.hour!, mi: p.minute!, s: p.second! }
}

/** Kalendertag in Europe/Berlin als `YYYY-MM-DD`. */
export function berlinDay(ms: number): string {
  const { y, mo, d } = berlinParts(ms)
  return `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`
}

/** Abstand Berliner Ortszeit − UTC (ms) zum Zeitpunkt `ms`. */
function berlinOffset(ms: number): number {
  const p = berlinParts(ms)
  return Date.UTC(p.y, p.mo - 1, p.d, p.h, p.mi, p.s) - Math.floor(ms / 1000) * 1000
}

/** Beginn des nächsten Berliner Kalendertags (Mitternacht Ortszeit) als UTC-ms. */
export function nextBerlinMidnight(ms: number): number {
  const { y, mo, d } = berlinParts(ms)
  const wall = Date.UTC(y, mo - 1, d + 1)
  // Zweimal schätzen: der Versatz kann sich zwischen jetzt und Mitternacht ändern (Sommer-/Winterzeit).
  let t = wall - berlinOffset(wall)
  t = wall - berlinOffset(t)
  return t
}

// ---------------------------------------------------------------- Abzeichen

/** Vergibt ein Profil-Abzeichen (idempotent). `true` = neu. */
export function grantBadge(ctx: AppContext, uuid: string, badge: BadgeId): boolean {
  return run(
    ctx.db,
    'INSERT INTO user_badges (uuid, badge, granted_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING',
    uuid, badge, ctx.now(),
  ) > 0
}

/**
 * Abzeichen „Halloween 2026“: bei jedem angemeldeten `GET /v1/me` (und `GET /v1/me/halloween`), solange die Funktionen
 * laufen. Danach nie mehr vergeben. `true` = gerade neu vergeben (dann sind die Ereignisse schon verschickt).
 */
export function ensureEventBadge(ctx: AppContext, uuid: string): boolean {
  if (!halloweenFeaturesActive(ctx, uuid)) return false
  if (one(ctx.db, "SELECT 1 AS x FROM user_badges WHERE uuid = ? AND badge = 'halloween2026'", uuid) !== undefined) return false
  if (!grantBadge(ctx, uuid, 'halloween2026')) return false
  notifyHalloween(ctx, uuid, { badges: true })
  return true
}

// ---------------------------------------------------------------- Sticker

export function ownedStickers(ctx: AppContext, uuid: string): string[] {
  return all<{ sticker: string }>(
    ctx.db,
    'SELECT u.sticker FROM user_stickers u JOIN stickers s ON s.id = u.sticker WHERE u.uuid = ? ORDER BY s.sort, s.id',
    uuid,
  ).map((r) => r.sticker)
}

export function ownsSticker(ctx: AppContext, uuid: string, id: string): boolean {
  return one(ctx.db, 'SELECT 1 AS x FROM user_stickers WHERE uuid = ? AND sticker = ?', uuid, id) !== undefined
}

export function grantSticker(ctx: AppContext, uuid: string, id: string, source: 'reward' | 'admin'): boolean {
  if (!isStickerId(id)) return false
  return run(
    ctx.db,
    'INSERT INTO user_stickers (uuid, sticker, source, granted_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING',
    uuid, id, source, ctx.now(),
  ) > 0
}

/** Prüft einen Sticker für eine Chat-Nachricht: unbekannt oder nicht freigeschaltet → `403 sticker_locked`. */
export function assertStickerUsable(ctx: AppContext, uuid: string, id: string): void {
  if (!isStickerId(id) || !ownsSticker(ctx, uuid, id)) throw forbidden('sticker_locked', 'You have not unlocked this sticker')
}

/** Bild-Datei eines Stickers/Abzeichens im Asset-Ordner (`stickers/<id>.png`, `badges/<id>.png`). */
export function artKey(kind: 'stickers' | 'badges', id: string): string {
  return `${kind}/${id}.png`
}

function artVersion(ctx: AppContext, key: string): string {
  const f = ctx.halloweenArt.get(key)
  return f ? `?v=${f.sha256.slice(0, 12)}` : ''
}

export function stickerUrl(ctx: AppContext, id: string): string {
  return `${ctx.config.publicBaseUrl}/v1/stickers/${id}.png${artVersion(ctx, artKey('stickers', id))}`
}

export function badgeUrl(ctx: AppContext, id: string): string {
  return `${ctx.config.publicBaseUrl}/v1/badges/${id}.png${artVersion(ctx, artKey('badges', id))}`
}

/** `GET /v1/stickers` (öffentlich). */
export function stickerList(ctx: AppContext): { stickers: { id: string, name: Lang3, url: string }[] } {
  return { stickers: STICKERS.map((s) => ({ id: s.id, name: s.name, url: stickerUrl(ctx, s.id) })) }
}

/** `GET /v1/badges` (öffentlich): alle Profil-Abzeichen mit Namen und Bild. */
export function badgeList(ctx: AppContext): { badges: { id: string, name: Lang3, url: string }[] } {
  return { badges: (Object.keys(BADGES) as BadgeId[]).map((id) => ({ id, name: BADGES[id].name, url: badgeUrl(ctx, id) })) }
}

// ---------------------------------------------------------------- Sicht

function foundOf(ctx: AppContext, uuid: string): string[] {
  const set = new Set(all<{ pumpkin: string }>(ctx.db, 'SELECT pumpkin FROM halloween_pumpkins WHERE uuid = ?', uuid).map((r) => r.pumpkin))
  // In Hinweis-Reihenfolge; unbekannte (alte) IDs fallen weg.
  return PUMPKINS.filter((p) => set.has(p))
}

function rewardView(kind: RewardKind | null, id: string | null): RewardView | undefined {
  if (!kind || !id) return undefined
  return { kind, id, name: rewardName(kind, id) ?? { de: id, en: id, es: id } }
}

export function halloweenView(ctx: AppContext, uuid: string): HalloweenView {
  const t = ctx.now()
  const found = foundOf(ctx, uuid)
  const next = PUMPKINS.find((p) => !found.includes(p))
  const today = one<{ result: 'sweet' | 'sour', reward_kind: RewardKind | null, reward_id: string | null }>(
    ctx.db,
    'SELECT result, reward_kind, reward_id FROM halloween_tricks WHERE uuid = ? AND day = ?',
    uuid, berlinDay(t),
  )
  const reward = today ? rewardView(today.reward_kind, today.reward_id) : undefined
  return {
    active: halloweenFeaturesActive(ctx, uuid),
    endsAt: HALLOWEEN_END_ISO,
    pumpkins: { total: PUMPKIN_TOTAL, found, nextHint: next ? RIDDLES[next] : null },
    trick: {
      usedToday: !!today,
      ...(today ? { result: today.result } : {}),
      ...(reward ? { reward } : {}),
      next: new Date(nextBerlinMidnight(t)).toISOString(),
    },
    badges: badgesOf(ctx, uuid),
    effects: effectsOf(ctx, uuid),
  }
}

/**
 * Nach einer Änderung: `halloween_updated` an die eigenen Geräte (`GET /v1/events/me`) und – wenn sich Abzeichen oder
 * Effekte geändert haben – `badges` an alle, die den Spieler beobachten (`GET /v1/events/players`).
 */
export function notifyHalloween(ctx: AppContext, uuid: string, opts: { badges?: boolean } = {}): void {
  if (ctx.events.wants(uuid)) ctx.events.publish(uuid, { type: 'halloween_updated', ...halloweenView(ctx, uuid) }, { meOnly: true })
  if (opts.badges) emitBadges(ctx, uuid)
}

/** Spieler-Stream: neue `badges`/`fx` dieses Spielers (gefiltert je Betrachter wie im Lookup). */
export function emitBadges(ctx: AppContext, subject: string): number {
  const u = getUser(ctx, subject)
  if (!u) return 0
  return emitToWatchers(ctx, subject, (self) => {
    const v = badgeFxFor(ctx, self ? subject : '', [{ uuid: subject, show_badge: u.show_badge }]).get(subject)!
    return { type: 'badges', uuid: subject, badges: v.badges, fx: v.fx }
  })
}

function assertActive(ctx: AppContext, uuid: string): void {
  if (!halloweenFeaturesActive(ctx, uuid)) throw forbidden('event_inactive', 'Halloween is not active for you')
}

// ---------------------------------------------------------------- Kürbis-Suche

export interface FindResult {
  found: string[]
  count: number
  complete: boolean
  /** Nur bei dem Fund, der die Suche abschließt. */
  reward?: { badge: BadgeId, cape: string }
}

/** Gibt Kürbisjäger-Abzeichen + Umhang (idempotent). `true` = etwas war neu. */
function grantHunter(ctx: AppContext, uuid: string): boolean {
  const badge = grantBadge(ctx, uuid, 'pumpkinhunter2026')
  let cape = false
  if (one(ctx.db, 'SELECT 1 AS x FROM capes WHERE id = ?', HUNTER_CAPE.id) !== undefined) cape = grantCape(ctx, uuid, HUNTER_CAPE.id, 'reward')
  else console.warn(`[trs-api] halloween: cape ${HUNTER_CAPE.id} missing – granted once it exists (next start)`)
  return badge || cape
}

/**
 * Kürbis gefunden (`POST /v1/me/halloween/pumpkins/{id}`). Unbekannte ID → `404 pumpkin_unknown`, Event aus →
 * `403 event_inactive`. Idempotent: ein schon gefundener zählt nicht doppelt. Der 13. gibt Abzeichen + Umhang.
 */
export function findPumpkin(ctx: AppContext, uuid: string, id: string): FindResult {
  if (!isPumpkinId(id)) throw notFound('pumpkin_unknown', 'Unknown pumpkin')
  assertActive(ctx, uuid)
  const { added, rewarded } = tx(ctx.db, () => {
    const n = run(
      ctx.db,
      'INSERT INTO halloween_pumpkins (uuid, pumpkin, found_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING',
      uuid, id, ctx.now(),
    )
    const complete = foundOf(ctx, uuid).length >= PUMPKIN_TOTAL
    return { added: n > 0, rewarded: n > 0 && complete ? grantHunter(ctx, uuid) : false }
  })
  const found = foundOf(ctx, uuid)
  const complete = found.length >= PUMPKIN_TOTAL
  if (added) notifyHalloween(ctx, uuid, { badges: rewarded })
  return {
    found,
    count: found.length,
    complete,
    ...(added && complete ? { reward: { badge: 'pumpkinhunter2026' as const, cape: HUNTER_CAPE.id } } : {}),
  }
}

// ---------------------------------------------------------------- Süßes oder Saures

export type TrickResult =
  | { result: 'sweet', reward: RewardView, next: string }
  | { result: 'sour', next: string }

/** Besitzt `uuid` dieses Teil des Topfs wirklich (Zeile, ohne Team-Sonderrecht)? */
function ownsPoolItem(ctx: AppContext, uuid: string, kind: RewardKind, id: string): boolean {
  if (kind === 'cape' && id === MONDNACHT_POOL_ID) return ownedMondnacht(ctx, uuid) !== null
  if (kind === 'cape') return one(ctx.db, 'SELECT 1 AS x FROM user_capes WHERE uuid = ? AND cape_id = ?', uuid, id) !== undefined
  if (kind === 'emote') return one(ctx.db, 'SELECT 1 AS x FROM user_cosmetics WHERE uuid = ? AND cosmetic_id = ?', uuid, id) !== undefined
  return ownsSticker(ctx, uuid, id)
}

/** Gibt es das Teil (Umhang/Emote eingespielt, Sticker angelegt)? Fehlende werden nie verlost. */
function poolItemExists(ctx: AppContext, kind: RewardKind, id: string): boolean {
  if (kind === 'cape' && id === MONDNACHT_POOL_ID) return existingMondnacht(ctx).length > 0
  if (kind === 'cape') return one(ctx.db, 'SELECT 1 AS x FROM capes WHERE id = ? AND retired = 0', id) !== undefined
  if (kind === 'emote') return one(ctx.db, "SELECT 1 AS x FROM cosmetics WHERE id = ? AND slot = 'emote' AND retired = 0", id) !== undefined
  return one(ctx.db, 'SELECT 1 AS x FROM stickers WHERE id = ?', id) !== undefined
}

/** Noch nicht besessene Teile des Topfs (Reihenfolge wie im Topf). */
export function unownedPool(ctx: AppContext, uuid: string): { kind: RewardKind, id: string }[] {
  return TRICK_POOL.filter((p) => poolItemExists(ctx, p.kind, p.id) && !ownsPoolItem(ctx, uuid, p.kind, p.id))
}

/** Gibt das Teil und liefert, was es tatsächlich wurde (Mondnacht: die gewürfelte Farbe). */
function grantPoolItem(ctx: AppContext, uuid: string, kind: RewardKind, id: string, roll: Roll): { kind: RewardKind, id: string } {
  if (kind === 'cape' && id === MONDNACHT_POOL_ID) {
    const colours = existingMondnacht(ctx)
    const colour = colours[roll(colours.length)]!
    grantCape(ctx, uuid, colour, 'reward')
    return { kind, id: colour }
  }
  if (kind === 'cape') grantCape(ctx, uuid, id, 'reward')
  else if (kind === 'emote') grantCosmetic(ctx, uuid, id, 'reward')
  else grantSticker(ctx, uuid, id, 'reward')
  return { kind, id }
}

/** Eingespielte (nicht ausgemusterte) Mondnacht-Farben in fester Reihenfolge. */
function existingMondnacht(ctx: AppContext): string[] {
  const have = new Set(all<{ id: string }>(
    ctx.db,
    `SELECT id FROM capes WHERE retired = 0 AND id IN (${MONDNACHT_CAPE_IDS.map(() => '?').join(', ')})`,
    ...MONDNACHT_CAPE_IDS,
  ).map((r) => r.id))
  return MONDNACHT_CAPE_IDS.filter((id) => have.has(id))
}

/** Die Mondnacht-Farbe, die `uuid` besitzt (höchstens eine), sonst `null`. */
export function ownedMondnacht(ctx: AppContext, uuid: string): string | null {
  return one<{ cape_id: string }>(
    ctx.db,
    `SELECT cape_id FROM user_capes WHERE uuid = ? AND cape_id IN (${MONDNACHT_CAPE_IDS.map(() => '?').join(', ')}) LIMIT 1`,
    uuid, ...MONDNACHT_CAPE_IDS,
  )?.cape_id ?? null
}

/** Zufall (krypto-sicher), in Tests ersetzbar: liefert eine ganze Zahl in [0, n). */
export type Roll = (n: number) => number
const cryptoRoll: Roll = (n) => randomInt(n)

/**
 * Einmal anklopfen (`POST /v1/me/halloween/trick`): ein Wurf je Berliner Kalendertag (`409 trick_used` + `next`).
 * 1 von 4 = Saures; sonst ein zufälliges noch nicht besessenes Teil des Topfs (11; der Mondnacht-Umhang zählt als eines,
 * die Farbe wird dann gewürfelt, und wer schon eine Farbe hat, bekommt keine weitere). Alles schon da → Saures.
 */
export function trickOrTreat(ctx: AppContext, uuid: string, roll: Roll = cryptoRoll): TrickResult {
  assertActive(ctx, uuid)
  const t = ctx.now()
  const day = berlinDay(t)
  const next = new Date(nextBerlinMidnight(t)).toISOString()
  // `undefined` = heute schon geworfen, `null` = Saures, sonst das gewonnene Teil.
  const pick = tx(ctx.db, (): { kind: RewardKind, id: string } | null | undefined => {
    if (one(ctx.db, 'SELECT 1 AS x FROM halloween_tricks WHERE uuid = ? AND day = ?', uuid, day) !== undefined) return undefined
    const pool = unownedPool(ctx, uuid)
    const sour = roll(SOUR_ONE_IN) === 0 || pool.length === 0
    const item = sour ? null : pool[roll(pool.length)]!
    const won = item ? grantPoolItem(ctx, uuid, item.kind, item.id, roll) : null
    run(
      ctx.db,
      'INSERT INTO halloween_tricks (uuid, day, result, reward_kind, reward_id, rolled_at) VALUES (?, ?, ?, ?, ?, ?)',
      uuid, day, won ? 'sweet' : 'sour', won?.kind ?? null, won?.id ?? null, t,
    )
    return won
  })
  if (pick === undefined) throw new ApiError(409, 'trick_used', 'You already knocked today – come back tomorrow', { next })
  notifyHalloween(ctx, uuid)
  if (pick === null) return { result: 'sour', next }
  return { result: 'sweet', reward: rewardView(pick.kind, pick.id)!, next }
}

// ---------------------------------------------------------------- Spuk-Effekte

/** `PUT /v1/me/halloween/effects`: dürfen ANDERE TRS-Spieler meine Spuk-Effekte sehen? */
export function setEffects(ctx: AppContext, uuid: string, input: { hits: boolean, trail: boolean }): { hits: boolean, trail: boolean } {
  const before = effectsOf(ctx, uuid)
  run(
    ctx.db,
    `INSERT INTO halloween_effects (uuid, hits, trail, updated_at) VALUES (?, ?, ?, ?)
     ON CONFLICT(uuid) DO UPDATE SET hits = excluded.hits, trail = excluded.trail, updated_at = excluded.updated_at`,
    uuid, input.hits ? 1 : 0, input.trail ? 1 : 0, ctx.now(),
  )
  const after = effectsOf(ctx, uuid)
  if (before.hits !== after.hits || before.trail !== after.trail) notifyHalloween(ctx, uuid, { badges: true })
  return after
}

// ---------------------------------------------------------------- Start

/**
 * Beim Start (nach dem Einspielen des Katalogs): wer alle 13 Kürbisse hat, aber den Kürbisjäger-Umhang noch nicht (weil
 * er beim Fund fehlte), bekommt ihn jetzt. Gibt die Zahl der Nachträge zurück.
 */
export function grantPendingHalloweenRewards(ctx: AppContext): number {
  if (one(ctx.db, 'SELECT 1 AS x FROM capes WHERE id = ?', HUNTER_CAPE.id) === undefined) return 0
  const rows = all<{ uuid: string }>(
    ctx.db,
    `SELECT p.uuid FROM halloween_pumpkins p
     WHERE p.pumpkin IN (${PUMPKINS.map(() => '?').join(', ')})
     GROUP BY p.uuid HAVING COUNT(*) >= ?`,
    ...PUMPKINS, PUMPKIN_TOTAL,
  )
  let n = 0
  for (const r of rows) if (grantHunter(ctx, r.uuid)) n++
  return n
}

export { HALLOWEEN_REWARD }
