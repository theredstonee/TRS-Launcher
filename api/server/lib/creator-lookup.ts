import type { AppContext } from './context'
import { all, one, placeholders } from './db'
import { ACTIVE_BANS } from './users'

/**
 * Creator-Daten für den Spieler-Lookup (§35.5) – getrennt von creators.ts, damit lookup.ts keine Kette über
 * playerevents.ts zurück in sich selbst bekommt. Nur Lesen, keine Ereignisse.
 */

export type CreatorTier = 'starter' | 'partner' | 'top'
export type CreatorState = 'active' | 'revoked' | 'banned'
export type Milestone = 'bronze' | 'silver' | 'gold' | 'diamond'

/** Fan-Symbol: senkrechter Streifen, je Frame `size`×`size`. Die URL ist unveränderlich (Inhalt = sha256). */
export interface SymbolView {
  url: string
  size: number
  frames: number
  frameMs: number
  sha256: string
}

export interface SymbolRowLike {
  sha256: string
  size: number
  frames: number
  frame_ms: number
}

export function symbolUrl(sha256: string): string {
  return `/v1/creator-symbols/${sha256}.png`
}

export function symbolView(r: SymbolRowLike): SymbolView {
  return { url: symbolUrl(r.sha256), size: r.size, frames: r.frames, frameMs: r.frame_ms, sha256: r.sha256 }
}

export interface LookupCreator {
  tier: CreatorTier
}

export interface LookupFan {
  creator: string
  name: string
  symbol: SymbolView
  milestone: Milestone | null
}

/** Creator, die `viewer` blockiert hat (leer für „irgendein anderer“ = `''`). */
export function blockedCreatorsOf(ctx: AppContext, viewer: string): Set<string> {
  if (!viewer) return new Set()
  return new Set(all<{ creator_uuid: string }>(ctx.db, 'SELECT creator_uuid FROM creator_blocks WHERE uuid = ?', viewer).map((r) => r.creator_uuid))
}

/** Will `viewer` Fan-Symbole ANDERER sehen? (`''` = ja) */
export function showsFanSymbols(ctx: AppContext, viewer: string): boolean {
  if (!viewer) return true
  return (one<{ v: number }>(ctx.db, 'SELECT show_fan_symbols AS v FROM users WHERE uuid = ?', viewer)?.v ?? 1) === 1
}

/**
 * `creator` (der Spieler IST ein aktiver Creator) und `fan` (er unterstützt einen aktiven Creator mit freigegebenem
 * Symbol) für `uuids`, schon gefiltert für `viewer`: blockierte Creator fehlen, und ohne `showFanSymbols` gibt es
 * `fan` nur für den Fragenden selbst.
 */
export function creatorLookupData(
  ctx: AppContext,
  viewer: string,
  uuids: readonly string[],
): { creators: Map<string, LookupCreator>, fans: Map<string, LookupFan> } {
  const creators = new Map<string, LookupCreator>()
  const fans = new Map<string, LookupFan>()
  if (uuids.length === 0) return { creators, fans }
  const blocked = blockedCreatorsOf(ctx, viewer)
  const showOthers = showsFanSymbols(ctx, viewer)
  const t = ctx.now()
  for (const r of all<{ uuid: string, tier: CreatorTier }>(
    ctx.db,
    `SELECT uuid, tier FROM creators WHERE state = 'active' AND uuid IN (${placeholders(uuids.length)})`,
    ...uuids,
  )) {
    if (!blocked.has(r.uuid)) creators.set(r.uuid, { tier: r.tier })
  }
  const rows = all<{ fan_uuid: string, creator_uuid: string, name: string, milestone: Milestone | null } & SymbolRowLike>(
    ctx.db,
    `SELECT s.fan_uuid, s.creator_uuid, u.name, c.milestone, y.sha256, y.size, y.frames, y.frame_ms
     FROM creator_supports s
     JOIN creators c ON c.uuid = s.creator_uuid AND c.state = 'active'
     JOIN creator_symbols y ON y.id = c.symbol_id AND y.status = 'approved'
     JOIN users u ON u.uuid = c.uuid
     WHERE s.fan_uuid IN (${placeholders(uuids.length)}) AND c.uuid NOT IN (${ACTIVE_BANS})`,
    ...uuids, t,
  )
  for (const r of rows) {
    if (blocked.has(r.creator_uuid)) continue
    if (r.fan_uuid !== viewer && !showOthers) continue
    fans.set(r.fan_uuid, { creator: r.creator_uuid, name: r.name, symbol: symbolView(r), milestone: r.milestone })
  }
  return { creators, fans }
}
