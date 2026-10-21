// Creator auf der Website (API.md §35.11): Typen der öffentlichen Liste und des Dashboards, Zahlen-Kurzform, Link-Symbole.
import type { ProfileLink, ProfileLinkType } from '#shared/creator-profile'

export type { DescriptionBlock, ProfileLink, ProfileLinkType } from '#shared/creator-profile'
export { PROFILE_DESCRIPTION_MAX, PROFILE_LINK_TYPES, PROFILE_MAX_LINKS, checkProfileLink, descriptionBlocks } from '#shared/creator-profile'

export type CreatorTier = 'starter' | 'partner' | 'top'
export const CREATOR_TIERS: CreatorTier[] = ['top', 'partner', 'starter']

export interface SiteCreatorPack {
  code: string
  name: string
  summary?: string
  mcVersion: string
  loader: string
  installs: number
}

export interface SiteCreator {
  uuid: string
  name: string
  tier: CreatorTier
  skin: string | null
  fanCount: number
  milestone: 'bronze' | 'silver' | 'gold' | 'diamond' | null
  since: string
  symbol: { url: string, size: number, frames: number, frameMs: number } | null
  fanCape: { name: string, url: string, scale: number, frames: number, frameTimeMs: number | null } | null
  description: string
  links: ProfileLink[]
  packs: SiteCreatorPack[]
}

/** Symbol je Link-Art (SiteIcon). */
export const LINK_ICONS: Record<ProfileLinkType, string> = {
  youtube: 'youtube',
  twitch: 'twitch',
  tiktok: 'tiktok',
  discord: 'discord',
  instagram: 'instagram',
  x: 'xsocial',
  website: 'globe',
}

/** Kurzform für Fan-Zahlen in jeder Sprache gleich (K/M, Dezimalzeichen der Seitensprache): 950, 1.2K, 12K, 1.3M … */
export function compactCount(n: number, lang: string): string {
  const v = Math.max(0, Math.floor(n))
  const fmt = (x: number) => new Intl.NumberFormat(lang === 'en' ? 'en' : lang, { maximumFractionDigits: x < 100 ? 1 : 0 }).format(Math.floor(x * 10) / 10)
  if (v < 1000) return String(v)
  if (v < 1_000_000) return `${fmt(v / 1000)}K`
  return `${fmt(v / 1_000_000)}M`
}

/** Pfad der Creator-Seite (Name in der Adresse, Groß/klein egal). */
export function creatorPath(name: string): string {
  return `/creators/${encodeURIComponent(name)}`
}

// ---------------------------------------------------------------- Dashboard (§35.3, über die Website-Sitzung)

export interface CreatorSymbolInfo { url: string, size: number, frames: number, frameMs: number, sha256: string }
export interface FanCapeRef { capeId: string, name: string, url: string }
export interface Rejection { reason: string | null, at: string }
export interface ProfileRevision { description: string, links: ProfileLink[], at: string }
export interface ProfileState { approved: ProfileRevision | null, pending: ProfileRevision | null, rejected: Rejection | null }
export type HelperRight = 'stats' | 'codes' | 'uploads' | 'packs'
export const HELPER_RIGHTS: HelperRight[] = ['stats', 'codes', 'uploads', 'packs']

export interface CreatorCode {
  code: string
  kind: 'main' | 'extra'
  uses: number
  maxUses: number | null
  expiresAt: string | null
  active: boolean
  createdAt: string
}

export interface CreatorDashboard {
  uuid: string
  name: string
  tier: CreatorTier
  state: 'active' | 'revoked' | 'banned'
  reason: string | null
  /** Mojang-Skin für den Kopf (neu, ältere API-Stände ohne). */
  skin?: string | null
  since: string
  channels: { platform: string, url: string, followers: number }[]
  codes: CreatorCode[]
  limits: { extraCodes: number | null, maxUsesPerCode: number | null, symbolFrames: number, fanCape: false | 'standard' | 'hd' }
  stats: {
    activeFans: number
    redemptions: number
    fanCapeHolders: number
    newPlayers: number
    packInstalls: number
    daily: { day: string, newFans: number, redemptions: number }[]
  } | null
  milestone: SiteCreator['milestone']
  nextMilestone: { name: string, at: number } | null
  symbol: { current: CreatorSymbolInfo | null, pending: CreatorSymbolInfo | null, rejected: Rejection | null }
  fanCape: { current: FanCapeRef | null, pending: FanCapeRef | null, rejected: Rejection | null }
  requests: { id: number, kind: 'upgrade' | 'team_cape', status: 'open' | 'accepted' | 'declined', createdAt: string, reason: string | null }[]
  packs: { code: string, name: string, installs: number, listed: boolean }[]
  helpers?: { uuid: string, name: string, skin: string | null, rights: HelperRight[], status: 'pending' | 'active', createdAt: string }[]
  viewer?: { helper: true, rights: HelperRight[] }
  profile?: ProfileState
}

export interface MyCreatorState {
  creator: CreatorDashboard | null
  application: { id: string, status: string, createdAt: string } | null
  helping: { creator: { uuid: string, name: string, tier: CreatorTier }, rights: HelperRight[] }[]
}

/** Texturen immer über diese Seite laden (Cookie für wartende Uploads), auch wenn die API eine absolute Adresse nennt. */
export function sameOriginPath(url: string): string {
  if (!import.meta.client) return url
  try {
    const u = new URL(url, location.origin)
    return u.pathname + u.search
  } catch {
    return url
  }
}
